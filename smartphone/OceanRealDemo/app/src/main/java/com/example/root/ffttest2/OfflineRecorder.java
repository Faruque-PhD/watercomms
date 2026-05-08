package com.example.root.ffttest2;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Optimized OfflineRecorder using a circular buffer to prevent OOM errors.
 */
public class OfflineRecorder extends Thread {
    public boolean recording;
    private int samplingfrequency;
    private AudioRecord rec;
    private int minbuffersize;
    private Activity av;
    private String filename;
    private int channels;
    
    private int read_pointer = 0;
    private int write_pointer = 0;
    private int total_samples_recorded = 0;
    
    // Circular Buffer
    private static final int BUFFER_SIZE = 48000 * 10; // 10 seconds of audio for better stability
    private short[] circularBuffer = new short[BUFFER_SIZE];
    private final ReentrantLock bufferLock = new ReentrantLock();

    public OfflineRecorder(Activity av, int samplingfrequency, String filename) {
        this.filename = filename;
        this.av = av;
        this.samplingfrequency = samplingfrequency;
        
        if (Constants.stereo) {
            channels = AudioFormat.CHANNEL_IN_STEREO;
        } else {
            channels = AudioFormat.CHANNEL_IN_MONO;
        }

        minbuffersize = AudioRecord.getMinBufferSize(
                samplingfrequency,
                channels,
                AudioFormat.ENCODING_PCM_16BIT);
        
        if (ActivityCompat.checkSelfPermission(av, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("OfflineRecorder", "Microphone permission not granted!");
        }

        rec = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                samplingfrequency, 
                channels,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minbuffersize, BUFFER_SIZE / 2));
    }

    public void halt2() {
        this.recording = false;
        try {
            if (this.rec != null) {
                if (this.rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop();
                }
                rec.release();
            }
        } catch (Exception e) {
            Log.e("OfflineRecorder", "Error during halt: " + e.getMessage());
        }
    }

    public void start2() {
        recording = true;
        this.start();
    }

    /**
     * Optimized: Returns a primitive double array directly to avoid boxing overhead.
     */
    public double[] get_FIFO() {
        int targetSize = Constants.RecorderStepSize;
        
        // Wait for enough data
        while (recording && getAvailableData() < targetSize) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                break;
            }
        }

        if (!recording && getAvailableData() < targetSize) return null;

        double[] return_array = new double[targetSize];
        bufferLock.lock();
        try {
            for (int i = 0; i < targetSize; i++) {
                return_array[i] = (double) circularBuffer[(read_pointer + i) % BUFFER_SIZE];
            }
            read_pointer = (read_pointer + targetSize) % BUFFER_SIZE;
        } finally {
            bufferLock.unlock();
        }
        return return_array;
    }

    private int getAvailableData() {
        bufferLock.lock();
        try {
            if (write_pointer >= read_pointer) {
                return write_pointer - read_pointer;
            } else {
                return (BUFFER_SIZE - read_pointer) + write_pointer;
            }
        } finally {
            bufferLock.unlock();
        }
    }

    @Override
    public void run() {
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e("OfflineRecorder", "AudioRecord failed to initialize!");
            return;
        }

        rec.startRecording();
        short[] temp = new short[minbuffersize];

        while (recording) {
            int bytesread = rec.read(temp, 0, minbuffersize);
            if (bytesread > 0) {
                bufferLock.lock();
                try {
                    for (int i = 0; i < bytesread; i++) {
                        circularBuffer[write_pointer] = temp[i];
                        write_pointer = (write_pointer + 1) % BUFFER_SIZE;
                        
                        // Overrun detection: if write catches up to read
                        if (write_pointer == read_pointer) {
                            read_pointer = (read_pointer + 1) % BUFFER_SIZE; // Drop oldest
                        }
                    }
                    total_samples_recorded += bytesread;
                } finally {
                    bufferLock.unlock();
                }
                
                if (MainActivity.activityInstance != null && total_samples_recorded % 48000 < bytesread) {
                    MainActivity.activityInstance.logPerf("BOB", "BUFFER_STATUS", "Available:" + getAvailableData());
                }
            }
        }
        
        Log.e("OfflineRecorder", "Recording thread ended elegantly.");
    }
}
