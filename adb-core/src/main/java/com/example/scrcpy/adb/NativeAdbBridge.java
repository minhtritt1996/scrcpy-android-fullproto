package com.example.scrcpy.adb;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class NativeAdbBridge implements AdbBridge {
    private static final String ADB_SERVER_PORT = "5137";

    public interface ShellResultListener {
        void onCompleted(String output);
        void onError(Throwable throwable);
    }

    private final Context context;
    private String currentSerial;

    public NativeAdbBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    public void pair(String host, int pairPort, String pairingCode) throws Exception {
        if (pairingCode == null || pairingCode.trim().isEmpty()) {
            throw new IOException("Pairing code is empty");
        }
        runAdb("start-server");
        runAdb("pair", host + ":" + pairPort, pairingCode.trim());
    }

    @Override
    public void connect(String host, int port) throws Exception {
        currentSerial = host + ":" + port;
        runAdb("start-server");
        try {
            runAdb("disconnect", currentSerial);
        } catch (Exception ignored) {
        }
        runAdb("connect", currentSerial);

        // Wait a bit for the transport to switch from offline -> device
        String lastState = "";
        for (int i = 0; i < 12; i++) {
            try {
                lastState = runDeviceAdb("get-state").trim();
                if ("device".equals(lastState)) {
                    return;
                }
            } catch (Exception e) {
                lastState = e.getMessage() == null ? "" : e.getMessage();
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
        }

        if (lastState.contains("device offline") || "offline".equals(lastState)) {
            throw new IOException(
                    "Device is offline. On target phone: open Developer options -> Wireless debugging, " +
                            "turn it OFF then ON, reconnect, and accept ADB authorization prompt."
            );
        }
        if (lastState.contains("not found")) {
            throw new IOException(
                    "Device not found. Use Connect port from Wireless debugging, or run Pair first " +
                            "(IP + Pairing port + Pairing code)."
            );
        }
        throw new IOException("ADB device state is not ready: " + lastState);
    }

    @Override
    public void push(String localPath, String remotePath) throws Exception {
        runDeviceAdb("wait-for-device");
        runDeviceAdb("shell", "mkdir -p /data/local/tmp");
        runDeviceAdb("push", localPath, remotePath);
    }

    @Override
    public void forward(int localPort, String remoteSpec) throws Exception {
        try {
            runDeviceAdb("forward", "--remove", "tcp:" + localPort);
        } catch (Exception ignored) {
            // ignore missing previous forward
        }
        runDeviceAdb("forward", "tcp:" + localPort, remoteSpec);
    }

    @Override
    public void shell(String command) throws Exception {
        runDeviceAdb("shell", command);
    }

    public void shellAsync(String command, ShellResultListener listener) {
        Thread t = new Thread(() -> {
            try {
                String out = runDeviceAdb("shell", command);
                if (listener != null) {
                    listener.onCompleted(out);
                }
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError(e);
                }
            }
        }, "adb-shell");
        t.start();
    }

    private String runDeviceAdb(String... args) throws Exception {
        if (currentSerial == null || currentSerial.isEmpty()) {
            throw new IOException("Device serial is not initialized. Call connect() first.");
        }
        String[] withSerial = new String[args.length + 2];
        withSerial[0] = "-s";
        withSerial[1] = currentSerial;
        System.arraycopy(args, 0, withSerial, 2, args.length);
        return runAdb(withSerial);
    }

    private String runAdb(String... args) throws Exception {
        String adbPath = context.getApplicationInfo().nativeLibraryDir + "/libadb.so";
        String[] command = new String[args.length + 1];
        command[0] = adbPath;
        System.arraycopy(args, 0, command, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(context.getFilesDir());
        Map<String, String> env = pb.environment();
        env.put("HOME", context.getFilesDir().getAbsolutePath());
        env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
        env.put("ANDROID_ADB_SERVER_PORT", ADB_SERVER_PORT);

        Process process = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread outThread = drain(process.getInputStream(), out);
        Thread errThread = drain(process.getErrorStream(), err);

        int code = process.waitFor();
        outThread.join();
        errThread.join();

        String stdout = out.toString(StandardCharsets.UTF_8.name()).trim();
        String stderr = err.toString(StandardCharsets.UTF_8.name()).trim();

        if (code != 0) {
            String details = ("stdout: " + stdout + "\nstderr: " + stderr).trim();
            throw new IOException("adb failed (" + code + ") " + String.join(" ", command) + "\n" + details);
        }
        return stdout;
    }

    private static Thread drain(InputStream inputStream, ByteArrayOutputStream outputStream) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[4096];
            int n;
            try {
                while ((n = inputStream.read(buf)) != -1) {
                    outputStream.write(buf, 0, n);
                }
            } catch (IOException ignored) {
            }
        });
        t.start();
        return t;
    }
}
