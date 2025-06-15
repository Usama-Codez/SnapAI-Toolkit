package com.example.mlkitapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceLandmark;

import java.util.ArrayList;
import java.util.List;

public class FaceDetectionOverlay extends View {
    private List<Face> faces = new ArrayList<>();
    private boolean showLandmarks = false;
    private final Paint boundsPaint;
    private final Paint landmarkPaint;
    private int previewWidth;
    private int previewHeight;
    private boolean isFrontFacing = true;
    private boolean isAnalyzing = true;

    public FaceDetectionOverlay(Context context) {
        this(context, null);
    }

    public FaceDetectionOverlay(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FaceDetectionOverlay(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        boundsPaint = new Paint();
        boundsPaint.setColor(Color.GREEN);
        boundsPaint.setStyle(Paint.Style.STROKE);
        boundsPaint.setStrokeWidth(4.0f);

        landmarkPaint = new Paint();
        landmarkPaint.setColor(Color.RED);
        landmarkPaint.setStyle(Paint.Style.FILL);
        landmarkPaint.setStrokeWidth(8.0f);
    }

    public void updateFaces(List<Face> faces, int previewWidth, int previewHeight, boolean isFrontFacing) {
        this.faces = faces;
        this.previewWidth = previewWidth;
        this.previewHeight = previewHeight;
        this.isFrontFacing = isFrontFacing;
        this.isAnalyzing = true;
        invalidate();
    }

    public void setShowLandmarks(boolean showLandmarks) {
        this.showLandmarks = showLandmarks;
        invalidate();
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

        if (!isAnalyzing || faces == null || faces.isEmpty()) return;

        float scaleX = (float) getWidth() / previewWidth;
        float scaleY = (float) getHeight() / previewHeight;

        for (Face face : faces) {
            // Draw face bounding box
            Rect bounds = face.getBoundingBox();
            float left = translateX(bounds.left, scaleX);
            float top = translateY(bounds.top, scaleY);
            float right = translateX(bounds.right, scaleX);
            float bottom = translateY(bounds.bottom, scaleY);
            
            canvas.drawRect(left, top, right, bottom, boundsPaint);

            // Draw facial landmarks if enabled
            if (showLandmarks) {
                // Using correct int constants for FaceLandmark
                drawLandmark(canvas, face.getLandmark(FaceLandmark.LEFT_EYE), scaleX, scaleY);
                drawLandmark(canvas, face.getLandmark(FaceLandmark.RIGHT_EYE), scaleX, scaleY);
                drawLandmark(canvas, face.getLandmark(FaceLandmark.NOSE_BASE), scaleX, scaleY);
                drawLandmark(canvas, face.getLandmark(FaceLandmark.MOUTH_LEFT), scaleX, scaleY);
                drawLandmark(canvas, face.getLandmark(FaceLandmark.MOUTH_RIGHT), scaleX, scaleY);
                drawLandmark(canvas, face.getLandmark(FaceLandmark.LEFT_EAR), scaleX, scaleY);
                drawLandmark(canvas, face.getLandmark(FaceLandmark.RIGHT_EAR), scaleX, scaleY);
                drawLandmark(canvas, face.getLandmark(FaceLandmark.LEFT_CHEEK), scaleX, scaleY);
                drawLandmark(canvas, face.getLandmark(FaceLandmark.RIGHT_CHEEK), scaleX, scaleY);
            }
        }
    }

    private void drawLandmark(Canvas canvas, @Nullable FaceLandmark landmark, float scaleX, float scaleY) {
        if (landmark != null) {
            float x = translateX(landmark.getPosition().x, scaleX);
            float y = translateY(landmark.getPosition().y, scaleY);
            canvas.drawCircle(x, y, 8f, landmarkPaint);
        }
    }

    private float translateX(float x, float scaleX) {
        if (isFrontFacing) {
            return getWidth() - (x * scaleX);
        } else {
            return x * scaleX;
        }
    }

    private float translateY(float y, float scaleY) {
        return y * scaleY;
    }
}