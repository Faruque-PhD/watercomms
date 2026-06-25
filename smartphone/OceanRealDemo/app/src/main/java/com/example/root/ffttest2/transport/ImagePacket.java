package com.example.root.ffttest2.transport;

import android.util.Log;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * A data structure representing a single chunk of an image.
 */
public class ImagePacket implements Serializable {
    public static final int HEADER_SIZE = 9; // Magic(2) + Type(1) + Id(2) + Seq(2) + Total(2)
    public static final int CRC_SIZE = 2; // CRC16 appended after payload
    public static final byte MAGIC_1 = 0x4F; // 'O'
    public static final byte MAGIC_2 = 0x52; // 'R' (Ocean Real)

    public byte type; // 0: Data, 1: ACK, 2: NACK
    public int imageId;
    public int packetIndex;
    public int totalPackets;
    public byte[] payload;
    public int crc;

    public ImagePacket() {}

    public ImagePacket(byte type, int imageId, int packetIndex, int totalPackets, byte[] payload) {
        this.type = type;
        this.imageId = imageId;
        this.packetIndex = packetIndex;
        this.totalPackets = totalPackets;
        this.payload = payload;
    }

    /**
     * Serializes the packet into a byte array for transmission.
     */
    public byte[] toBytes() {
        // Allocate space for header + payload + CRC
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + payload.length + CRC_SIZE);
        buffer.put(MAGIC_1);
        buffer.put(MAGIC_2);
        buffer.put(type);
        buffer.putShort((short) imageId);
        buffer.putShort((short) packetIndex);
        buffer.putShort((short) totalPackets);
        buffer.put(payload);

        // Compute CRC16 over everything after the magic bytes (type..payload)
        byte[] tmp = buffer.array();
        int crc = computeCrc16(tmp, 2, HEADER_SIZE + payload.length - 1); // start index 2, end index inclusive
        // Append CRC in big-endian order
        buffer.put((byte) ((crc >> 8) & 0xFF));
        buffer.put((byte) (crc & 0xFF));

        byte[] out = buffer.array();
        // Diagnostic dump of serialized packet (sender side). Non-fatal if it fails.
        try {
            java.io.File dir = new java.io.File("/storage/emulated/0/Android/data/com.example.root.ffttest2/files/diagnostics");
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, "sent_img_" + imageId + "_pkt_" + packetIndex + "_" + System.currentTimeMillis() + ".bin");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(out);
            fos.close();
        } catch (Exception e) {
            Log.d("ImagePacket", "Diag dump failed: " + e.getMessage());
        }

        return out;
    }

    /**
     * Deserializes a byte array back into an ImagePacket.
     */
    public static ImagePacket fromBytes(byte[] data) {
        if (data.length < HEADER_SIZE + CRC_SIZE) return null;
        ByteBuffer buffer = ByteBuffer.wrap(data);

        byte m1 = buffer.get();
        byte m2 = buffer.get();

        // Robust check: Allow some bit errors due to noisy channels, but don't reject immediately.
        int diff1 = countSetBits((byte)(m1 ^ MAGIC_1));
        int diff2 = countSetBits((byte)(m2 ^ MAGIC_2));
        int totalDiff = diff1 + diff2;
        boolean headerCorrupted = totalDiff > 3; // mark if header looks corrupted

        ImagePacket packet = new ImagePacket();
        packet.type = buffer.get();
        packet.imageId = buffer.getShort() & 0xFFFF;
        packet.packetIndex = buffer.getShort() & 0xFFFF;
        packet.totalPackets = buffer.getShort() & 0xFFFF;

        int payloadLen = data.length - HEADER_SIZE - CRC_SIZE;
        if (payloadLen < 0) return null;
        packet.payload = new byte[payloadLen];
        buffer.get(packet.payload);

        // Read CRC appended (big-endian)
        int crcHigh = buffer.get() & 0xFF;
        int crcLow = buffer.get() & 0xFF;
        int receivedCrc = (crcHigh << 8) | crcLow;

        // Compute CRC16 over type..payload (i.e., bytes from index 2 up to HEADER_SIZE+payloadLen-1)
        int computedCrc = computeCrc16(data, 2, HEADER_SIZE + payloadLen - 1);
        if (computedCrc != receivedCrc) {
            // CRC mismatch -> corrupted packet
            Log.d("ImagePacket", "CRC mismatch: recv=0x" + Integer.toHexString(receivedCrc) + " comp=0x" + Integer.toHexString(computedCrc) + " pktIndex=" + packet.packetIndex + " imgId=" + packet.imageId + " diff1=" + diff1 + " diff2=" + diff2);
            return null;
        }

        if (headerCorrupted) {
            // CRC matched even though header bits were off — accept but log warning
            Log.w("ImagePacket", "Header corrupted but CRC OK: diff1=" + diff1 + " diff2=" + diff2 + " pktIndex=" + packet.packetIndex + " imgId=" + packet.imageId);
        }

        return packet;
    }

    /**
     * Lenient parser: verifies CRC and parses fields even if magic header appears corrupted.
     * Use only as a fallback when standard fromBytes() fails.
     */
    public static ImagePacket fromBytesLenient(byte[] data) {
        if (data.length < HEADER_SIZE + CRC_SIZE) return null;
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            // Skip magic bytes (we'll accept corrupted magic here)
            buffer.get();
            buffer.get();
            ImagePacket packet = new ImagePacket();
            packet.type = buffer.get();
            packet.imageId = buffer.getShort() & 0xFFFF;
            packet.packetIndex = buffer.getShort() & 0xFFFF;
            packet.totalPackets = buffer.getShort() & 0xFFFF;

            int payloadLen = data.length - HEADER_SIZE - CRC_SIZE;
            if (payloadLen < 0) return null;
            packet.payload = new byte[payloadLen];
            buffer.get(packet.payload);

            int crcHigh = buffer.get() & 0xFF;
            int crcLow = buffer.get() & 0xFF;
            int receivedCrc = (crcHigh << 8) | crcLow;
            int computedCrc = computeCrc16(data, 2, HEADER_SIZE + payloadLen - 1);
            if (computedCrc != receivedCrc) {
                Log.d("ImagePacket", "Lenient CRC mismatch: recv=0x" + Integer.toHexString(receivedCrc) + " comp=0x" + Integer.toHexString(computedCrc) + " pktIndex=" + packet.packetIndex + " imgId=" + packet.imageId);
                return null;
            }
            Log.w("ImagePacket", "Lenient acceptance: CRC OK despite corrupted header. pktIndex=" + packet.packetIndex + " imgId=" + packet.imageId);
            return packet;
        } catch (Exception e) {
            return null;
        }
    }

    private static int countSetBits(byte n) {
        int count = 0;
        int val = n & 0xFF;
        while (val > 0) {
            val &= (val - 1);
            count++;
        }
        return count;
    }

    // CRC16-CCITT (poly 0x1021) implementation
    private static int computeCrc16(byte[] data, int startIdx, int endIdxInclusive) {
        int crc = 0xFFFF; // initial value
        for (int i = startIdx; i <= endIdxInclusive; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
                crc &= 0xFFFF;
            }
        }
        return crc & 0xFFFF;
    }
}