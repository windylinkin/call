package com.example.callrecorderuploader;

import java.util.Objects;

public class RecordingEntry {
    private String filePath;
    private String fileName;
    private long creationTimestamp;
    private String uploadStatus; // e.g., "Pending", "Uploading", "Success: [serverMsg]", "Failed: [errorMsg]"
    private String workRequestId; // To link with WorkManager's WorkInfo

    public RecordingEntry(String filePath, String fileName, long creationTimestamp, String uploadStatus, String workRequestId) {
        this.filePath = filePath;
        this.fileName = fileName;
        this.creationTimestamp = creationTimestamp;
        this.uploadStatus = uploadStatus;
        this.workRequestId = workRequestId;
    }

    // Getters and Setters
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public long getCreationTimestamp() { return creationTimestamp; }
    public void setCreationTimestamp(long creationTimestamp) { this.creationTimestamp = creationTimestamp; }
    public String getUploadStatus() { return uploadStatus; }
    public void setUploadStatus(String uploadStatus) { this.uploadStatus = uploadStatus; }
    public String getWorkRequestId() { return workRequestId; }
    public void setWorkRequestId(String workRequestId) { this.workRequestId = workRequestId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RecordingEntry that = (RecordingEntry) o;
        return Objects.equals(filePath, that.filePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filePath);
    }
}