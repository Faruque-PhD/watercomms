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
        void onCalibrationComplete(double threshold);
    }

    private static final long BIT_DURATION_MS = 200; // 5 Hz
    private static final long PREAMBLE_MIN_MS = 600; // UFlash uses 800ms
    private static final long STOP_MIN_MS = 800;     // UFlash uses 900ms
    private static final int CALIBRATION_FRAMES = 60; // ~2 seconds at 30fps

    private final OpticalListener listener;
    private boolean isDecoding = false;

    // Adaptive Thresholding
    private final LinkedList<Double> intensityWindow = new LinkedList<>();
    private static final int WINDOW_SIZE = 150;
    private double currentThreshold = -1;
    private int calibrationCount = 0;

    // Decoding State
    private enum State { CALIBRATING, IDLE, RECEIVING }
    private State currentState = State.CALIBRATING;
    
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
        if (this.isDecoding && !decoding) {
            // Manual stop: try to flush what we have
            if (currentState == State.RECEIVING && !edges.isEmpty()) {
                Log.d(TAG, "Manual stop: flushing " + edges.size() + " edges");
                decodeEdges();
            }
        }
        this.isDecoding = decoding;
        if (!decoding) reset();
    }

    public void reset() {
        Log.d(TAG, "Resetting decoder");
        currentState = State.CALIBRATING;
        calibrationCount = 0;
        intensityWindow.clear();
        edges.clear();
        currentThreshold = -1;
        lastEdgeTime = 0;
        lastLevel = false;
        if (listener != null) listener.onStateChanged("CALIBRATING", 0);
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

        // Use frame timestamp for accuracy (convert ns to ms)
        long timestampMs = image.getImageInfo().getTimestamp() / 1000000;

        // Get Luminance (Y) plane
        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        ByteBuffer buffer = yPlane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int pixelStride = yPlane.getPixelStride();
        int rowStride = yPlane.getRowStride();

        double avgIntensity = calculateROIIntensity(buffer, width, height, pixelStride, rowStride);
        
        if (listener != null) listener.onIntensityChanged(avgIntensity);

        if (currentState == State.CALIBRATING) {
            calibrationCount++;
            intensityWindow.add(avgIntensity);
            if (intensityWindow.size() > WINDOW_SIZE) intensityWindow.removeFirst();
            
            if (calibrationCount >= CALIBRATION_FRAMES) {
                updateThreshold(avgIntensity); // Calculate initial threshold
                currentState = State.IDLE;
                lastEdgeTime = timestampMs;
                if (listener != null) {
                    listener.onCalibrationComplete(currentThreshold);
                    listener.onStateChanged("IDLE (CALIBRATED)", 0);
                }
            } else {
                if (listener != null) listener.onStateChanged("CALIBRATING", (int)((calibrationCount * 100.0) / CALIBRATION_FRAMES));
            }
        } else {
            updateThreshold(avgIntensity);
            if (currentThreshold != -1) {
                processSample(avgIntensity > currentThreshold, timestampMs);
            }
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

    private void processSample(boolean level, long nowMs) {
        if (level != lastLevel) {
            long duration = nowMs - lastEdgeTime;
            
            if (currentState == State.IDLE) {
                // Look for HIGH preamble
                if (!level && duration >= PREAMBLE_MIN_MS) {
                    Log.d(TAG, "Preamble detected! Duration: " + duration);
                    currentState = State.RECEIVING;
                    edges.clear();
                    // Level is now LOW after preamble HIGH
                    edges.add(new Edge(nowMs, false));
                    if (listener != null) listener.onStateChanged("RECEIVING", 0);
                }
            } else if (currentState == State.RECEIVING) {
                edges.add(new Edge(nowMs, level));
                if (listener != null) listener.onStateChanged("RECEIVING", edges.size());
            }

            lastLevel = level;
            lastEdgeTime = nowMs;
        } else {
            // No change. Check for STOP signal or timeouts.
            long duration = nowMs - lastEdgeTime;
            if (currentState == State.RECEIVING && !level && duration >= STOP_MIN_MS) {
                Log.d(TAG, "STOP signal detected! Duration: " + duration);
                decodeEdges();
                // Instead of reset(), go to IDLE to be ready for next packet immediately
                currentState = State.IDLE;
                edges.clear();
                if (listener != null) listener.onStateChanged("IDLE", 0);
            } else if (currentState == State.RECEIVING && duration > 10000) {
                Log.d(TAG, "Receiving timeout");
                reset();
            }
        }
    }

    private void decodeEdges() {
        if (edges.isEmpty()) return;

        StringBuilder bits = new StringBuilder();
        
        // Start from the falling edge of the preamble
        long tRef = edges.get(0).timestamp;
        boolean currentVal = edges.get(0).level; // false (LOW)
        
        for (int i = 1; i < edges.size(); i++) {
            long tNext = edges.get(i).timestamp;
            long duration = tNext - tRef;
            
            // Clock Recovery / Drift Adjustment:
            // Calculate bitCount using the expected BIT_DURATION_MS
            int bitCount = (int) Math.round((double) duration / BIT_DURATION_MS);
            
            for (int b = 0; b < bitCount; b++) {
                bits.append(currentVal ? "1" : "0");
                if (listener != null) listener.onBitDetected(currentVal);
            }
            
            tRef = tNext;
            currentVal = edges.get(i).level;
        }

        // Handle the last stretch between the last transition and the STOP signal detection
        // We use the current system time or the time when STOP was detected.
        // Actually, processSample calls decodeEdges right when duration >= STOP_MIN_MS.
        // So the last stretch is duration - STOP_MIN_MS.
        long now = System.currentTimeMillis(); // Approximation if we don't have accurate 'now'
        // Better: use the last duration that triggered the STOP signal.
        long durationSinceLastEdge = 800; // Minimum for STOP
        // Let's assume the last stretch had some data bits before the 800ms of LOW.
        // But 4B5B ensures we have transitions. 
        // If we stayed LOW for > 800ms, everything after 3-4 bits must be STOP.
        
        int extraBits = (int) Math.round((double) durationSinceLastEdge / BIT_DURATION_MS);
        // We only append up to 3 bits if it's LOW (max consecutive zeros in 4B5B)
        if (!currentVal) extraBits = Math.min(extraBits, 3);
        
        for (int b = 0; b < extraBits; b++) {
            bits.append(currentVal ? "1" : "0");
            if (listener != null) listener.onBitDetected(currentVal);
        }

        Log.d(TAG, "Decoded bitstream (" + bits.length() + " bits): " + bits.toString());
        processBitstream(bits.toString());
    }

    private static class DecodeResult {
        int validCount;
        List<Byte> bytes;
        int shift;
        DecodeResult(int vc, List<Byte> b, int s) { this.validCount = vc; this.bytes = b; this.shift = s; }
    }

    private void processBitstream(String bitstream) {
        String inverted = bitstream.replace('0', 'x').replace('1', '0').replace('x', '1');
        
        DecodeResult normal = getBestShiftResult(bitstream);
        DecodeResult inv = getBestShiftResult(inverted);
        
        DecodeResult best = (normal.validCount >= inv.validCount) ? normal : inv;
        String label = (normal.validCount >= inv.validCount) ? "Normal" : "Inverted";

        if (best.validCount > 3) {
            Log.d(TAG, label + " best shift: " + best.shift + " with " + best.validCount + " valid symbols");
            StringBuilder hex = new StringBuilder();
            for (byte b : best.bytes) {
                hex.append(String.format("%02X ", b));
                if (listener != null) listener.onByteReceived(b);
            }
            Log.d(TAG, label + " Hex: " + hex.toString());
        } else {
            Log.d(TAG, "No valid data found in bitstream.");
        }
    }

    private DecodeResult getBestShiftResult(String stream) {
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
        return new DecodeResult(maxValid, bestDecoded, bestShift);
    }
}
