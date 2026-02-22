package com.example.scrcpyandroidfullproto;

import java.nio.ByteBuffer;

public final class AvcConfigParser {
    private AvcConfigParser() {
    }

    public static ByteBuffer[] extractCsd(byte[] configPacket) {
        int spsStart = -1;
        int ppsStart = -1;
        int spsEnd = -1;

        int i = 0;
        while (i <= configPacket.length - 4) {
            int codeLen = startCodeLength(configPacket, i);
            if (codeLen == 0) {
                i++;
                continue;
            }
            int nalStart = i + codeLen;
            if (nalStart >= configPacket.length) {
                break;
            }
            int nalType = configPacket[nalStart] & 0x1F;
            if (nalType == 7 && spsStart < 0) {
                spsStart = i;
            } else if (nalType == 8 && ppsStart < 0) {
                if (spsStart >= 0 && spsEnd < 0) {
                    spsEnd = i;
                }
                ppsStart = i;
            }
            i = nalStart;
        }

        if (spsStart >= 0 && spsEnd < 0 && ppsStart > spsStart) {
            spsEnd = ppsStart;
        }

        if (spsStart < 0 || ppsStart < 0 || spsEnd <= spsStart) {
            return null;
        }

        byte[] sps = new byte[spsEnd - spsStart];
        byte[] pps = new byte[configPacket.length - ppsStart];
        System.arraycopy(configPacket, spsStart, sps, 0, sps.length);
        System.arraycopy(configPacket, ppsStart, pps, 0, pps.length);
        return new ByteBuffer[]{ByteBuffer.wrap(sps), ByteBuffer.wrap(pps)};
    }

    private static int startCodeLength(byte[] data, int offset) {
        if (offset + 3 < data.length && data[offset] == 0 && data[offset + 1] == 0) {
            if (data[offset + 2] == 1) {
                return 3;
            }
            if (offset + 4 < data.length && data[offset + 2] == 0 && data[offset + 3] == 1) {
                return 4;
            }
        }
        return 0;
    }
}
