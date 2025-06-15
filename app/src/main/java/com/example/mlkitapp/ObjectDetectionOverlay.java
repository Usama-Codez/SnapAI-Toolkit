package com.example.mlkitapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.mlkit.vision.objects.DetectedObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ObjectDetectionOverlay extends View {
    private List<DetectedObject> objects = new ArrayList<>();
    private final Paint boxPaint;
    private final Paint textBackgroundPaint;
    private final Paint textPaint;
    private final int[] colors = {
            Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN,
            Color.MAGENTA, Color.DKGRAY, Color.rgb(255, 128, 0)
    };
    private int previewWidth;
    private int previewHeight;
    private boolean isAnalyzing = true;
    private boolean isTrackingMode = false;
    private float confidenceThreshold = 0.5f;
    private boolean singleObjectMode = false;

    public ObjectDetectionOverlay(Context context) {
        this(context, null);
    }

    public ObjectDetectionOverlay(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ObjectDetectionOverlay(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        boxPaint = new Paint();
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(5.0f);
        
        textBackgroundPaint = new Paint();
        textBackgroundPaint.setStyle(Paint.Style.FILL);
        textBackgroundPaint.setColor(Color.BLACK);
        textBackgroundPaint.setAlpha(150);
        
        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(36.0f);
    }

    public void updateObjects(List<DetectedObject> objects, int previewWidth, int previewHeight) {
        if (singleObjectMode && objects.size() > 1) {
            // In single object mode, find the object with highest confidence
            DetectedObject bestObject = objects.get(0);
            float highestConfidence = 0f;
            
            for (DetectedObject object : objects) {
                for (DetectedObject.Label label : object.getLabels()) {
                    if (label.getConfidence() > highestConfidence) {
                        highestConfidence = label.getConfidence();
                        bestObject = object;
                    }
                }
            }
            
            this.objects = Arrays.asList(bestObject);
        } else {
            // Filter by confidence threshold
            List<DetectedObject> filteredObjects = new ArrayList<>();
            for (DetectedObject object : objects) {
                for (DetectedObject.Label label : object.getLabels()) {
                    if (label.getConfidence() >= confidenceThreshold) {
                        filteredObjects.add(object);
                        break;
                    }
                }
            }
            this.objects = filteredObjects;
        }
        
        this.previewWidth = previewWidth;
        this.previewHeight = previewHeight;
        invalidate();
    }

    public void setTrackingMode(boolean trackingMode) {
        this.isTrackingMode = trackingMode;
    }

    public void setConfidenceThreshold(float threshold) {
        this.confidenceThreshold = threshold;
    }

    public void setSingleObjectMode(boolean singleMode) {
        this.singleObjectMode = singleMode;
    }

    public void pauseAnalysis() {
        isAnalyzing = false;
    }

    public void resumeAnalysis() {
        isAnalyzing = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (!isAnalyzing || objects == null || objects.isEmpty()) return;

        float scaleX = (float) getWidth() / previewWidth;
        float scaleY = (float) getHeight() / previewHeight;

        for (int i = 0; i < objects.size(); i++) {
            DetectedObject object = objects.get(i);
            
            // Draw bounding box
            int colorIndex = i % colors.length;
            boxPaint.setColor(colors[colorIndex]);
            
            Rect bounds = object.getBoundingBox();
            float left = bounds.left * scaleX;
            float top = bounds.top * scaleY;
            float right = bounds.right * scaleX;
            float bottom = bounds.bottom * scaleY;
            
            canvas.drawRect(left, top, right, bottom, boxPaint);
            
            // Get the label with highest confidence
            String labelText = "Unknown";
            float confidence = 0f;
            
            for (DetectedObject.Label label : object.getLabels()) {
                if (label.getConfidence() > confidence) {
                    confidence = label.getConfidence();
                    labelText = label.getText();
                    if (labelText == null || labelText.isEmpty()) {
                        labelText = "Unknown";
                    }
                }
            }
            
            // Draw label with background
            String displayText = labelText + " " + String.format("%.1f%%", confidence * 100);
            float textWidth = textPaint.measureText(displayText);
            float textPadding = 8f;
            
            // Background for text
            canvas.drawRect(left, top - 40f, left + textWidth + 2 * textPadding, top, textBackgroundPaint);
            
            // Text itself
            canvas.drawText(displayText, left + textPadding, top - 10f, textPaint);
            
            // Draw tracking ID if available and tracking mode is on
            if (isTrackingMode && object.getTrackingId() != null) {
                String trackingId = "ID: " + object.getTrackingId();
                canvas.drawText(trackingId, left + textPadding, top - 50f, textPaint);
            }
        }
    }
}