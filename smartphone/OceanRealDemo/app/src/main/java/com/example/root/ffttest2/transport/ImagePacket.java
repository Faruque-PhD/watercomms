package com.example.root.ffttest2.transport;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * A data structure representing a single chunk of an image.
 */
public class ImagePacket implements Serializable {
    public static final int HEADER_SIZE = 9; // Magic(2) + Type(1) + Id(2) + Seq(2) + Total(2)
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
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        buffer.put(MAGIC_1);
        buffer.put(MAGIC_2);
        buffer.put(type);
        buffer.putShort((short) imageId);
        buffer.putShort((short) packetIndex);
        buffer.putShort((short) totalPackets);
        buffer.put(payload);
        // CRC will be handled by the streamer for now, or added here if needed
        return buffer.array();
    }

    /**
     * Deserializes a byte array back into an ImagePacket.
     */
    public static ImagePacket fromBytes(byte[] data) {
        if (data.length < HEADER_SIZE) return null;
        ByteBuffer buffer = ByteBuffer.wrap(data);
        if (buffer.get() != MAGIC_1 || buffer.get() != MAGIC_2) return null;

        ImagePacket packet = new ImagePacket();
        packet.type = buffer.get();
        packet.imageId = buffer.getShort() & 0xFFFF;
        packet.packetIndex = buffer.getShort() & 0xFFFF;
        packet.totalPackets = buffer.getShort() & 0xFFFF;
        
        int payloadLen = data.length - HEADER_SIZE;
        packet.payload = new byte[payloadLen];
        buffer.get(packet.payload);
        
        return packet;
    }
}
