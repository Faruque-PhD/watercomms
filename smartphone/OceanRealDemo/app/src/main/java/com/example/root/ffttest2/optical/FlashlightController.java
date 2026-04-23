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
    private static final long BIT_DURATION_MS = 200; // 5 Hz
    
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
            // Start Preamble (800ms HIGH to wake up receiver and stabilize threshold)
            setFlashlight(true);
            sleep(800);
            setFlashlight(false);
            sleep(200); // Guard interval

            for (byte b : data) {
                if (!isTransmitting) break;
                int encoded = FourB5B.encode(b);
                for (int i = 9; i >= 0; i--) {
                    if (!isTransmitting) break;
                    boolean bit = ((encoded >> i) & 1) == 1;
                    setFlashlight(bit);
                    sleep(BIT_DURATION_MS);
                }
            }

            setFlashlight(false);
            sleep(900); // STOP signal
            isTransmitting = false;
            if (onComplete != null) {
                handler.post(onComplete);
            }
        }).start();
    }

    public void setFlashlight(boolean on) {
        if (cameraId == null) return;
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
