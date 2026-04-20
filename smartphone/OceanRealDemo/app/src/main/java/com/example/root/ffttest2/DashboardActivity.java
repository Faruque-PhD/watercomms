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
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.example.root.ffttest2.optical.FlashlightController;
import com.example.root.ffttest2.optical.OpticalDetector;
import com.example.root.ffttest2.transport.ImageAssembler;
import com.example.root.ffttest2.transport.ImagePacket;
import com.example.root.ffttest2.transport.ImagePacketizer;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class DashboardActivity extends AppCompatActivity implements ImageAssembler.AssemblyListener {

    private static final int PICK_IMAGE_REQUEST = 1;
    
    private ImageView sourceImage, reconstructedImage, channelIcon;
    private TextView statusConsole;
    private Button igniteButton, listenButton, captureButton, cameraToggleButton;
    private PreviewView alignmentPreview;

    private boolean isAcoustic = true;
    private boolean isListening = false;
    private boolean isCameraActive = false;
    private Bitmap selectedBitmap;
    
    private FlashlightController flashlightController;
    private ImageAssembler imageAssembler;
    private OpticalDetector opticalDetector;
    private ImageCapture imageCapture;

    private ProgressBar circularProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        // UI Initialization
        sourceImage = findViewById(R.id.sourceImage);
        reconstructedImage = findViewById(R.id.reconstructedImage);
        channelIcon = findViewById(R.id.channelIcon);
        statusConsole = findViewById(R.id.statusConsole);
        igniteButton = findViewById(R.id.igniteButton);
        listenButton = findViewById(R.id.listenButton);
        captureButton = findViewById(R.id.captureButton);
        cameraToggleButton = findViewById(R.id.cameraToggleButton);
        alignmentPreview = findViewById(R.id.alignmentPreview);
        circularProgress = findViewById(R.id.circularProgress);

        // Core Components
        flashlightController = new FlashlightController(this);
        imageAssembler = new ImageAssembler(this);
        Decoder.setImageAssembler(imageAssembler);
        
        MainActivity.setActivity(this);
        Constants.statusConsole = statusConsole;
        Constants.setup(this);

        setupListeners();
    }

    private void setupListeners() {
        channelIcon.setOnClickListener(v -> toggleChannel());
        sourceImage.setOnClickListener(v -> pickImage());
        igniteButton.setOnClickListener(v -> startTransmission());
        listenButton.setOnClickListener(v -> toggleListen());
        cameraToggleButton.setOnClickListener(v -> toggleCamera());
        captureButton.setOnClickListener(v -> takePhoto());
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
                if (opticalDetector != null) {
                    opticalDetector.setDecoding(false);
                }
                circularProgress.setVisibility(View.GONE);
            }
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
                selectedBitmap = android.graphics.BitmapFactory.decodeFile(photoFile.getAbsolutePath());
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
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
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

        Constants.user = Constants.User.Alice;
        int payloadSize = isAcoustic ? 128 : 512;
        List<ImagePacket> packets = ImagePacketizer.packetize(selectedBitmap, (int)System.currentTimeMillis(), payloadSize);
        
        statusConsole.setText("Igniting Transmission...\nPackets: " + packets.size());
        circularProgress.setVisibility(View.VISIBLE);
        circularProgress.setProgress(0);
        circularProgress.setMax(packets.size());

        if (isAcoustic) {
            new SendChirpAsyncTask(this, packets).execute();
        } else {
            // Optical transmission - unbind camera first to free flash resources
            ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
            cameraProviderFuture.addListener(() -> {
                try {
                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                    cameraProvider.unbindAll(); // Crucial for Flashlight access in some devices
                    
                    new Thread(() -> {
                        for (int i = 0; i < packets.size(); i++) {
                            final int idx = i;
                            runOnUiThread(() -> {
                                statusConsole.setText("Optical Sending Pkt: " + (idx+1) + "/" + packets.size());
                                circularProgress.setProgress(idx + 1);
                            });
                            flashlightController.transmit(packets.get(i).toBytes(), null);
                            try { Thread.sleep(1000); } catch (InterruptedException e) {}
                        }
                        runOnUiThread(() -> {
                            statusConsole.setText("Optical Transmission Complete.");
                            circularProgress.setVisibility(View.GONE);
                            startCameraPreview(); // Re-bind for alignment
                        });
                    }).start();
                } catch (Exception e) { e.printStackTrace(); }
            }, ContextCompat.getMainExecutor(this));
        }
    }

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
                    private java.io.ByteArrayOutputStream opticalBuffer = new java.io.ByteArrayOutputStream();

                    @Override
                    public void onBitDetected(boolean bit) {
                    }

                    @Override
                    public void onByteReceived(byte b) {
                        opticalBuffer.write(b);
                        byte[] currentData = opticalBuffer.toByteArray();
                        
                        try {
                            com.example.root.ffttest2.transport.ImagePacket packet = com.example.root.ffttest2.transport.ImagePacket.fromBytes(currentData);
                            if (packet != null) {
                                runOnUiThread(() -> {
                                    statusConsole.setText("Optical Packet Rcvd: " + packet.packetIndex + "/" + packet.totalPackets);
                                    imageAssembler.addPacket(packet);
                                });
                                opticalBuffer.reset(); 
                            }
                        } catch (Exception e) { }
                    }

                    @Override
                    public void onIntensityChanged(double intensity) {
                    }

                    @Override
                    public void onStateChanged(String state, int progress) {
                        runOnUiThread(() -> {
                            if (state.equals("RECEIVING")) {
                                circularProgress.setVisibility(View.VISIBLE);
                                circularProgress.setMax(256); // Estimate for status bar
                                circularProgress.setProgress(progress % 256);
                                statusConsole.setText("Decoding Bits: " + progress);
                            } else if (state.equals("SYNCING")) {
                                statusConsole.setText("Syncing Optical Signal: " + progress + "/3");
                            } else {
                                statusConsole.setText("Optical Status: " + state);
                            }
                        });
                    }
                });

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), opticalDetector);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis, imageCapture);

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Decoder.setImageAssembler(null);
    }

    // Assembly Listener Methods
    @Override
    public void onPacketReceived(int current, int total) {
        runOnUiThread(() -> {
            statusConsole.setText("Receiving Image: " + current + "/" + total + " packets");
            circularProgress.setVisibility(View.VISIBLE);
            circularProgress.setMax(total);
            circularProgress.setProgress(current);
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
