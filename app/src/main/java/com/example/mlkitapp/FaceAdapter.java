package com.example.mlkitapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.mlkit.vision.face.Face;

import java.util.ArrayList;
import java.util.List;

public class FaceAdapter extends RecyclerView.Adapter<FaceAdapter.FaceViewHolder> {
    
    private List<Face> faceList = new ArrayList<>();
    
    public void updateFaces(List<Face> faces) {
        this.faceList = new ArrayList<>(faces);
        notifyDataSetChanged();
    }
    
    @NonNull
    @Override
    public FaceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_face, parent, false);
        return new FaceViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull FaceViewHolder holder, int position) {
        Face face = faceList.get(position);
        holder.bind(face, position);
    }
    
    @Override
    public int getItemCount() {
        return faceList.size();
    }
    
    static class FaceViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvFaceId;
        private final TextView tvSmileProbability;
        private final TextView tvRightEyeOpen;
        private final TextView tvLeftEyeOpen;
        
        public FaceViewHolder(@NonNull View itemView) {
            super(itemView);
            tvFaceId = itemView.findViewById(R.id.tv_face_id);
            tvSmileProbability = itemView.findViewById(R.id.tv_smile_probability);
            tvRightEyeOpen = itemView.findViewById(R.id.tv_right_eye_open);
            tvLeftEyeOpen = itemView.findViewById(R.id.tv_left_eye_open);
        }
        
        public void bind(Face face, int position) {
            // Set face ID (position-based) or tracking ID if available
            Integer trackingId = face.getTrackingId();
            if (trackingId != null) {
                tvFaceId.setText("Face #" + trackingId);
            } else {
                tvFaceId.setText("Face #" + (position + 1));
            }
            
            // Smile probability
            if (face.getSmilingProbability() != null) {
                float smileProb = face.getSmilingProbability() * 100;
                tvSmileProbability.setText(String.format("Smile probability: %.1f%%", smileProb));
            } else {
                tvSmileProbability.setText("Smile probability: --");
            }
            
            // Right eye open probability
            if (face.getRightEyeOpenProbability() != null) {
                float rightEyeProb = face.getRightEyeOpenProbability() * 100;
                tvRightEyeOpen.setText(String.format("Right eye open: %.1f%%", rightEyeProb));
            } else {
                tvRightEyeOpen.setText("Right eye open: --");
            }
            
            // Left eye open probability
            if (face.getLeftEyeOpenProbability() != null) {
                float leftEyeProb = face.getLeftEyeOpenProbability() * 100;
                tvLeftEyeOpen.setText(String.format("Left eye open: %.1f%%", leftEyeProb));
            } else {
                tvLeftEyeOpen.setText("Left eye open: --");
            }
        }
    }
}