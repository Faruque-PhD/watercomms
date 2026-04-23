package com.example.root.ffttest2.transport;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a Bitmap into ImagePackets for transmission.
 */
public class ImagePacketizer {
    public static final int DEFAULT_PAYLOAD_SIZE = 128; // Standard for acoustic channel
    public static final int JPEG_QUALITY = 50; // Balance between size and quality
    public static final int RESIZE_LIMIT = 256; // Max dimension for optical/acoustic image transfer

    /**
     * Compresses the bitmap and splits it into packets.
     */
    public static List<ImagePacket> packetize(Bitmap bitmap, int imageId, int payloadSize) {
        // Step 0: Resize for transmission efficiency
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width > RESIZE_LIMIT || height > RESIZE_LIMIT) {
            float scale = Math.min((float) RESIZE_LIMIT / width, (float) RESIZE_LIMIT / height);
            bitmap = Bitmap.createScaledBitmap(bitmap, Math.round(width * scale), Math.round(height * scale), true);
        }

        // Step 1: Compress to WebP (lossy) if available, otherwise JPEG
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, JPEG_QUALITY, stream);
        } else {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream);
        }
        byte[] imageData = stream.toByteArray();

        // Step 2: Fragment the data
        List<ImagePacket> packets = new ArrayList<>();
        int totalPackets = (int) Math.ceil((double) imageData.length / payloadSize);

        for (int i = 0; i < totalPackets; i++) {
            int start = i * payloadSize;
            int end = Math.min(start + payloadSize, imageData.length);
            byte[] chunk = new byte[end - start];
            System.arraycopy(imageData, start, chunk, 0, end - start);

            ImagePacket packet = new ImagePacket(
                (byte) 0, // Data type
                imageId,
                i,
                totalPackets,
                chunk
            );
            packets.add(packet);
        }

        return packets;
    }

    /**
     * Convenience method with default payload size.
     */
    public static List<ImagePacket> packetize(Bitmap bitmap, int imageId) {
        return packetize(bitmap, imageId, DEFAULT_PAYLOAD_SIZE);
    }
}
