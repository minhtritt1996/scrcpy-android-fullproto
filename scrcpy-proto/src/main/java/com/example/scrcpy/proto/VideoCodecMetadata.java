package com.example.scrcpy.proto;

public final class VideoCodecMetadata {
    public final int codecId;
    public final int width;
    public final int height;

    public VideoCodecMetadata(int codecId, int width, int height) {
        this.codecId = codecId;
        this.width = width;
        this.height = height;
    }
}
