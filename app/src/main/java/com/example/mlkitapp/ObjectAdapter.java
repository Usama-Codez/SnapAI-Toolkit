package com.example.mlkitapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.mlkit.vision.objects.DetectedObject;

import java.util.ArrayList;
import java.util.List;

public class ObjectAdapter extends RecyclerView.Adapter<ObjectAdapter.ObjectViewHolder> {
    
    private List<DetectedObjectItem> objectList = new ArrayList<>();
    
    public static class DetectedObjectItem {
        String label;
        float confidence;
        
        public DetectedObjectItem(String label, float confidence) {
            this.label = label;
            this.confidence = confidence;
        }
    }
    
    public void updateObjects(List<DetectedObject> detectedObjects) {
        objectList.clear();
        
        for (DetectedObject object : detectedObjects) {
            // Get the label with highest confidence for each object
            String labelText = "Unknown";
            float confidence = 0f;
            
            List<DetectedObject.Label> labels = object.getLabels();
            if (labels != null && !labels.isEmpty()) {
                for (DetectedObject.Label label : labels) {
                    if (label.getConfidence() > confidence) {
                        confidence = label.getConfidence();
                        String text = label.getText();
                        if (text != null && !text.isEmpty()) {
                            labelText = text;
                        }
                    }
                }
            }
            
            objectList.add(new DetectedObjectItem(labelText, confidence));
        }
        
        notifyDataSetChanged();
    }
    
    @NonNull
    @Override
    public ObjectViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_detected_object, parent, false);
        return new ObjectViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull ObjectViewHolder holder, int position) {
        DetectedObjectItem item = objectList.get(position);
        holder.bind(item);
    }
    
    @Override
    public int getItemCount() {
        return objectList.size();
    }
    
    static class ObjectViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvObjectName;
        private final TextView tvConfidence;
        
        public ObjectViewHolder(@NonNull View itemView) {
            super(itemView);
            tvObjectName = itemView.findViewById(R.id.tv_object_name);
            tvConfidence = itemView.findViewById(R.id.tv_confidence);
        }
        
        public void bind(DetectedObjectItem item) {
            tvObjectName.setText(item.label);
            tvConfidence.setText(String.format("%.1f%%", item.confidence * 100));
        }
    }
}