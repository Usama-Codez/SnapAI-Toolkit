package com.example.mlkitapp;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class TextRecognitionActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private static final String TAG = "TextRecognitionAct";
    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private static final String[] CAMERA_PERMISSIONS = {Manifest.permission.CAMERA};
    private static final int MAX_PREVIEW_WIDTH = 1280;
    private static final int MAX_PREVIEW_HEIGHT = 720;

    private static final float MIN_LUX_FOR_NO_FLASH = 50.0f;
    private boolean shouldUseFlash = true;

    private final Executor imageProcessingExecutor = Executors.newSingleThreadExecutor();

    private WeakReference<SurfaceView> viewFinderRef;
    private Button captureButton;
    private Button copyButton;
    private Button shareButton;
    private TextView textResult;
    private BoundingBoxView overlay;

    private TextRecognizer textRecognizer;
    private String recognizedText = "";

    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimension;
    private ImageReader imageReader;
    private volatile boolean isCapturing = false;
    private volatile boolean isCameraReady = false;
    private long lastToastTime = 0;

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private int cameraRetryCount = 0;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);

    private enum CameraState {
        CLOSED, OPENING, OPENED, CLOSING, ERROR
    }
    private volatile CameraState cameraState = CameraState.CLOSED;

    private SensorManager sensorManager;
    private LightSensorListener lightSensorListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_recognition);

        SurfaceView viewFinder = findViewById(R.id.viewFinder);
        viewFinderRef = new WeakReference<>(viewFinder);
        captureButton = findViewById(R.id.btn_capture);
        copyButton = findViewById(R.id.btn_copy);
        shareButton = findViewById(R.id.btn_share);
        textResult = findViewById(R.id.text_result);
        overlay = findViewById(R.id.overlay);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        lightSensorListener = new LightSensorListener();

        try {
            textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

            // Pre-warm ML Kit on a background thread
            imageProcessingExecutor.execute(() -> {
                try {
                    Bitmap dummyBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
                    InputImage dummyInput = InputImage.fromBitmap(dummyBitmap, 0);
                    textRecognizer.process(dummyInput).addOnCompleteListener(task -> dummyBitmap.recycle());
                } catch (Exception e) {
                    Log.w(TAG, "ML Kit warm-up failed: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize text recognizer: " + e.getMessage());
            Toast.makeText(this, "Error initializing text recognizer. Please restart the app.",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (viewFinder != null) {
            SurfaceHolder holder = viewFinder.getHolder();
            if (holder != null) {
                holder.setKeepScreenOn(true);
                holder.addCallback(this);
            } else {
                Log.e(TAG, "SurfaceHolder is null");
                Toast.makeText(this, "Camera initialization error", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        }

        captureButton.setOnClickListener(view -> {
            if (System.currentTimeMillis() - lastToastTime > 1000) {
                if (!isCapturing && isCameraReady) {
                    capturePhoto();
                } else if (isCapturing) {
                    lastToastTime = System.currentTimeMillis();
                    Toast.makeText(TextRecognitionActivity.this,
                            "Processing previous capture...", Toast.LENGTH_SHORT).show();
                } else {
                    lastToastTime = System.currentTimeMillis();
                    Toast.makeText(TextRecognitionActivity.this,
                            "Camera not ready yet", Toast.LENGTH_SHORT).show();
                }
            }
        });

        copyButton.setOnClickListener(v -> copyToClipboard(recognizedText));
        shareButton.setOnClickListener(v -> shareText(recognizedText));

        copyButton.setEnabled(false);
        shareButton.setEnabled(false);

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, CAMERA_PERMISSIONS, CAMERA_PERMISSION_REQUEST);
        }
    }

    private class LightSensorListener implements SensorEventListener {
        private float lastLightLevel = 0f;
        private long lastReadingTime = 0;
        private static final long MIN_READING_INTERVAL = 1000;

        @Override
        public void onSensorChanged(SensorEvent event) {
            long currentTime = System.currentTimeMillis();

            if (currentTime - lastReadingTime > MIN_READING_INTERVAL) {
                lastReadingTime = currentTime;
                lastLightLevel = event.values[0];

                if (isCameraReady && cameraCaptureSession != null) {
                    boolean newShouldUseFlash = lastLightLevel < MIN_LUX_FOR_NO_FLASH;
                    if (newShouldUseFlash != shouldUseFlash) {
                        shouldUseFlash = newShouldUseFlash;
                        updateFlashMode();
                    }
                }
            }
        }

        @Override
        public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {
            // Not needed
        }

        public float getLastLightLevel() {
            return lastLightLevel;
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : CAMERA_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (allPermissionsGranted()) {
                SurfaceView viewFinder = viewFinderRef.get();
                if (viewFinder != null && viewFinder.getHolder() != null) {
                    surfaceCreated(viewFinder.getHolder());
                }
            } else {
                Toast.makeText(this, "Camera permission is required for this feature",
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder surfaceHolder) {
        if (cameraState == CameraState.CLOSED) {
            openCamera();
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder surfaceHolder, int format, int width, int height) {
        if (captureRequestBuilder != null) {
            try {
                if (cameraCaptureSession != null) {
                    cameraCaptureSession.stopRepeating();
                }
            } catch (CameraAccessException | IllegalStateException e) {
                Log.e(TAG, "Failed to stop repeating: " + e.getMessage());
            }
        }

        if (cameraState == CameraState.OPENED) {
            createCameraPreview();
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder surfaceHolder) {
        closeCamera();
    }

    private void openCamera() {
        if (cameraState != CameraState.CLOSED) {
            Log.w(TAG, "Camera already in state: " + cameraState);
            return;
        }

        cameraState = CameraState.OPENING;

        if (backgroundHandler == null) {
            startBackgroundThread();
        }

        cameraRetryCount = 0;
        tryOpenCamera();
    }

    private void tryOpenCamera() {
        if (cameraRetryCount >= MAX_RETRY_ATTEMPTS) {
            Log.e(TAG, "Failed to open camera after " + MAX_RETRY_ATTEMPTS + " attempts");
            runOnUiThread(() -> {
                Toast.makeText(this, "Failed to open camera. Please restart the app.",
                        Toast.LENGTH_LONG).show();
                cameraState = CameraState.ERROR;
            });
            return;
        }

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening");
            }

            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                throw new RuntimeException("Camera manager is null");
            }

            String[] cameraIds = manager.getCameraIdList();
            if (cameraIds.length == 0) {
                throw new RuntimeException("No cameras available on this device");
            }

            for (String cameraId : cameraIds) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    this.cameraId = cameraId;
                    break;
                }
            }

            if (this.cameraId == null) {
                this.cameraId = cameraIds[0];
                Log.w(TAG, "No back-facing camera found, using camera: " + this.cameraId);
            }

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (map == null) {
                throw new RuntimeException("Cannot get available preview sizes");
            }

            Size[] availableSizes = map.getOutputSizes(SurfaceHolder.class);

            if (availableSizes == null || availableSizes.length == 0) {
                throw new RuntimeException("No available preview sizes");
            }

            final SurfaceView viewFinder = viewFinderRef.get();
            if (viewFinder == null) {
                throw new RuntimeException("SurfaceView has been garbage collected");
            }

            imageDimension = chooseOptimalSize(availableSizes,
                    viewFinder.getWidth(), viewFinder.getHeight(),
                    MAX_PREVIEW_WIDTH, MAX_PREVIEW_HEIGHT);

            Log.d(TAG, "Selected camera preview size: " + imageDimension.getWidth() + "x" + imageDimension.getHeight());

            // Setup ImageReader for image capture
            setupImageReader();

            SurfaceHolder holder = viewFinder.getHolder();
            if (holder == null) {
                throw new RuntimeException("Surface holder is null");
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Camera permission not granted");
                cameraState = CameraState.CLOSED;
                cameraOpenCloseLock.release();
                return;
            }

            isCameraReady = false;
            manager.openCamera(cameraId, stateCallback, backgroundHandler);

        } catch (CameraAccessException e) {
            cameraOpenCloseLock.release();
            Log.e(TAG, "Failed to access camera: " + e.getMessage());
            cameraRetryCount++;
            retryOpeningCamera();
        } catch (InterruptedException e) {
            cameraOpenCloseLock.release();
            Log.e(TAG, "Interrupted while locking: " + e.getMessage());
            Thread.currentThread().interrupt();
            cameraState = CameraState.ERROR;
        } catch (Exception e) {
            cameraOpenCloseLock.release();
            Log.e(TAG, "Unexpected error opening camera: " + e.getMessage(), e);
            cameraRetryCount++;
            retryOpeningCamera();
        }
    }

    private void setupImageReader() {
        if (imageDimension == null) {
            Log.e(TAG, "Cannot setup ImageReader, image dimension is null");
            return;
        }

        // Release previous ImageReader if it exists
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        imageReader = ImageReader.newInstance(
                imageDimension.getWidth(), imageDimension.getHeight(),
                android.graphics.ImageFormat.JPEG, 2);

        imageReader.setOnImageAvailableListener(reader -> {
            try {
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    processImage(image);
                    image.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing image: " + e.getMessage());
                isCapturing = false;
            }
        }, backgroundHandler);
    }

    private void retryOpeningCamera() {
        if (backgroundHandler != null) {
            backgroundHandler.postDelayed(this::tryOpenCamera, 1000);
        } else {
            cameraState = CameraState.ERROR;
            runOnUiThread(() -> Toast.makeText(this,
                    "Camera initialization failed", Toast.LENGTH_SHORT).show());
        }
    }

    private Size chooseOptimalSize(Size[] choices, int viewWidth, int viewHeight,
                                   int maxWidth, int maxHeight) {
        List<Size> bigEnough = new ArrayList<>();
        List<Size> allSizes = new ArrayList<>();

        Collections.addAll(allSizes, choices);

        float targetRatio = (float) viewWidth / viewHeight;
        float tolerance = 0.1f;

        for (Size option : choices) {
            float ratio = (float) option.getWidth() / option.getHeight();
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    Math.abs(ratio - targetRatio) < tolerance) {
                bigEnough.add(option);
            }
        }

        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        }

        if (allSizes.size() > 0) {
            Collections.sort(allSizes, new CompareSizesByArea());
            // Return a size in the middle of available sizes for balance of quality and performance
            int midIndex = allSizes.size() / 2;
            return allSizes.get(midIndex < allSizes.size() ? midIndex : 0);
        }

        Log.e(TAG, "Couldn't find suitable preview size, using default");
        return new Size(640, 480);
    }

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraOpenCloseLock.release();
            cameraDevice = camera;
            cameraState = CameraState.OPENED;
            createCameraPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
            isCameraReady = false;
            cameraState = CameraState.CLOSED;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
            isCameraReady = false;
            cameraState = CameraState.ERROR;

            if (cameraRetryCount < MAX_RETRY_ATTEMPTS) {
                cameraRetryCount++;
                Log.e(TAG, "Camera error: " + error + ", retrying (" + cameraRetryCount + "/" + MAX_RETRY_ATTEMPTS + ")");
                cameraState = CameraState.CLOSED;
                retryOpeningCamera();
            } else {
                final String errorMessage;
                switch (error) {
                    case ERROR_CAMERA_DEVICE: errorMessage = "Fatal camera device error"; break;
                    case ERROR_CAMERA_DISABLED: errorMessage = "Camera disabled by policy"; break;
                    case ERROR_CAMERA_IN_USE: errorMessage = "Camera already in use"; break;
                    case ERROR_MAX_CAMERAS_IN_USE: errorMessage = "Max cameras in use"; break;
                    default: errorMessage = "Camera error: " + error;
                }

                runOnUiThread(() -> Toast.makeText(TextRecognitionActivity.this,
                        errorMessage, Toast.LENGTH_SHORT).show());
            }
        }
    };

    private void createCameraPreview() {
        final SurfaceView viewFinder = viewFinderRef.get();
        if (viewFinder == null) {
            Log.e(TAG, "SurfaceView has been garbage collected");
            return;
        }

        SurfaceHolder surfaceHolder = viewFinder.getHolder();
        if (surfaceHolder == null || surfaceHolder.getSurface() == null || !surfaceHolder.getSurface().isValid()) {
            Log.e(TAG, "Surface holder is null or invalid");
            return;
        }

        try {
            if (cameraDevice == null) {
                Log.e(TAG, "Cannot create preview, camera device is null");
                return;
            }

            // Create a preview request
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surfaceHolder.getSurface());

            // Apply some settings for better preview
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            updateFlashMode();

            // Create the capture session
            List<Surface> outputSurfaces = new ArrayList<>(2);
            outputSurfaces.add(surfaceHolder.getSurface());
            if (imageReader != null) {
                outputSurfaces.add(imageReader.getSurface());
            }

            cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    cameraCaptureSession = session;
                    try {
                        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                        isCameraReady = true;
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Failed to start camera preview: " + e.getMessage());
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "Camera already closed: " + e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Camera capture session configuration failed");
                    runOnUiThread(() -> Toast.makeText(TextRecognitionActivity.this,
                            "Failed to configure camera", Toast.LENGTH_SHORT).show());
                    isCameraReady = false;
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access exception: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Error creating camera preview: " + e.getMessage());
        }
    }

    private void updateFlashMode() {
        if (captureRequestBuilder == null) {
            return;
        }

        try {
            if (shouldUseFlash) {
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            } else {
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            }

            if (cameraCaptureSession != null) {
                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
            }
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "Failed to update flash mode: " + e.getMessage());
        }
    }

    private void capturePhoto() {
        if (cameraDevice == null || !isCameraReady || isCapturing) {
            Log.e(TAG, "Cannot capture, camera not ready or already capturing");
            return;
        }

        isCapturing = true;

        try {
            // Show a toast message to inform user
            runOnUiThread(() -> Toast.makeText(TextRecognitionActivity.this,
                    "Capturing...", Toast.LENGTH_SHORT).show());

            // Set up the capture request
            final CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

            if (imageReader == null || imageReader.getSurface() == null) {
                setupImageReader();
                if (imageReader == null) {
                    Log.e(TAG, "Failed to setup image reader");
                    isCapturing = false;
                    return;
                }
            }

            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);

            // Use flash if necessary
            captureBuilder.set(CaptureRequest.FLASH_MODE,
                    shouldUseFlash ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);

            // Capture the image
            cameraCaptureSession.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Log.d(TAG, "Image captured successfully");
                    // Image will be processed in the ImageReader.OnImageAvailableListener
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                            @NonNull CaptureRequest request,
                                            @NonNull CaptureFailure result) {
                    super.onCaptureFailed(session, request, result);
                    isCapturing = false;
                    Log.e(TAG, "Image capture failed");
                    runOnUiThread(() -> Toast.makeText(TextRecognitionActivity.this,
                            "Failed to capture image", Toast.LENGTH_SHORT).show());
                }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            isCapturing = false;
            Log.e(TAG, "Camera access exception during capture: " + e.getMessage());
            runOnUiThread(() -> Toast.makeText(TextRecognitionActivity.this,
                    "Failed to capture image: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            isCapturing = false;
            Log.e(TAG, "Exception during capture: " + e.getMessage());
            runOnUiThread(() -> Toast.makeText(TextRecognitionActivity.this,
                    "Failed to capture image", Toast.LENGTH_SHORT).show());
        }
    }

    /**
     * Process the image captured from camera and run text recognition
     */
    private void processImage(Image image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes);

        try {
            // Create bitmap from byte array
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            final Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);

            if (bitmap == null) {
                Log.e(TAG, "Failed to decode image");
                isCapturing = false;
                runOnUiThread(() -> Toast.makeText(TextRecognitionActivity.this,
                        "Failed to process image", Toast.LENGTH_SHORT).show());
                return;
            }

            // Enhance the bitmap for better text recognition
            final Bitmap enhancedBitmap = enhanceBitmapForTextRecognition(bitmap);

            runOnUiThread(() -> Toast.makeText(TextRecognitionActivity.this,
                    "Processing text...", Toast.LENGTH_SHORT).show());

            final int rotation = getWindowManager().getDefaultDisplay().getRotation();

            // Run text recognition in a background thread
            imageProcessingExecutor.execute(() -> {
                try {
                    InputImage inputImage = InputImage.fromBitmap(enhancedBitmap, rotation * 90);

                    // Pass the actual bitmap dimensions to the overlay for proper scaling
                    final int bitmapWidth = enhancedBitmap.getWidth();
                    final int bitmapHeight = enhancedBitmap.getHeight();

                    // Make sure UI updates happen on the main thread
                    runOnUiThread(() -> {
                        try {
                            overlay.updateScaleFactors(bitmapWidth, bitmapHeight);

                            // Set the rotation in the bounding box view
                            overlay.setDeviceRotation(rotation * 90);
                        } catch (Exception e) {
                            Log.e(TAG, "Error updating overlay: " + e.getMessage());
                        }
                    });

                    // Process the image with ML Kit
                    textRecognizer.process(inputImage)
                            .addOnSuccessListener(visionText -> {
                                // Process results on the main thread
                                runOnUiThread(() -> {
                                    try {
                                        isCapturing = false;
                                        recognizedText = visionText.getText();

                                        if (recognizedText.isEmpty()) {
                                            textResult.setText("No text detected");
                                            overlay.clear(); // Using the new method name
                                            Toast.makeText(TextRecognitionActivity.this,
                                                    "No text detected", Toast.LENGTH_SHORT).show();

                                            // Disable buttons since no text was found
                                            copyButton.setEnabled(false);
                                            shareButton.setEnabled(false);
                                            return;
                                        }

                                        textResult.setText(recognizedText);

                                        // Create a list to store bounding boxes
                                        List<Rect> boundingBoxes = new ArrayList<>();

                                        // Process all text blocks and their elements
                                        for (Text.TextBlock block : visionText.getTextBlocks()) {
                                            Rect blockFrame = block.getBoundingBox();
                                            if (blockFrame != null) {
                                                boundingBoxes.add(blockFrame);
                                            }
                                        }

                                        // Use the new method name to set the bounding boxes
                                        overlay.setBoundingBoxes(boundingBoxes);

                                        // Enable buttons
                                        copyButton.setEnabled(true);
                                        shareButton.setEnabled(true);

                                        Toast.makeText(TextRecognitionActivity.this,
                                                "Text recognized successfully", Toast.LENGTH_SHORT).show();
                                    } catch (Exception e) {
                                        Log.e(TAG, "Error updating UI with text results: " + e.getMessage());
                                        Toast.makeText(TextRecognitionActivity.this,
                                                "Error displaying results", Toast.LENGTH_SHORT).show();
                                    }
                                });
                            })
                            .addOnFailureListener(e -> {
                                runOnUiThread(() -> {
                                    try {
                                        isCapturing = false;
                                        Log.e(TAG, "Text recognition failed: " + e.getMessage());
                                        textResult.setText("Text recognition failed");
                                        overlay.clear(); // Using the new method name
                                        Toast.makeText(TextRecognitionActivity.this,
                                                "Text recognition failed", Toast.LENGTH_SHORT).show();

                                        // Disable buttons on failure
                                        copyButton.setEnabled(false);
                                        shareButton.setEnabled(false);
                                    } catch (Exception ex) {
                                        Log.e(TAG, "Error updating UI after recognition failure: " + ex.getMessage());
                                    }
                                });
                            })
                            .addOnCompleteListener(task -> {
                                // Always make sure to recycle bitmaps to prevent memory leaks
                                enhancedBitmap.recycle();
                                bitmap.recycle();
                            });
                } catch (Exception e) {
                    // Handle any exceptions during the recognition process
                    Log.e(TAG, "Error in text recognition process: " + e.getMessage());
                    runOnUiThread(() -> {
                        isCapturing = false;
                        Toast.makeText(TextRecognitionActivity.this,
                                "Text recognition error", Toast.LENGTH_SHORT).show();
                    });

                    // Make sure to recycle bitmaps even in case of error
                    enhancedBitmap.recycle();
                    bitmap.recycle();
                }
            });

        } catch (Exception e) {
            isCapturing = false;
            Log.e(TAG, "Error processing image data: " + e.getMessage());
            runOnUiThread(() -> Toast.makeText(TextRecognitionActivity.this,
                    "Failed to process image", Toast.LENGTH_SHORT).show());
        }
    }

    /**
     * Enhance bitmap for better text recognition using image processing
     */
    private Bitmap enhanceBitmapForTextRecognition(Bitmap original) {
        if (original == null) return null;

        try {
            // Create a mutable copy that we can modify
            Bitmap enhanced = original.copy(Bitmap.Config.ARGB_8888, true);

            // Create canvas and set up paint for processing
            android.graphics.Canvas canvas = new android.graphics.Canvas(enhanced);
            android.graphics.Paint paint = new android.graphics.Paint();

            // Adjust contrast and brightness based on flash usage and estimated lighting
            float contrastFactor = shouldUseFlash ? 1.4f : 1.2f;
            int brightnessFactor = shouldUseFlash ? 20 : 10;

            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix(new float[] {
                    contrastFactor, 0, 0, 0, brightnessFactor,
                    0, contrastFactor, 0, 0, brightnessFactor,
                    0, 0, contrastFactor, 0, brightnessFactor,
                    0, 0, 0, 1, 0
            });

            // Apply color matrix to paint
            paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));

            // Draw using the enhanced filter
            canvas.drawBitmap(enhanced, 0, 0, paint);

            return enhanced;

        } catch (Exception e) {
            Log.e(TAG, "Error enhancing bitmap: " + e.getMessage());
            return original; // Return original if enhancement fails
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Get current rotation and update the bounding box overlay
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        if (overlay != null) {
            overlay.setDeviceRotation(rotation * 90);
        }

        // Recreate camera preview with new orientation
        if (cameraState == CameraState.OPENED && cameraCaptureSession != null) {
            closeCamera();
            // Give time for the camera to close properly
            backgroundHandler.postDelayed(() -> {
                openCamera();
            }, 500);
        }
    }

    private void copyToClipboard(String text) {
        if (text.isEmpty()) {
            Toast.makeText(this, "No text to copy", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("Recognized Text", text);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Text copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy to clipboard: " + e.getMessage());
            Toast.makeText(this, "Failed to copy text", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareText(String text) {
        if (text.isEmpty()) {
            Toast.makeText(this, "No text to share", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(shareIntent, "Share text via"));
        } catch (Exception e) {
            Log.e(TAG, "Failed to share text: " + e.getMessage());
            Toast.makeText(this, "Failed to share text", Toast.LENGTH_SHORT).show();
        }
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Failed to stop background thread: " + e.getMessage());
            }
        }
    }

    private void closeCamera() {
        try {
            cameraOpenCloseLock.acquire();
            cameraState = CameraState.CLOSING;

            if (cameraCaptureSession != null) {
                try {
                    cameraCaptureSession.stopRepeating();
                    cameraCaptureSession.close();
                } catch (Exception e) {
                    Log.w(TAG, "Exception closing capture session: " + e.getMessage());
                } finally {
                    cameraCaptureSession = null;
                }
            }

            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }

            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }

            isCameraReady = false;
            cameraState = CameraState.CLOSED;
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while closing camera: " + e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (textRecognizer == null) {
            // Re-initialize if necessary
            try {
                textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize text recognizer on resume: " + e.getMessage());
            }
        }

        startBackgroundThread();

        SurfaceView viewFinder = viewFinderRef.get();
        if (viewFinder != null && viewFinder.getHolder().getSurface().isValid()) {
            if (cameraState == CameraState.CLOSED) {
                openCamera();
            }
        }

        // Register light sensor listener
        if (sensorManager != null) {
            Sensor lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (lightSensor != null) {
                sensorManager.registerListener(
                        lightSensorListener,
                        lightSensor,
                        SensorManager.SENSOR_DELAY_NORMAL
                );
            }
        }

        // Set rotation in overlay based on device orientation
        if (overlay != null) {
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            overlay.setDeviceRotation(rotation * 90);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();

        // Unregister light sensor listener
        if (sensorManager != null) {
            sensorManager.unregisterListener(lightSensorListener);
        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (textRecognizer != null) {
            try {
                textRecognizer.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing text recognizer: " + e.getMessage());
            }
            textRecognizer = null;
        }
    }
}