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
import androidx.camera.camera2.interop.Camera2Interop;
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

        setupListeners();
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
        // Reduced payload size for Optical to improve reliability (10bps is slow/jittery)
        int payloadSize = isAcoustic ? 128 : 64; 
        List<ImagePacket> packets = ImagePacketizer.packetize(selectedBitmap, (int)System.currentTimeMillis(), payloadSize);
        
        statusConsole.setText("Igniting Transmission...\nPackets: " + packets.size());
        circularProgress.setVisibility(View.VISIBLE);
        circularProgress.setProgress(0);
        circularProgress.setMax(packets.size());

        if (isAcoustic) {
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

                    @Override
                    public void onBitDetected(boolean bit) {
                        runOnUiThread(() -> {
                            statusConsole.setText("Detecting Bits...");
                        });
                    }

                    @Override
                    public void onByteReceived(byte b) {
                        // Convert byte to 8-bit string and append
                        String s = String.format("%8s", Integer.toBinaryString(b & 0xFF)).replace(' ', '0');
                        bitStream.append(s);

                        // Keep buffer length limited (approx 5 packets)
                        if (bitStream.length() > 5000) bitStream.delete(0, 2000);

                        // MAGIC HEADER: 'OR' (0x4F, 0x52) -> 01001111 01010010
                        String magic = "0100111101010010";
                        int idx = bitStream.indexOf(magic);

                        if (idx != -1) {
                            int totalPacketBits = (ImagePacket.HEADER_SIZE + (isAcoustic ? 128 : 64)) * 8;
                            if (bitStream.length() >= idx + totalPacketBits) {
                                String packetBits = bitStream.substring(idx, idx + totalPacketBits);
                                byte[] data = bitStringToBytes(packetBits);
                                try {
                                    ImagePacket packet = ImagePacket.fromBytes(data);
                                    if (packet != null) {
                                        android.util.Log.i("OpticalDetector", "VALID PACKET! Pkt: " + packet.packetIndex);
                                        runOnUiThread(() -> {
                                            statusConsole.setText("Optical Packet Rcvd: " + packet.packetIndex + "/" + packet.totalPackets);
                                            imageAssembler.addPacket(packet);
                                        });
                                        bitStream.delete(0, idx + totalPacketBits);
                                    } else {
                                        bitStream.delete(0, idx + 8); // Skip this magic and look for next
                                    }
                                } catch (Exception e) {
                                    bitStream.delete(0, idx + 8);
                                }
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
                            if (state.equals("RECEIVING")) {
                                circularProgress.setVisibility(View.VISIBLE);
                                circularProgress.setIndeterminate(true); // Spin while receiving bits
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
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);

                // Lock Auto-Exposure (AE) to prevent brightness drift
                Camera2Interop.Extender<ImageAnalysis> analysisExtender = new Camera2Interop.Extender<>(analysisBuilder);
                // analysisExtender.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_LOCK, true);

                ImageAnalysis imageAnalysis = analysisBuilder.build();
                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), opticalDetector);

                cameraProvider.unbindAll();
                Camera camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis, imageCapture);

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
