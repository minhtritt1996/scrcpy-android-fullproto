package com.example.scrcpy.adb;

public final class StubAdbBridge implements AdbBridge {
    @Override
    public void connect(String host, int port) {
        throw new UnsupportedOperationException("ADB core will be implemented in M1.1");
    }

    @Override
    public void push(String localPath, String remotePath) {
        throw new UnsupportedOperationException("ADB core will be implemented in M1.1");
    }

    @Override
    public void forward(int localPort, String remoteSpec) {
        throw new UnsupportedOperationException("ADB core will be implemented in M1.1");
    }

    @Override
    public void shell(String command) {
        throw new UnsupportedOperationException("ADB core will be implemented in M1.1");
    }
}
