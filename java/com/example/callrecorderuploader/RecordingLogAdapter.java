package com.example.callrecorderuploader;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RecordingLogAdapter extends RecyclerView.Adapter<RecordingLogAdapter.ViewHolder> {

    private List<RecordingEntry> recordingEntries;
    private Context context;
    private OnManualUploadClickListener manualUploadClickListener;

    public interface OnManualUploadClickListener {
        void onManualUploadClick(RecordingEntry entry);
    }

    public RecordingLogAdapter(Context context, List<RecordingEntry> recordingEntries, OnManualUploadClickListener listener) {
        this.context = context;
        this.recordingEntries = recordingEntries;
        this.manualUploadClickListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_recording, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RecordingEntry entry = recordingEntries.get(position);
        holder.tvFileName.setText(entry.getFileName());

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        holder.tvTimestamp.setText("创建: " + sdf.format(new Date(entry.getCreationTimestamp())));
        holder.tvUploadStatus.setText("状态: " + entry.getUploadStatus());

        // 控制手动上传按钮的可见性
        if (entry.getUploadStatus() != null && (entry.getUploadStatus().toLowerCase().startsWith("failed") || entry.getUploadStatus().equalsIgnoreCase("pending"))) {
            holder.btnManualUpload.setVisibility(View.VISIBLE);
            holder.btnManualUpload.setOnClickListener(v -> {
                if (manualUploadClickListener != null) {
                    manualUploadClickListener.onManualUploadClick(entry);
                }
            });
        } else {
            holder.btnManualUpload.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return recordingEntries.size();
    }

    public void updateData(List<RecordingEntry> newEntries) {
        this.recordingEntries.clear();
        this.recordingEntries.addAll(newEntries);
        notifyDataSetChanged(); // Or use DiffUtil for better performance
    }

    public void updateEntryStatus(String filePath, String newStatus) {
        for (int i = 0; i < recordingEntries.size(); i++) {
            if (recordingEntries.get(i).getFilePath().equals(filePath)) {
                recordingEntries.get(i).setUploadStatus(newStatus);
                notifyItemChanged(i);
                return;
            }
        }
    }
     public void addEntry(RecordingEntry entry) {
        boolean exists = false;
        for (int i = 0; i < recordingEntries.size(); i++) {
            if (recordingEntries.get(i).getFilePath().equals(entry.getFilePath())) {
                recordingEntries.set(i, entry); // Update existing entry
                notifyItemChanged(i);
                exists = true;
                break;
            }
        }
        if (!exists) {
            recordingEntries.add(0, entry); // Add new entry at the top
            notifyItemInserted(0);
        }
    }


    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvFileName, tvTimestamp, tvUploadStatus;
        Button btnManualUpload;

        ViewHolder(View itemView) {
            super(itemView);
            tvFileName = itemView.findViewById(R.id.tvRecordingFileName);
            tvTimestamp = itemView.findViewById(R.id.tvRecordingTimestamp);
            tvUploadStatus = itemView.findViewById(R.id.tvRecordingUploadStatus);
            btnManualUpload = itemView.findViewById(R.id.btnManualUpload);
        }
    }
}