package com.example.root.ffttest2.optical;

import android.graphics.ImageFormat;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Robust OpticalDetector with intense logging for debugging.
 */
public class OpticalDetector implements ImageAnalysis.Analyzer {
    private static final String TAG = "OpticalDetector";
    
    public interface OpticalListener {
        void onBitDetected(boolean bit);
        void onByteReceived(byte b);
        void onIntensityChanged(double intensity);
        void onStateChanged(String state, int progress);
    }

    private static final long BIT_DURATION_MS = 100;
    private static final double ON_THRESHOLD_RATIO = 1.08; // 8% above baseline - more sensitive

    private final OpticalListener listener;
    private double baselineIntensity = -1;
    private double alpha = 0.05; 
    private boolean isDecoding = false;

    private enum State { IDLE, SYNCING, RECEIVING }
    private State currentState = State.IDLE;
    
    private long startTime = 0;
    private int bitCount = 0;
    private int currentByte = 0;
    private final List<Long> preamblePulseTimes = new ArrayList<>();
    private long lastPulseTime = 0;

    public OpticalDetector(OpticalListener listener) {
        this.listener = listener;
    }

    public void setDecoding(boolean decoding) {
        this.isDecoding = decoding;
        Log.d(TAG, "Decoding set to: " + decoding);
        if (!decoding) reset();
    }

    public void reset() {
        Log.d(TAG, "Resetting state");
        currentState = State.IDLE;
        bitCount = 0;
        currentByte = 0;
        preamblePulseTimes.clear();
        baselineIntensity = -1;
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

        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);

        double avgIntensity = calculateAverageLuminance(data);
        if (listener != null) listener.onIntensityChanged(avgIntensity);

        processOpticalSignal(avgIntensity);
        image.close();
    }

    private synchronized void processOpticalSignal(double intensity) {
        long now = System.currentTimeMillis();

        if (baselineIntensity == -1) {
            baselineIntensity = intensity;
            Log.d(TAG, "Baseline initialized: " + baselineIntensity);
            return;
        }

        boolean isOn = intensity > (baselineIntensity * ON_THRESHOLD_RATIO);
        
        // Log intensity occasionally for debugging
        if (now % 500 < 50) {
            Log.d(TAG, String.format("Intensity: %.2f | Baseline: %.2f | IsOn: %b | State: %s", 
                intensity, baselineIntensity, isOn, currentState));
        }

        if (!isOn) {
            baselineIntensity = (1 - alpha) * baselineIntensity + alpha * intensity;
        }

        switch (currentState) {
            case IDLE:
                if (isOn) {
                    Log.d(TAG, "Sync pulse 1 detected!");
                    currentState = State.SYNCING;
                    preamblePulseTimes.clear();
                    preamblePulseTimes.add(now);
                    lastPulseTime = now;
                    if (listener != null) listener.onStateChanged("SYNCING", 1);
                }
                break;

            case SYNCING:
                if (isOn && (now - lastPulseTime > 150)) {
                    preamblePulseTimes.add(now);
                    lastPulseTime = now;
                    Log.d(TAG, "Sync pulse " + preamblePulseTimes.size() + " detected!");
                    if (listener != null) listener.onStateChanged("SYNCING", preamblePulseTimes.size());
                    
                    if (preamblePulseTimes.size() >= 3) {
                        Log.d(TAG, "Sync complete! Moving to RECEIVING");
                        currentState = State.RECEIVING;
                        startTime = now + 300; 
                        bitCount = 0;
                        currentByte = 0;
                        if (listener != null) listener.onStateChanged("RECEIVING", 0);
                    }
                }
                if (now - lastPulseTime > 3000) {
                    Log.d(TAG, "Sync timeout");
                    reset();
                }
                break;

            case RECEIVING:
                long elapsed = now - startTime;
                if (elapsed < 0) return;

                int targetBitIndex = (int) (elapsed / BIT_DURATION_MS);
                
                // Sample if we've reached a new bit window
                if (targetBitIndex > bitCount) {
                    while (bitCount < targetBitIndex) {
                        Log.d(TAG, "Sampling Bit " + bitCount + ": " + (isOn ? "1" : "0"));
                        sampleBit(isOn);
                    }
                }

                if (bitCount >= 1024 || (now - lastPulseTime > 8000)) {
                    Log.d(TAG, "Transmission ended or timeout");
                    reset();
                }
                if (isOn) lastPulseTime = now;
                break;
        }
    }

    private void sampleBit(boolean isOn) {
        currentByte = (currentByte << 1) | (isOn ? 1 : 0);
        bitCount++;
        if (listener != null) {
            listener.onBitDetected(isOn);
            listener.onStateChanged("RECEIVING", bitCount);
        }

        if (bitCount % 8 == 0) {
            if (listener != null) listener.onByteReceived((byte) (currentByte & 0xFF));
            currentByte = 0;
        }
    }

    private double calculateAverageLuminance(byte[] yData) {
        long total = 0;
        int step = Math.max(1, yData.length / 1024);
        for (int i = 0; i < yData.length; i += step) {
            total += (yData[i] & 0xFF);
        }
        return (double) total / (yData.length / (double)step);
    }
}
