package com.example.root.ffttest2.optical;

import java.util.HashMap;
import java.util.Map;

/**
 * 4B5B Encoding/Decoding for optical communication.
 * Guarantees no more than 3 consecutive zeros, aiding in clock recovery.
 */
public class FourB5B {
    private static final Map<Integer, Integer> ENCODE_TABLE = new HashMap<>();
    private static final Map<Integer, Integer> DECODE_TABLE = new HashMap<>();

    static {
        // 4-bit to 5-bit lookup table (100BASE-TX standard)
        int[][] table = {
            {0x0, 0x1E}, {0x1, 0x09}, {0x2, 0x14}, {0x3, 0x15},
            {0x4, 0x0A}, {0x5, 0x0B}, {0x6, 0x0E}, {0x7, 0x0F},
            {0x8, 0x12}, {0x9, 0x13}, {0xA, 0x16}, {0xB, 0x17},
            {0xC, 0x1A}, {0xD, 0x1B}, {0xE, 0x1C}, {0xF, 0x1D}
        };
        for (int[] pair : table) {
            ENCODE_TABLE.put(pair[0], pair[1]);
            DECODE_TABLE.put(pair[1], pair[0]);
        }
    }

    /**
     * Encodes a byte into 10 bits (two 5-bit symbols).
     */
    public static int encode(byte b) {
        int high = (b >> 4) & 0x0F;
        int low = b & 0x0F;
        return (ENCODE_TABLE.get(high) << 5) | ENCODE_TABLE.get(low);
    }

    /**
     * Decodes a 10-bit value into a byte.
     * Returns -1 if decoding fails.
     */
    public static int decode(int tenBits) {
        Integer high = DECODE_TABLE.get((tenBits >> 5) & 0x1F);
        Integer low = DECODE_TABLE.get(tenBits & 0x1F);
        if (high == null || low == null) return -1;
        return (high << 4) | low;
    }
}
