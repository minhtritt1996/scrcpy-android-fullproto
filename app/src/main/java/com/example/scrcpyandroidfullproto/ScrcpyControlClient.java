package com.example.scrcpyandroidfullproto;

import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

public final class ScrcpyControlClient {
    public interface Listener {
        void onStatus(String text);
        void onError(String text, Throwable throwable);
        void onClipboardText(String text);
    }

    private static final int TYPE_INJECT_KEYCODE = 0;
    private static final int TYPE_INJECT_TOUCH_EVENT = 2;
    private static final int TYPE_GET_CLIPBOARD = 8;
    private static final int TYPE_SET_CLIPBOARD = 9;
    private static final int DEVICE_MSG_TYPE_CLIPBOARD = 0;
    private static final int DEVICE_MSG_TYPE_ACK_CLIPBOARD = 1;
    private static final int DEVICE_MSG_TYPE_UHID_OUTPUT = 2;
    private static final int COPY_KEY_NONE = 0;
    private static final int MAX_CLIPBOARD_TEXT_BYTES = (1 << 18) - 14;
    private static final int MAX_DEVICE_MESSAGE_SIZE = 1 << 18;
    private static final int CONNECT_RETRIES = 20;
    private static final int MAX_QUEUE_SIZE = 120;

    private final Listener listener;
    private final Object queueLock = new Object();
    private final ArrayDeque<QueuedMessage> queue = new ArrayDeque<>();
    private final AtomicLong clipboardSequence = new AtomicLong(1);
    private volatile boolean running;
    private volatile Socket socket;
    private volatile OutputStream output;
    private Thread worker;

    private static final class QueuedMessage {
        private final byte[] payload;
        private final boolean droppable;

        private QueuedMessage(byte[] payload, boolean droppable) {
            this.payload = payload;
            this.droppable = droppable;
        }
    }

    public ScrcpyControlClient(Listener listener) {
        this.listener = listener;
    }

    public synchronized void start(String host, int port) {
        stop();
        running = true;
        worker = new Thread(() -> runLoop(host, port), "scrcpy-control-client");
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        Thread currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.interrupt();
            worker = null;
        }
        synchronized (queueLock) {
            queue.clear();
            queueLock.notifyAll();
        }
        closeSocket();
    }

    public boolean isReady() {
        return running && output != null;
    }

    public void sendKeyPress(int keyCode) {
        sendKeyEvent(KeyEvent.ACTION_DOWN, keyCode);
        sendKeyEvent(KeyEvent.ACTION_UP, keyCode);
    }

    public void sendKeyEvent(int action, int keyCode) {
        byte[] payload = new byte[14];
        payload[0] = (byte) TYPE_INJECT_KEYCODE;
        payload[1] = (byte) action;
        writeInt(payload, 2, keyCode);
        writeInt(payload, 6, 0);  // repeat
        writeInt(payload, 10, 0); // metastate
        enqueue(payload, false);
    }

    public void sendTouchEvent(int action, long pointerId, int x, int y,
                               int screenWidth, int screenHeight, float pressure) {
        int clampedWidth = clampToU16(screenWidth);
        int clampedHeight = clampToU16(screenHeight);
        int clampedX = Math.max(0, Math.min(x, Math.max(0, screenWidth - 1)));
        int clampedY = Math.max(0, Math.min(y, Math.max(0, screenHeight - 1)));
        float normalizedPressure = action == MotionEvent.ACTION_UP ? 0f : pressure;

        byte[] payload = new byte[32];
        payload[0] = (byte) TYPE_INJECT_TOUCH_EVENT;
        payload[1] = (byte) action;
        writeLong(payload, 2, pointerId);
        writeInt(payload, 10, clampedX);
        writeInt(payload, 14, clampedY);
        writeShort(payload, 18, clampedWidth);
        writeShort(payload, 20, clampedHeight);
        writeShort(payload, 22, encodeU16FixedPoint(normalizedPressure));
        writeInt(payload, 24, 0); // actionButton
        writeInt(payload, 28, 0); // buttons

        enqueue(payload, action == MotionEvent.ACTION_MOVE);
    }

    public void setDeviceClipboard(String text, boolean paste) {
        if (text == null) {
            return;
        }
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        int textLength = Math.min(raw.length, MAX_CLIPBOARD_TEXT_BYTES);
        byte[] payload = new byte[14 + textLength];
        payload[0] = (byte) TYPE_SET_CLIPBOARD;
        long sequence = clipboardSequence.getAndIncrement();
        writeLong(payload, 1, sequence);
        payload[9] = (byte) (paste ? 1 : 0);
        writeInt(payload, 10, textLength);
        if (textLength > 0) {
            System.arraycopy(raw, 0, payload, 14, textLength);
        }
        enqueue(payload, false);
    }

    public void requestDeviceClipboard() {
        byte[] payload = new byte[2];
        payload[0] = (byte) TYPE_GET_CLIPBOARD;
        payload[1] = (byte) COPY_KEY_NONE;
        enqueue(payload, false);
    }

    private void enqueue(byte[] payload, boolean droppable) {
        synchronized (queueLock) {
            if (!running || output == null) {
                return;
            }
            if (queue.size() >= MAX_QUEUE_SIZE) {
                if (droppable) {
                    return;
                }
                if (!dropOldestDroppableLocked()) {
                    return;
                }
            }
            queue.addLast(new QueuedMessage(payload, droppable));
            queueLock.notifyAll();
        }
    }

    private boolean dropOldestDroppableLocked() {
        Iterator<QueuedMessage> iterator = queue.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().droppable) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private QueuedMessage takeNextMessage() throws InterruptedException {
        synchronized (queueLock) {
            while (running && queue.isEmpty()) {
                queueLock.wait();
            }
            if (!running || queue.isEmpty()) {
                return null;
            }
            return queue.removeFirst();
        }
    }

    private void runLoop(String host, int port) {
        Socket localSocket = null;
        InputStream localInput = null;
        OutputStream localOutput = null;
        Thread readerThread = null;
        try {
            localSocket = connectWithRetry(host, port);
            localSocket.setTcpNoDelay(true);
            localInput = localSocket.getInputStream();
            localOutput = localSocket.getOutputStream();
            synchronized (queueLock) {
                socket = localSocket;
                output = localOutput;
                queue.clear();
            }
            InputStream finalLocalInput = localInput;
            readerThread = new Thread(() -> readDeviceMessages(finalLocalInput), "scrcpy-control-reader");
            readerThread.start();
            listener.onStatus("Control channel connected");

            while (running && !Thread.currentThread().isInterrupted()) {
                QueuedMessage message = takeNextMessage();
                if (message == null) {
                    continue;
                }
                localOutput.write(message.payload);
                localOutput.flush();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            if (running) {
                listener.onError("Control error (" + t.getClass().getSimpleName() + "): " + safeMessage(t), t);
            }
        } finally {
            running = false;
            synchronized (queueLock) {
                output = null;
                socket = null;
                queue.clear();
                queueLock.notifyAll();
            }
            if (localSocket != null) {
                try {
                    localSocket.close();
                } catch (IOException ignored) {
                }
            }
            if (readerThread != null) {
                readerThread.interrupt();
                try {
                    readerThread.join(300);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void readDeviceMessages(InputStream input) {
        DataInputStream dis = new DataInputStream(input);
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                int type = dis.read();
                if (type < 0) {
                    throw new EOFException("control channel closed");
                }
                switch (type) {
                    case DEVICE_MSG_TYPE_CLIPBOARD:
                        int len = dis.readInt();
                        if (len < 0 || len > MAX_DEVICE_MESSAGE_SIZE - 5) {
                            throw new IOException("Invalid clipboard message length: " + len);
                        }
                        byte[] data = new byte[len];
                        dis.readFully(data);
                        listener.onClipboardText(new String(data, StandardCharsets.UTF_8));
                        break;
                    case DEVICE_MSG_TYPE_ACK_CLIPBOARD:
                        dis.readLong();
                        break;
                    case DEVICE_MSG_TYPE_UHID_OUTPUT:
                        dis.readUnsignedShort();
                        int size = dis.readUnsignedShort();
                        if (size > 0) {
                            byte[] ignored = new byte[size];
                            dis.readFully(ignored);
                        }
                        break;
                    default:
                        throw new IOException("Unknown device message type: " + type);
                }
            }
        } catch (Throwable t) {
            if (running) {
                listener.onError("Control read error (" + t.getClass().getSimpleName() + "): " + safeMessage(t), t);
            }
        }
    }

    private Socket connectWithRetry(String host, int port) throws Exception {
        Exception lastError = null;
        for (int i = 1; i <= CONNECT_RETRIES && running; i++) {
            Socket attempt = new Socket();
            try {
                listener.onStatus("Connecting control " + host + ":" + port + " (try " + i + "/" + CONNECT_RETRIES + ")");
                attempt.connect(new InetSocketAddress(host, port), 4000);
                return attempt;
            } catch (Exception e) {
                lastError = e;
                try {
                    attempt.close();
                } catch (IOException ignored) {
                }
                Thread.sleep(250);
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IOException("Unable to connect control");
    }

    private void closeSocket() {
        Socket current = socket;
        socket = null;
        output = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static int clampToU16(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 0xFFFF);
    }

    private static int encodeU16FixedPoint(float value) {
        float clamped = Math.max(0f, Math.min(1f, value));
        return Math.round(clamped * 65535f);
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) ((value >>> 24) & 0xFF);
        target[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 3] = (byte) (value & 0xFF);
    }

    private static void writeLong(byte[] target, int offset, long value) {
        target[offset] = (byte) ((value >>> 56) & 0xFF);
        target[offset + 1] = (byte) ((value >>> 48) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 40) & 0xFF);
        target[offset + 3] = (byte) ((value >>> 32) & 0xFF);
        target[offset + 4] = (byte) ((value >>> 24) & 0xFF);
        target[offset + 5] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 6] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 7] = (byte) (value & 0xFF);
    }

    private static void writeShort(byte[] target, int offset, int value) {
        target[offset] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 1] = (byte) (value & 0xFF);
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "no details";
        }
        return message;
    }
}
