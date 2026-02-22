package com.example.scrcpy.proto;

public final class FrameHeader {
    private static final long CONFIG_FLAG = 1L << 63;
    private static final long KEY_FRAME_FLAG = 1L << 62;

    public final boolean config;
    public final boolean keyFrame;
    public final long ptsUs;
    public final int packetSize;

    public FrameHeader(boolean config, boolean keyFrame, long ptsUs, int packetSize) {
        this.config = config;
        this.keyFrame = keyFrame;
        this.ptsUs = ptsUs;
        this.packetSize = packetSize;
    }

    public static FrameHeader fromWire(long ptsAndFlags, int packetSize) {
        boolean config = (ptsAndFlags & CONFIG_FLAG) != 0;
        boolean keyFrame = (ptsAndFlags & KEY_FRAME_FLAG) != 0;
        long ptsUs = ptsAndFlags & ~(3L << 62);
        return new FrameHeader(config, keyFrame, ptsUs, packetSize);
    }
}
