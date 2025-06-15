package com.example.mlkitapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@androidx.annotation.OptIn(markerClass = ExperimentalGetImage.class)
public class FaceDetectionActivity extends AppCompatActivity {
    private static final String TAG = "FaceDetectionActivity";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};

    private PreviewView previewView;
    private FaceDetectionOverlay faceOverlay;
    private Camera camera;
    private ExecutorService cameraExecutor;
    private FaceDetector faceDetector;
    private boolean showLandmarks = false;
    private boolean isPaused = false;
    
    // UI elements
    private TextView tvFaceCount;
    private Button btnCaptureFrame, btnToggleFeatures;
    private RecyclerView rvFaces;
    private FaceAdapter faceAdapter;
    private boolean isFrontFacing = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_face_detection);

        // Initialize UI elements
        previewView = findViewById(R.id.preview_view);
        faceOverlay = findViewById(R.id.face_overlay);
        tvFaceCount = findViewById(R.id.tv_face_count);
        btnCaptureFrame = findViewById(R.id.btn_capture_frame);
        btnToggleFeatures = findViewById(R.id.btn_toggle_features);
        rvFaces = findViewById(R.id.rv_faces);

        // Setup RecyclerView
        faceAdapter = new FaceAdapter();
        rvFaces.setLayoutManager(new LinearLayoutManager(this));
        rvFaces.setAdapter(faceAdapter);

        // Set up ML Kit Face Detector with appropriate options
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.15f)
                .enableTracking()
                .build();

        faceDetector = FaceDetection.getClient(options);

        // Set up button listeners
        btnToggleFeatures.setOnClickListener(v -> {
            showLandmarks = !showLandmarks;
            faceOverlay.setShowLandmarks(showLandmarks);
            btnToggleFeatures.setText(showLandmarks ? "Hide Features" : "Show Features");
        });

        btnCaptureFrame.setOnClickListener(v -> {
            if (isPaused) {
                // Resume analysis
                isPaused = false;
                faceOverlay.resumeAnalysis();
                btnCaptureFrame.setText("Capture Frame");
            } else {
                // Pause analysis
                isPaused = true;
                faceOverlay.pauseAnalysis();
                btnCaptureFrame.setText("Resume");
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
                        .setTargetResolution(new Size(480, 640))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processImageForFaceDetection);

                // Select front camera as a default
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
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
    private void processImageForFaceDetection(ImageProxy imageProxy) {
        try {
            if (isPaused || imageProxy.getImage() == null) {
                imageProxy.close();
                return;
            }

            InputImage image = InputImage.fromMediaImage(
                    imageProxy.getImage(),
                    imageProxy.getImageInfo().getRotationDegrees()
            );

            faceDetector.process(image)
                    .addOnSuccessListener(faces -> {
                        if (!isPaused) {
                            updateFaceUI(faces);
                            faceOverlay.updateFaces(
                                    faces,
                                    imageProxy.getWidth(),
                                    imageProxy.getHeight(),
                                    isFrontFacing
                            );
                        }
                        imageProxy.close();
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Face detection failed", e);
                        imageProxy.close();
                    });
        } catch (Exception e) {
            Log.e(TAG, "Error processing image for face detection", e);
            imageProxy.close();
        }
    }

    private void updateFaceUI(List<Face> faces) {
        runOnUiThread(() -> {
            // Update face count text
            tvFaceCount.setText("Faces detected: " + faces.size());
            
            // Update the RecyclerView with all faces
            faceAdapter.updateFaces(faces);
        });
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
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}