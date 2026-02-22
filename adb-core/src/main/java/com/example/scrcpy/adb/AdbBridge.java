package com.example.scrcpy.adb;

public interface AdbBridge {
    void connect(String host, int port) throws Exception;
    void push(String localPath, String remotePath) throws Exception;
    void forward(int localPort, String remoteSpec) throws Exception;
    void shell(String command) throws Exception;
}
