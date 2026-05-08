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
        void onPartialImageReconstructed(Bitmap bitmap);
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
    public SparseArray<ImagePacket> getPacketBuffer() {
        return packetBuffer;
    }

    public synchronized void addPacket(ImagePacket packet) {
        if (packet == null) return;
        android.util.Log.d("ImageAssembler", "Adding packet " + packet.packetIndex + "/" + packet.totalPackets + " | ID: " + packet.imageId);

        // More robust ID handling: Only reset if we see multiple packets with a new ID
        // or if the new ID is significantly different and we have very few packets of current ID.
        if (packet.imageId != currentImageId) {
            if (packetBuffer.size() < 2 || packet.packetIndex == 0) {
                android.util.Log.i("ImageAssembler", "New image detected. Resetting buffer. Old ID: " + currentImageId + " New ID: " + packet.imageId);
                packetBuffer.clear();
                currentImageId = packet.imageId;
                totalPackets = packet.totalPackets;
            } else {
                // Potential noise in ID, ignore this packet's ID change but keep it if indices match?
                // For now, let's just stick to the current image if we're deep into it.
                if (packet.totalPackets != totalPackets) return; 
            }
        }

        // Store the packet
        packetBuffer.put(packet.packetIndex, packet);
        android.util.Log.d("ImageAssembler", "Buffer size: " + packetBuffer.size() + "/" + totalPackets);

        if (listener != null) {
            listener.onPacketReceived(packetBuffer.size(), totalPackets);
        }

        // --- ENHANCED: Resilient Partial Reconstruction ---
        // We MUST have the first packet because it contains the image header (Magic, etc.)
        if (packetBuffer.get(0) == null) {
            android.util.Log.w("ImageAssembler", "Packet 0 missing, skipping partial reconstruction");
            return;
        }

        ByteArrayOutputStream partialStream = new ByteArrayOutputStream();
        int payloadSize = packet.payload.length;
        
        int maxIndex = 0;
        for (int i = 0; i < packetBuffer.size(); i++) {
            maxIndex = Math.max(maxIndex, packetBuffer.keyAt(i));
        }
        
        for (int i = 0; i <= maxIndex; i++) {
            ImagePacket p = packetBuffer.get(i);
            if (p != null) {
                try {
                    partialStream.write(p.payload);
                } catch (IOException e) {}
            } else {
                // GAP FILLING: Write zeros for missing intermediate packets
                partialStream.write(new byte[payloadSize], 0, payloadSize);
            }
        }

        if (partialStream.size() > 0) {
            byte[] partialData = partialStream.toByteArray();
            try {
                // For partial reconstruction, we use the simpler decodeByteArray
                Bitmap partialBitmap = BitmapFactory.decodeByteArray(partialData, 0, partialData.length);
                if (partialBitmap != null) {
                    android.util.Log.d("ImageAssembler", "Partial image reconstructed! Size: " + partialData.length);
                    if (listener != null) {
                        listener.onPartialImageReconstructed(partialBitmap);
                    }
                }
            } catch (Exception e) {
                // Decode failed, expected for very fragmented data
                android.util.Log.v("ImageAssembler", "Partial decode failed: " + e.getMessage());
            }
        }
        // ------------------------------------

        // Check if we have all pieces
        if (packetBuffer.size() == totalPackets) {
            android.util.Log.i("ImageAssembler", "All packets received! Assembling full image.");
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
