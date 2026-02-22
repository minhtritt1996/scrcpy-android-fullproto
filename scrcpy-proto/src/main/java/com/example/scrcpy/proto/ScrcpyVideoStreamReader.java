package com.example.scrcpy.proto;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

public final class ScrcpyVideoStreamReader {
    private static final int MAX_PACKET_SIZE = 4 * 1024 * 1024;

    private final DataInputStream input;

    public ScrcpyVideoStreamReader(InputStream input) {
        this.input = new DataInputStream(input);
    }

    public VideoCodecMetadata readCodecMetadata() throws IOException {
        int codecId = input.readInt();
        int width = input.readInt();
        int height = input.readInt();
        return new VideoCodecMetadata(codecId, width, height);
    }

    public Packet readPacket() throws IOException {
        long ptsAndFlags = input.readLong();
        int packetSize = input.readInt();
        if (packetSize <= 0 || packetSize > MAX_PACKET_SIZE) {
            throw new IOException("Invalid packet size: " + packetSize);
        }
        byte[] data = new byte[packetSize];
        input.readFully(data);
        return new Packet(FrameHeader.fromWire(ptsAndFlags, packetSize), data);
    }

    public static final class Packet {
        public final FrameHeader header;
        public final byte[] data;

        public Packet(FrameHeader header, byte[] data) {
            this.header = header;
            this.data = data;
        }
    }
}
