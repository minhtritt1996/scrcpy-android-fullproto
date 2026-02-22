package com.example.scrcpyandroidfullproto;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import com.example.scrcpy.proto.ScrcpyVideoStreamReader;
import com.example.scrcpy.proto.VideoCodecMetadata;

import java.io.IOException;
import java.io.EOFException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.Socket;
import java.nio.ByteBuffer;

public final class ScrcpyVideoClient {
    public interface Listener {
        void onStatus(String text);
        void onError(String text, Throwable throwable);
        void onResolutionChanged(int width, int height);
        void onVideoSocketConnected();
    }

    private final Listener listener;
    private Thread worker;
    private volatile boolean running;
    private static final int STREAM_READ_TIMEOUT_MS = 45000;

    public ScrcpyVideoClient(Listener listener) {
        this.listener = listener;
    }

    public void start(String host, int port, Surface surface) {
        stop();
        running = true;
        worker = new Thread(() -> runLoop(host, port, surface), "scrcpy-video-client");
        worker.start();
    }

    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private void runLoop(String host, int port, Surface surface) {
        final int maxSessionAttempts = 6;
        for (int attempt = 1; attempt <= maxSessionAttempts && running && !Thread.currentThread().isInterrupted(); attempt++) {
            Socket socket = null;
            String stage = "init";
            try {
                stage = "connect";
                socket = connectWithRetry(host, port);
                listener.onVideoSocketConnected();
                listener.onStatus("Stream socket connected, waiting metadata...");

                stage = "read_codec_metadata";
                ScrcpyVideoStreamReader reader = new ScrcpyVideoStreamReader(socket.getInputStream());
                VideoCodecMetadata meta = reader.readCodecMetadata();
                listener.onResolutionChanged(meta.width, meta.height);
                listener.onStatus("Codec id=" + meta.codecId + " size=" + meta.width + "x" + meta.height);

                stage = "create_decoder";
                MediaCodec decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                try {
                    boolean configured = false;
                    while (running && !Thread.currentThread().isInterrupted()) {
                        stage = "read_packet";
                        ScrcpyVideoStreamReader.Packet packet = reader.readPacket();
                        if (packet.header.config) {
                            stage = "parse_avc_config";
                            ByteBuffer[] csd = AvcConfigParser.extractCsd(packet.data);
                            if (csd != null) {
                                stage = "configure_decoder";
                                MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, meta.width, meta.height);
                                format.setByteBuffer("csd-0", csd[0]);
                                format.setByteBuffer("csd-1", csd[1]);
                                if (configured) {
                                    decoder.stop();
                                }
                                decoder.configure(format, surface, null, 0);
                                decoder.start();
                                configured = true;
                                listener.onStatus("Decoder configured");
                            }
                            continue;
                        }

                        if (!configured) {
                            continue;
                        }

                        stage = "dequeue_input";
                        int index = decoder.dequeueInputBuffer(10000);
                        if (index >= 0) {
                            stage = "queue_input";
                            ByteBuffer inputBuffer = decoder.getInputBuffer(index);
                            if (inputBuffer != null) {
                                inputBuffer.clear();
                                inputBuffer.put(packet.data);
                                int flags = packet.header.keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                                decoder.queueInputBuffer(index, 0, packet.data.length, packet.header.ptsUs, flags);
                            }
                        }

                        stage = "dequeue_output";
                        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                        int outIndex = decoder.dequeueOutputBuffer(info, 0);
                        if (outIndex >= 0) {
                            decoder.releaseOutputBuffer(outIndex, true);
                        }
                    }
                } finally {
                    try {
                        decoder.stop();
                    } catch (Exception ignored) {
                    }
                    decoder.release();
                }
                return;
            } catch (SocketTimeoutException e) {
                if (shouldRetry(stage, attempt, maxSessionAttempts)) {
                    listener.onStatus("Retry stream (" + attempt + "/" + maxSessionAttempts + ") after timeout at " + stage);
                    sleepQuiet(500);
                    continue;
                }
                listener.onError("Stream timeout at " + stage + ": " + safeMessage(e), e);
                return;
            } catch (Throwable t) {
                if ((t instanceof EOFException || t instanceof IOException) && shouldRetry(stage, attempt, maxSessionAttempts)) {
                    listener.onStatus("Retry stream (" + attempt + "/" + maxSessionAttempts + ") after " + t.getClass().getSimpleName() + " at " + stage);
                    sleepQuiet(500);
                    continue;
                }
                listener.onError("Stream error at " + stage + " (" + t.getClass().getSimpleName() + "): " + safeMessage(t), t);
                return;
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private static boolean shouldRetry(String stage, int attempt, int maxAttempts) {
        if (attempt >= maxAttempts) {
            return false;
        }
        return "connect".equals(stage) || "read_codec_metadata".equals(stage);
    }

    private static void sleepQuiet(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private Socket connectWithRetry(String host, int port) throws Exception {
        Exception lastError = null;
        for (int i = 1; i <= 15 && running; i++) {
            Socket socket = new Socket();
            try {
                listener.onStatus("Connecting stream " + host + ":" + port + " (try " + i + "/15)");
                socket.connect(new InetSocketAddress(host, port), 4000);
                socket.setSoTimeout(STREAM_READ_TIMEOUT_MS);
                return socket;
            } catch (Exception e) {
                lastError = e;
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                Thread.sleep(300);
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IOException("Unable to connect stream");
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        String msg = t.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return "no details (" + t.getClass().getName() + ")";
        }
        return msg;
    }
}
