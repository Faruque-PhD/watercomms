package com.example.root.ffttest2.optical;

import android.graphics.ImageFormat;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Robust OpticalDetector using Adaptive Thresholding and Edge-List Decoding.
 * Inspired by UFlash.
 */
public class OpticalDetector implements ImageAnalysis.Analyzer {
    private static final String TAG = "OpticalDetector";
    
    public interface OpticalListener {
        void onBitDetected(boolean bit);
        void onByteReceived(byte b);
        void onIntensityChanged(double intensity);
        void onStateChanged(String state, int progress);
    }

    private static final long BIT_DURATION_MS = 200; // 5 Hz
    private static final long PREAMBLE_MIN_MS = 600; // UFlash uses 800ms
    private static final long STOP_MIN_MS = 800;     // UFlash uses 900ms

    private final OpticalListener listener;
    private boolean isDecoding = false;

    // Adaptive Thresholding
    private final LinkedList<Double> intensityWindow = new LinkedList<>();
    private static final int WINDOW_SIZE = 150;
    private double currentThreshold = -1;

    // Decoding State
    private enum State { IDLE, RECEIVING }
    private State currentState = State.IDLE;
    
    private boolean lastLevel = false;
    private long lastEdgeTime = 0;
    private final List<Edge> edges = new ArrayList<>();

    private static class Edge {
        long timestamp;
        boolean level; // The level AFTER this edge
        Edge(long t, boolean l) { this.timestamp = t; this.level = l; }
    }

    public OpticalDetector(OpticalListener listener) {
        this.listener = listener;
    }

    public void setDecoding(boolean decoding) {
        this.isDecoding = decoding;
        if (!decoding) reset();
    }

    public void reset() {
        Log.d(TAG, "Resetting decoder");
        currentState = State.IDLE;
        intensityWindow.clear();
        edges.clear();
        currentThreshold = -1;
        if (listener != null) listener.onStateChanged("IDLE", 0);
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        if (!isDecoding) {
            image.close();
            return;
        }

        if (image.getFormat() != ImageFormat.YUV_420_888) {
            image.close();
            return;
        }

        // Get Luminance (Y) plane
        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        ByteBuffer buffer = yPlane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int pixelStride = yPlane.getPixelStride();
        int rowStride = yPlane.getRowStride();

        double avgIntensity = calculateROIIntensity(buffer, width, height, pixelStride, rowStride);
        
        if (listener != null) listener.onIntensityChanged(avgIntensity);

        updateThreshold(avgIntensity);
        if (currentThreshold != -1) {
            processSample(avgIntensity > currentThreshold);
        }

        image.close();
    }

    private double calculateROIIntensity(ByteBuffer buffer, int width, int height, int pixelStride, int rowStride) {
        // Center 40% ROI
        int roiW = (int) (width * 0.4);
        int roiH = (int) (height * 0.4);
        int startX = (width - roiW) / 2;
        int startY = (height - roiH) / 2;

        long total = 0;
        int count = 0;
        byte[] rowData = new byte[roiW * pixelStride];

        for (int y = startY; y < startY + roiH; y += 2) { // Subsample rows for speed
            buffer.position(y * rowStride + startX * pixelStride);
            buffer.get(rowData);
            for (int x = 0; x < rowData.length; x += pixelStride * 2) {
                total += (rowData[x] & 0xFF);
                count++;
            }
        }
        return (double) total / count;
    }

    private void updateThreshold(double intensity) {
        intensityWindow.add(intensity);
        if (intensityWindow.size() > WINDOW_SIZE) {
            intensityWindow.removeFirst();
        }

        if (intensityWindow.size() >= WINDOW_SIZE / 2) {
            List<Double> sorted = new ArrayList<>(intensityWindow);
            Collections.sort(sorted);
            double min = sorted.get(0);
            double max = sorted.get(sorted.size() - 1);
            
            if (max - min > 12) { // Minimum contrast required
                currentThreshold = (min + max) / 2.0;
            }
        }
    }

    private void processSample(boolean level) {
        long now = System.currentTimeMillis();

        if (level != lastLevel) {
            long duration = now - lastEdgeTime;
            
            if (currentState == State.IDLE) {
                // Look for HIGH preamble
                if (!level && duration >= PREAMBLE_MIN_MS) {
                    Log.d(TAG, "Preamble detected! Duration: " + duration);
                    currentState = State.RECEIVING;
                    edges.clear();
                    // Level is now LOW after preamble HIGH
                    edges.add(new Edge(now, false));
                    if (listener != null) listener.onStateChanged("RECEIVING", 0);
                }
            } else if (currentState == State.RECEIVING) {
                edges.add(new Edge(now, level));
                if (listener != null) listener.onStateChanged("RECEIVING", edges.size());
            }

            lastLevel = level;
            lastEdgeTime = now;
        } else {
            // No change. Check for STOP signal or timeouts.
            long duration = now - lastEdgeTime;
            if (currentState == State.RECEIVING && !level && duration >= STOP_MIN_MS) {
                Log.d(TAG, "STOP signal detected! Duration: " + duration);
                decodeEdges();
                reset();
            } else if (currentState == State.RECEIVING && duration > 10000) {
                Log.d(TAG, "Receiving timeout");
                reset();
            }
        }
    }

    private void decodeEdges() {
        if (edges.isEmpty()) return;

        StringBuilder bits = new StringBuilder();
        long lastT = lastEdgeTime; // The time of the STOP edge (transition to LOW)
        
        // We need to work backwards or forwards. Let's work forwards from the first edge after preamble.
        // The first edge in the list is the transition from HIGH (preamble) to the first bit(s).
        
        long tRef = edges.get(0).timestamp;
        boolean currentVal = edges.get(0).level; // Level after the first data-carrying edge
        
        for (int i = 1; i < edges.size(); i++) {
            long tNext = edges.get(i).timestamp;
            long duration = tNext - tRef;
            int bitCount = (int) Math.round((double) duration / BIT_DURATION_MS);
            
            for (int b = 0; b < bitCount; b++) {
                bits.append(currentVal ? "1" : "0");
                if (listener != null) listener.onBitDetected(currentVal);
            }
            
            tRef = tNext;
            currentVal = edges.get(i).level;
        }

        // Handle the last stretch before the STOP edge
        long duration = lastEdgeTime - tRef;
        int bitCount = (int) Math.round((double) duration / BIT_DURATION_MS);
        for (int b = 0; b < bitCount; b++) {
            bits.append(currentVal ? "1" : "0");
            if (listener != null) listener.onBitDetected(currentVal);
        }

        Log.d(TAG, "Decoded bitstream: " + bits.toString());
        processBitstream(bits.toString());
    }

    private void processBitstream(String bitstream) {
        // Try both normal and inverted bitstreams
        String inverted = bitstream.replace('0', 'x').replace('1', '0').replace('x', '1');
        
        decodeWithBestShift(bitstream, "Normal");
        decodeWithBestShift(inverted, "Inverted");
    }

    private void decodeWithBestShift(String stream, String label) {
        int bestShift = 0;
        int maxValid = -1;
        List<Byte> bestDecoded = new ArrayList<>();

        for (int shift = 0; shift < 10; shift++) {
            List<Byte> currentDecoded = new ArrayList<>();
            int validCount = 0;
            for (int i = shift; i + 10 <= stream.length(); i += 10) {
                try {
                    String symbolStr = stream.substring(i, i + 10);
                    int symbol = Integer.parseInt(symbolStr, 2);
                    int decoded = FourB5B.decode(symbol);
                    if (decoded != -1) {
                        validCount++;
                        currentDecoded.add((byte) decoded);
                    }
                } catch (Exception e) {}
            }
            if (validCount > maxValid) {
                maxValid = validCount;
                bestShift = shift;
                bestDecoded = currentDecoded;
            }
        }

        if (maxValid > 0) {
            Log.d(TAG, label + " best shift: " + bestShift + " with " + maxValid + " valid symbols");
            for (byte b : bestDecoded) {
                if (listener != null) listener.onByteReceived(b);
            }
        }
    }
}
