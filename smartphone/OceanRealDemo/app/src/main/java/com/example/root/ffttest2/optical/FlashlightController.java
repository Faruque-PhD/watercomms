package com.example.root.ffttest2.optical;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * Controls the phone's flashlight for OOK (On-Off Keying) transmission.
 */
public class FlashlightController {
    private static final long BIT_DURATION_MS = 100; // Duration for one bit (10Hz)
    
    private Context context;
    private CameraManager cameraManager;
    private String cameraId;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isTransmitting = false;

    public FlashlightController(Context context) {
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            this.cameraId = cameraManager.getCameraIdList()[0]; // Usually the back camera
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Transmits a byte array using the flashlight.
     */
    public void transmit(byte[] data, Runnable onComplete) {
        if (isTransmitting) return;
        isTransmitting = true;

        new Thread(() -> {
            // Start Preamble (3 rapid flashes to wake up receiver)
            sendPreamble();

            for (byte b : data) {
                for (int i = 7; i >= 0; i--) {
                    boolean bit = ((b >> i) & 1) == 1;
                    setFlashlight(bit);
                    sleep(BIT_DURATION_MS);
                }
            }

            setFlashlight(false);
            isTransmitting = false;
            if (onComplete != null) {
                handler.post(onComplete);
            }
        }).start();
    }

    private void sendPreamble() {
        for (int i = 0; i < 3; i++) {
            setFlashlight(true);
            sleep(100);
            setFlashlight(false);
            sleep(100);
        }
        sleep(300); // Guard interval after preamble
    }

    private void setFlashlight(boolean on) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraManager.setTorchMode(cameraId, on);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
