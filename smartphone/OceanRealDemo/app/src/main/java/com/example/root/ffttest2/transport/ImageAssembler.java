package com.example.root.ffttest2.transport;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.SparseArray;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Reassembles ImagePackets back into a Bitmap at the receiver side.
 */
public class ImageAssembler {
    private SparseArray<ImagePacket> packetBuffer = new SparseArray<>();
    private int totalPackets = -1;
    private int currentImageId = -1;

    public interface AssemblyListener {
        void onPacketReceived(int current, int total);
        void onImageComplete(Bitmap bitmap);
        void onImageError(String error);
    }

    private AssemblyListener listener;

    public ImageAssembler(AssemblyListener listener) {
        this.listener = listener;
    }

    /**
     * Adds an incoming packet to the assembly buffer.
     */
    public synchronized void addPacket(ImagePacket packet) {
        if (packet == null) return;

        // Reset if we see a new image ID
        if (packet.imageId != currentImageId) {
            packetBuffer.clear();
            currentImageId = packet.imageId;
            totalPackets = packet.totalPackets;
        }

        // Store the packet
        packetBuffer.put(packet.packetIndex, packet);

        if (listener != null) {
            listener.onPacketReceived(packetBuffer.size(), totalPackets);
        }

        // Check if we have all pieces
        if (packetBuffer.size() == totalPackets) {
            assemble();
        }
    }

    /**
     * Reconstructs the image from all stored packets.
     */
    private void assemble() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            for (int i = 0; i < totalPackets; i++) {
                ImagePacket packet = packetBuffer.get(i);
                if (packet == null) {
                    if (listener != null) listener.onImageError("Missing packet: " + i);
                    return;
                }
                outputStream.write(packet.payload);
            }

            byte[] fullData = outputStream.toByteArray();
            Bitmap bitmap = BitmapFactory.decodeByteArray(fullData, 0, fullData.length);

            if (bitmap != null) {
                if (listener != null) listener.onImageComplete(bitmap);
            } else {
                if (listener != null) listener.onImageError("Failed to decode image data.");
            }

        } catch (IOException e) {
            if (listener != null) listener.onImageError("Assembly error: " + e.getMessage());
        } finally {
            try {
                outputStream.close();
            } catch (IOException e) {}
        }
    }

    public int getCompletionPercentage() {
        if (totalPackets <= 0) return 0;
        return (int) ((packetBuffer.size() / (double) totalPackets) * 100);
    }
}
