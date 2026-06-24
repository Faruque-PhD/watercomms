package com.example.root.ffttest2;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.example.root.ffttest2.optical.FlashlightController;
import com.example.root.ffttest2.optical.FourB5B;
import com.example.root.ffttest2.optical.OpticalDetector;
import com.example.root.ffttest2.transport.ImageAssembler;
import com.example.root.ffttest2.transport.ImagePacket;
import com.example.root.ffttest2.transport.ImagePacketizer;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class DashboardActivity extends AppCompatActivity implements ImageAssembler.AssemblyListener {

    private static final int PICK_IMAGE_REQUEST = 1;
    
    private ImageView sourceImage, reconstructedImage, channelIcon;
    private TextView statusConsole;
    private Button igniteButton, listenButton, captureButton, cameraToggleButton, stopAllButton;
    private PreviewView alignmentPreview;

    private boolean isAcoustic = true;
    private boolean isListening = false;
    private boolean isCameraActive = false;
    private boolean isTransmitting = false;
    private boolean targetDecodingState = false; // Flag to ensure decoder starts after camera ready
    private Bitmap selectedBitmap;
    
    private FlashlightController flashlightController;
    private ImageAssembler imageAssembler;
    private OpticalDetector opticalDetector;
    private ImageCapture imageCapture;
    private Camera camera; // Added to hold camera reference for locking

    private ProgressBar circularProgress;

    // Accessibility & Quick Commands
    private final int[] quickCommandIds = {5, 17, 19}; // Okay, Help!, Danger
    private int currentCommandIndex = 0;
    private android.support.v4.media.session.MediaSessionCompat mediaSession;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        sourceImage = findViewById(R.id.sourceImage);
        reconstructedImage = findViewById(R.id.reconstructedImage);
        channelIcon = findViewById(R.id.channelIcon);
        statusConsole = findViewById(R.id.statusConsole);
        igniteButton = findViewById(R.id.igniteButton);
        listenButton = findViewById(R.id.listenButton);
        captureButton = findViewById(R.id.captureButton);
        cameraToggleButton = findViewById(R.id.cameraToggleButton);
        stopAllButton = findViewById(R.id.stopAllButton);
        alignmentPreview = findViewById(R.id.alignmentPreview);
        circularProgress = findViewById(R.id.circularProgress);

        // Core Components
        flashlightController = new FlashlightController(this);
        imageAssembler = new ImageAssembler(this);
        Decoder.setImageAssembler(imageAssembler);
        
        MainActivity.setActivity(this);
        Constants.statusConsole = statusConsole;
        Constants.setup(this);

        setupMediaSession();
        setupListeners();
    }

    private void setupMediaSession() {
        mediaSession = new android.support.v4.media.session.MediaSessionCompat(this, "VLC_Controller");
        mediaSession.setFlags(android.support.v4.media.session.MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                             android.support.v4.media.session.MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        mediaSession.setCallback(new android.support.v4.media.session.MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                transmitQuickCommand();
            }

            @Override
            public void onPause() {
                transmitQuickCommand();
            }

            @Override
            public void onSkipToNext() {
                navigateCommand(1);
            }

            @Override
            public void onSkipToPrevious() {
                navigateCommand(-1);
            }
        });

        android.support.v4.media.session.PlaybackStateCompat state = new android.support.v4.media.session.PlaybackStateCompat.Builder()
                .setActions(android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY |
                            android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE |
                            android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                            android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .setState(android.support.v4.media.session.PlaybackStateCompat.STATE_STOPPED, 0, 1.0f)
                .build();

        mediaSession.setPlaybackState(state);
        mediaSession.setActive(true);
    }

    private void setupListeners() {
        channelIcon.setOnClickListener(v -> toggleChannel());
        sourceImage.setOnClickListener(v -> pickImage());
        igniteButton.setOnClickListener(v -> startTransmission());
        listenButton.setOnClickListener(v -> toggleListen());
        cameraToggleButton.setOnClickListener(v -> toggleCamera());
        captureButton.setOnClickListener(v -> takePhoto());
        stopAllButton.setOnClickListener(v -> stopEverything());
    }

    private void toggleCamera() {
        isCameraActive = !isCameraActive;
        if (isCameraActive) {
            cameraToggleButton.setText("STOP CAMERA");
            cameraToggleButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.RED));
            startCameraPreview();
        } else {
            cameraToggleButton.setText("START CAMERA");
            cameraToggleButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#3a86ff")));
            stopCameraPreview();
        }
    }

    private void stopEverything() {
        isTransmitting = false; // Stop flashlight loops
        isListening = false;
        
        MainActivity.stopMethod(); // Stop acoustic
        
        if (opticalDetector != null) {
            opticalDetector.setDecoding(false);
        }

        listenButton.setText(isAcoustic ? "LISTEN" : "START DECODING");
        listenButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(isAcoustic ? android.graphics.Color.parseColor("#ff5d00") : android.graphics.Color.parseColor("#4caf50")));
        statusConsole.setText("System Halted.");
        circularProgress.setVisibility(View.GONE);
    }

    private void toggleListen() {
        if (!isListening) {
            if (isAcoustic) {
                boolean audioPerm = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
                if (!audioPerm) {
                    statusConsole.setText("Error: Audio permission required.");
                    Toast.makeText(this, "Please grant Microphone permission in app settings.", Toast.LENGTH_LONG).show();
                    return;
                }
            } else {
                if (!isCameraActive) {
                    toggleCamera();
                }
            }
        }

        isListening = !isListening;
        if (isListening) {
            listenButton.setText("STOP " + (isAcoustic ? "LISTEN" : "DECODING"));
            listenButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.RED));
            
            if (isAcoustic) {
                statusConsole.setText("ACOUSTIC LISTENING ACTIVE...");
                Constants.user = Constants.User.Bob;
                MainActivity.startMethod(this);
            } else {
                statusConsole.setText("OPTICAL DECODER ACTIVE...");
                targetDecodingState = true;
                if (opticalDetector != null) {
                    opticalDetector.setDecoding(true);
                }
            }
        } else {
            listenButton.setText(isAcoustic ? "LISTEN" : "START DECODING");
            listenButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(isAcoustic ? android.graphics.Color.parseColor("#ff5d00") : android.graphics.Color.parseColor("#4caf50")));
            statusConsole.setText(isAcoustic ? "Acoustic Listening Stopped." : "Optical Decoding Paused.");
            
            if (isAcoustic) {
                MainActivity.stopMethod();
            } else {
                targetDecodingState = false;
                if (opticalDetector != null) {
                    opticalDetector.setDecoding(false);
                    lockCameraSettings(false);
                }
            }
            circularProgress.setVisibility(View.GONE);
        }
    }

    private void takePhoto() {
        if (imageCapture == null) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        String name = new java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", java.util.Locale.US).format(System.currentTimeMillis());
        java.io.File photoFile = new java.io.File(getExternalFilesDir(null), name + ".jpg");

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(ImageCapture.OutputFileResults outputFileResults) {
                Toast.makeText(DashboardActivity.this, "Photo Saved: " + photoFile.getName(), Toast.LENGTH_SHORT).show();
                statusConsole.setText("Photo Saved. Loading into source...");
                
                // Load captured photo as source image
                selectedBitmap = android.graphics.BitmapFactory.decodeFile(photoFile.getAbsolutePath(), new android.graphics.BitmapFactory.Options());
                if (selectedBitmap != null) {
                    runOnUiThread(() -> sourceImage.setImageBitmap(selectedBitmap));
                }
            }

            @Override
            public void onError(ImageCaptureException exception) {
                statusConsole.setText("Capture Error: " + exception.getMessage());
            }
        });
    }

    private void stopCameraPreview() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                cameraProvider.unbindAll();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void toggleChannel() {
        isAcoustic = !isAcoustic;
        channelIcon.setImageResource(isAcoustic ? android.R.drawable.ic_lock_silent_mode_off : android.R.drawable.ic_menu_compass);
        statusConsole.setText("Channel Switched to: " + (isAcoustic ? "ACOUSTIC" : "OPTICAL"));
        
        // Reset Listen Button UI
        listenButton.setText(isAcoustic ? "LISTEN" : "START DECODING");
        listenButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(isAcoustic ? android.graphics.Color.parseColor("#ff5d00") : android.graphics.Color.parseColor("#4caf50")));
        
        Toast.makeText(this, "Switched to " + (isAcoustic ? "Acoustic" : "Optical"), Toast.LENGTH_SHORT).show();
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            navigateCommand(1);
            return true;
        } else if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            navigateCommand(-1);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void transmitQuickCommand() {
        String commandName = Constants.mmap.get(quickCommandIds[currentCommandIndex]);
        if (isAcoustic) {
            // Summary said Acoustic is different, but we could trigger it here if needed.
            // For now, let's focus on Optical as requested.
            runOnUiThread(() -> statusConsole.setText("Quick Command (Acoustic): " + commandName));
            // Trigger acoustic transmission if implemented
        } else {
            statusConsole.setText("Optical Sending: " + commandName);
            new Thread(() -> {
                transmitSync(commandName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                runOnUiThread(() -> statusConsole.setText("Optical Transmission Complete."));
            }).start();
        }
    }

    private void navigateCommand(int direction) {
        currentCommandIndex = (currentCommandIndex + direction + quickCommandIds.length) % quickCommandIds.length;
        String commandName = Constants.mmap.get(quickCommandIds[currentCommandIndex]);
        statusConsole.setText("Selected Command: " + commandName);
        
        if (Constants.tts != null) {
            Constants.tts.speak(commandName, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri imageUri = data.getData();
            try {
                selectedBitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
                sourceImage.setImageBitmap(selectedBitmap);
                statusConsole.setText("Image Selected. Ready to Ignite.");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void startTransmission() {
        if (selectedBitmap == null) {
            Toast.makeText(this, "Please select an image first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isAcoustic) {
            boolean audioPerm = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!audioPerm) {
                statusConsole.setText("Error: Audio permission required.");
                Toast.makeText(this, "Please grant Microphone permission in app settings.", Toast.LENGTH_LONG).show();
                return;
            }
        }

        Constants.user = Constants.User.Alice;
        // Even smaller payload for Optical to ensure we get progress updates more often
        int payloadSize = isAcoustic ? 128 : 32; 
        List<ImagePacket> packets = ImagePacketizer.packetize(selectedBitmap, (int)System.currentTimeMillis(), payloadSize);
        
        int estSecondsPerPacket = isAcoustic ? 3 : (payloadSize + 9) * 2;
        statusConsole.setText("Igniting Transmission...\nPackets: " + packets.size() + " (Est: " + (packets.size() * estSecondsPerPacket / 60) + " mins " + (packets.size() * estSecondsPerPacket % 60) + " secs)");
        circularProgress.setVisibility(View.VISIBLE);
        circularProgress.setProgress(0);
        circularProgress.setMax(packets.size());

        if (isAcoustic) {
            Constants.work = true;
            new SendChirpAsyncTask(this, packets).execute();
        } else {
            isTransmitting = true;
            // Optical transmission - unbind camera first to free flash resources
            ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
            cameraProviderFuture.addListener(() -> {
                try {
                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                    cameraProvider.unbindAll(); // Crucial for Flashlight access in some devices
                    
                    // Give the OS a moment to fully release camera hardware
                    try { Thread.sleep(500); } catch (InterruptedException e) {}
                    
                    new Thread(() -> {
                        for (int i = 0; i < packets.size() && isTransmitting; i++) {
                            final int idx = i;
                            final byte[] packetData = packets.get(i).toBytes();
                            runOnUiThread(() -> {
                                statusConsole.setText("Optical Sending Pkt: " + (idx+1) + "/" + packets.size());
                                circularProgress.setProgress(idx + 1);
                            });
                            
                            // Using a simple blocking transmit approach for reliability
                            transmitSync(packetData);
                        }
                        isTransmitting = false;
                        runOnUiThread(() -> {
                            statusConsole.setText("Optical Transmission Complete.");
                            circularProgress.setVisibility(View.GONE);
                            startCameraPreview(); // Re-bind for alignment
                        });
                    }).start();
                } catch (Exception e) { 
                    isTransmitting = false;
                    e.printStackTrace(); 
                }
            }, ContextCompat.getMainExecutor(this));
        }
    }

    private void transmitSync(byte[] data) {
        // Start Preamble (800ms HIGH to wake up receiver and stabilize threshold)
        flashlightController.setFlashlight(true);
        try { Thread.sleep(800); } catch (InterruptedException e) {}
        flashlightController.setFlashlight(false);
        try { Thread.sleep(200); } catch (InterruptedException e) {} // Guard interval

        // Data Bits
        for (byte b : data) {
            if (!isTransmitting) break;
            int encoded = FourB5B.encode(b);
            for (int i = 9; i >= 0; i--) {
                if (!isTransmitting) break;
                boolean bit = ((encoded >> i) & 1) == 1;
                flashlightController.setFlashlight(bit);
                try { Thread.sleep(200); } catch (InterruptedException e) {} // 5 Hz
            }
        }
        flashlightController.setFlashlight(false);
        try { Thread.sleep(900); } catch (InterruptedException e) {} // STOP signal (900ms LOW)
    }

    @ExperimentalCamera2Interop
    private void startCameraPreview() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            statusConsole.setText("Camera Permission Required!");
            return;
        }
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(alignmentPreview.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder().build();

                opticalDetector = new OpticalDetector(new OpticalDetector.OpticalListener() {
                    private StringBuilder bitStream = new StringBuilder();
                    private StringBuilder textAccumulator = new StringBuilder();

                    @Override
                    public void onCalibrationComplete(double threshold) {
                        runOnUiThread(() -> {
                            statusConsole.setText("Calibration Done. Locking Camera.");
                            statusConsole.setTag(System.currentTimeMillis() + 2000);
                            lockCameraSettings(true);
                        });
                    }

                    @Override
                    public void onBitDetected(boolean bit) {
                    }

                    @Override
                    public void onByteReceived(byte b) {
                        // 1. Accumulate as potential text (Quick Commands)
                        char c = (char) (b & 0xFF);
                        if (c >= 32 && c <= 126) { // Printable ASCII
                            textAccumulator.append(c);
                        } else {
                            // Only reset if we haven't found a command yet
                            if (textAccumulator.length() < 2) textAccumulator.setLength(0);
                        }

                        if (textAccumulator.length() >= 2) {
                            final String msg = textAccumulator.toString();
                            android.util.Log.d("DashboardActivity", "DECODED TEXT: " + msg);
                            runOnUiThread(() -> {
                                statusConsole.setText("Decoded Text: " + msg);
                                // Hold this message for a bit longer
                                statusConsole.setTag(System.currentTimeMillis() + 3000); 
                            });
                        }

                        // 2. Accumulate as bits for ImagePackets
                        String s = String.format("%8s", Integer.toBinaryString(b & 0xFF)).replace(' ', '0');
                        bitStream.append(s);

                        // Keep buffer length limited
                        if (bitStream.length() > 10000) bitStream.delete(0, 5000);

                        // MAGIC HEADER: 'OR' (0x4F, 0x52)
                        String magic = "0100111101010010";
                        int idx = bitStream.indexOf(magic);

                        if (idx != -1) {
                            int payloadSize = isAcoustic ? 128 : 32;
                            int totalPacketBits = (ImagePacket.HEADER_SIZE + payloadSize + ImagePacket.CRC_SIZE) * 8;
                            
                            if (bitStream.length() >= idx + totalPacketBits) {
                                String packetBits = bitStream.substring(idx, idx + totalPacketBits);
                                byte[] data = bitStringToBytes(packetBits);
                                try {
                                    ImagePacket packet = ImagePacket.fromBytes(data);
                                    if (packet != null) {
                                        android.util.Log.i("OpticalDetector", "VALID PACKET! Pkt: " + packet.packetIndex + "/" + packet.totalPackets);
                                        runOnUiThread(() -> {
                                            statusConsole.setText("Optical Packet Rcvd: " + (packet.packetIndex + 1) + "/" + packet.totalPackets);
                                            statusConsole.setTag(System.currentTimeMillis() + 2000);
                                            imageAssembler.addPacket(packet);
                                        });
                                        bitStream.delete(0, idx + totalPacketBits);
                                    } else {
                                        // Found magic but not a valid packet structure, skip it
                                        bitStream.delete(0, idx + 8);
                                    }
                                } catch (Exception e) {
                                    bitStream.delete(0, idx + 8);
                                }
                            } else if (bitStream.length() > idx + totalPacketBits + 800) {
                                // We've waited a long time (800+ bits) and still haven't finished this packet.
                                // It's likely a false positive or corrupted. Skip the magic.
                                bitStream.delete(0, idx + 8);
                            }
                        }
                    }

                    private byte[] bitStringToBytes(String s) {
                        byte[] b = new byte[s.length() / 8];
                        for (int i = 0; i < b.length; i++) {
                            b[i] = (byte) Integer.parseInt(s.substring(i * 8, i * 8 + 8), 2);
                        }
                        return b;
                    }

                    @Override
                    public void onIntensityChanged(double intensity) {
                    }

                    @Override
                    public void onStateChanged(String state, int progress) {
                        runOnUiThread(() -> {
                            // Check if there's a priority message currently being displayed
                            Object tag = statusConsole.getTag();
                            if (tag instanceof Long) {
                                if (System.currentTimeMillis() < (Long) tag) {
                                    return; // Don't overwrite yet
                                }
                            }

                            if (state.equals("RECEIVING")) {
                                circularProgress.setVisibility(View.VISIBLE);
                                circularProgress.setIndeterminate(true);
                                statusConsole.setText("Decoding Bits: " + progress);
                            } else if (state.equals("SYNCING")) {
                                circularProgress.setVisibility(View.VISIBLE);
                                circularProgress.setIndeterminate(false);
                                circularProgress.setMax(3);
                                circularProgress.setProgress(progress);
                                statusConsole.setText("Syncing Optical Signal: " + progress + "/3");
                            } else {
                                circularProgress.setVisibility(View.GONE);
                                statusConsole.setText("Optical Status: " + state);
                            }
                        });
                    }
                });

                // Apply the pending state (crucial for race condition fix)
                opticalDetector.setDecoding(targetDecodingState);

                ImageAnalysis.Builder analysisBuilder = new ImageAnalysis.Builder()
                        .setTargetResolution(new android.util.Size(320, 240))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);

                ImageAnalysis imageAnalysis = analysisBuilder.build();
                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), opticalDetector);

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis, imageCapture);

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @ExperimentalCamera2Interop
    private void lockCameraSettings(boolean lock) {
        if (camera == null) return;
        
        android.util.Log.d("DashboardActivity", "Setting Camera Lock: " + lock);
        Camera2CameraControl extender = Camera2CameraControl.from(camera.getCameraControl());
        
        CaptureRequestOptions options = new CaptureRequestOptions.Builder()
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_LOCK, lock)
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AWB_LOCK, lock)
                .build();
        
        extender.setCaptureRequestOptions(options);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopEverything();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopEverything();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaSession != null) {
            mediaSession.release();
        }
        Decoder.setImageAssembler(null);
    }

    // Assembly Listener Methods
    @Override
    public void onPacketReceived(int current, int total) {
        runOnUiThread(() -> {
            StringBuilder status = new StringBuilder("Receiving: " + current + "/" + total);
            
            // Check for missing packets in the current range
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < current; i++) {
                if (imageAssembler.getPacketBuffer().get(i) == null) {
                    missing.add(i + 1);
                }
            }
            if (!missing.isEmpty()) {
                status.append(" (Missing: ").append(missing.size()).append(")");
                android.util.Log.w("DashboardActivity", "Missing Acoustic Packets: " + missing.toString());
            }

            statusConsole.setText(status.toString());
            circularProgress.setVisibility(View.VISIBLE);
            circularProgress.setMax(total);
            circularProgress.setProgress(current);
        });
    }

    @Override
    public void onPartialImageReconstructed(Bitmap bitmap) {
        runOnUiThread(() -> {
            if (bitmap != null) {
                reconstructedImage.setImageBitmap(bitmap);
                statusConsole.setText("Partial Image Reconstructed...");
            }
        });
    }

    @Override
    public void onImageComplete(Bitmap bitmap) {
        runOnUiThread(() -> {
            reconstructedImage.setImageBitmap(bitmap);
            statusConsole.setText("IMAGE RECONSTRUCTION COMPLETE!");
            circularProgress.setVisibility(View.GONE);
            Toast.makeText(this, "Image Received Successfully!", Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onImageError(String error) {
        runOnUiThread(() -> statusConsole.setText("Reconstruction Error: " + error));
    }
}
