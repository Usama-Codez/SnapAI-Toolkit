package com.example.mlkitapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@androidx.annotation.OptIn(markerClass = ExperimentalGetImage.class)
public class ObjectDetectionActivity extends AppCompatActivity {
    private static final String TAG = "ObjectDetectionActivity";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};

    private PreviewView previewView;
    private ObjectDetectionOverlay objectOverlay;
    private Camera camera;
    private ExecutorService cameraExecutor;
    private ObjectDetector objectDetector;
    private boolean isPaused = false;
    
    // UI elements
    private RecyclerView rvObjects;
    private Button btnCaptureFreeze, btnCloseSettings;
    private ToggleButton toggleTracking;
    private ImageButton btnSettings;
    private CardView settingsPanel;
    private RadioGroup radioDetectionMode;
    private RadioButton radioMultiple, radioSingle;
    private SeekBar seekbarThreshold;
    private TextView tvThreshold;
    
    private ObjectAdapter objectAdapter;
    private float confidenceThreshold = 0.5f;
    private boolean isMultipleDetection = true;
    private boolean isTrackingEnabled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_object_detection);

        // Initialize UI elements
        previewView = findViewById(R.id.preview_view);
        objectOverlay = findViewById(R.id.object_overlay);
        rvObjects = findViewById(R.id.rv_objects);
        btnCaptureFreeze = findViewById(R.id.btn_capture_freeze);
        toggleTracking = findViewById(R.id.toggle_tracking);
        btnSettings = findViewById(R.id.btn_settings);
        settingsPanel = findViewById(R.id.settings_panel);
        btnCloseSettings = findViewById(R.id.btn_close_settings);
        radioDetectionMode = findViewById(R.id.radio_detection_mode);
        radioMultiple = findViewById(R.id.radio_multiple);
        radioSingle = findViewById(R.id.radio_single);
        seekbarThreshold = findViewById(R.id.seekbar_threshold);
        tvThreshold = findViewById(R.id.tv_threshold);

        // Setup RecyclerView
        objectAdapter = new ObjectAdapter();
        rvObjects.setLayoutManager(new LinearLayoutManager(this));
        rvObjects.setAdapter(objectAdapter);

        // Set up ML Kit Object Detector with default options
        setupObjectDetector();

        // Set up button listeners
        btnCaptureFreeze.setOnClickListener(v -> {
            if (isPaused) {
                // Resume analysis
                isPaused = false;
                objectOverlay.resumeAnalysis();
                btnCaptureFreeze.setText("Capture");
            } else {
                // Pause analysis
                isPaused = true;
                objectOverlay.pauseAnalysis();
                btnCaptureFreeze.setText("Resume");
            }
        });

        toggleTracking.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isTrackingEnabled = isChecked;
            objectOverlay.setTrackingMode(isTrackingEnabled);
            
            // Recreate the object detector with new options
            setupObjectDetector();
        });

        btnSettings.setOnClickListener(v -> {
            settingsPanel.setVisibility(
                    settingsPanel.getVisibility() == View.VISIBLE ? 
                    View.GONE : View.VISIBLE);
        });

        btnCloseSettings.setOnClickListener(v -> {
            settingsPanel.setVisibility(View.GONE);
            applySettings();
        });

        radioDetectionMode.setOnCheckedChangeListener((group, checkedId) -> {
            isMultipleDetection = checkedId == R.id.radio_multiple;
        });

        seekbarThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                confidenceThreshold = progress / 100f;
                tvThreshold.setText("Confidence Threshold: " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private void setupObjectDetector() {
        if (objectDetector != null) {
            objectDetector.close();
        }
        
        ObjectDetectorOptions.Builder builder = new ObjectDetectorOptions.Builder()
                .setDetectorMode(isTrackingEnabled ? 
                        ObjectDetectorOptions.STREAM_MODE : 
                        ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableClassification();
                
        if (isMultipleDetection) {
            builder.enableMultipleObjects();
        }
        
        objectDetector = ObjectDetection.getClient(builder.build());
    }

    private void applySettings() {
        // Update overlay settings
        objectOverlay.setConfidenceThreshold(confidenceThreshold);
        objectOverlay.setSingleObjectMode(!isMultipleDetection);
        
        // Recreate the object detector with updated settings
        setupObjectDetector();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                // Used to bind the lifecycle of cameras to the lifecycle owner
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // Preview use case
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // Image analysis use case
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(720, 1280))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processImageForObjectDetection);

                // Select back camera as a default
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                // Unbind use cases before rebinding
                cameraProvider.unbindAll();

                // Bind use cases to camera
                camera = cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Use case binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @ExperimentalGetImage
    private void processImageForObjectDetection(ImageProxy imageProxy) {
        try {
            if (isPaused || imageProxy.getImage() == null) {
                imageProxy.close();
                return;
            }

            InputImage image = InputImage.fromMediaImage(
                    imageProxy.getImage(),
                    imageProxy.getImageInfo().getRotationDegrees()
            );

            objectDetector.process(image)
                    .addOnSuccessListener(detectedObjects -> {
                        if (!isPaused) {
                            objectAdapter.updateObjects(detectedObjects);
                            objectOverlay.updateObjects(
                                    detectedObjects,
                                    imageProxy.getWidth(),
                                    imageProxy.getHeight()
                            );
                        }
                        imageProxy.close();
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Object detection failed", e);
                        imageProxy.close();
                    });
        } catch (Exception e) {
            Log.e(TAG, "Error processing image for object detection", e);
            imageProxy.close();
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != 
                    PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, 
                        "Permissions not granted by the user.", 
                        Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (objectDetector != null) {
            objectDetector.close();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}