package com.example.callrecorderuploader.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.example.callrecorderuploader.MainActivity;
import com.example.callrecorderuploader.R;
import com.example.callrecorderuploader.worker.UploadWorker;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecordingService extends Service {
    private static final String TAG = "RecordingService";
    public static final String EXTRA_PHONE_NUMBER = "extra_phone_number";
    public static final String EXTRA_CALL_STATE = "extra_call_state";

    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private String currentFilePath;
    private String phoneNumber;

    private static final String CHANNEL_ID = "RecordingServiceChannel";
    private static final int NOTIFICATION_ID = 12345;
    public static boolean IS_SERVICE_RUNNING = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Log.d(TAG, "Service Created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand received");
        IS_SERVICE_RUNNING = true;

        Notification notification = createNotification("通话录音服务正在运行");
        startForeground(NOTIFICATION_ID, notification);

        if (intent != null) {
            String callState = intent.getStringExtra(EXTRA_CALL_STATE);
            phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER);
            if (phoneNumber == null) phoneNumber = "UnknownNumber";

            Log.d(TAG, "Call state: " + callState + ", Phone number: " + phoneNumber);

            if ("OFFHOOK".equals(callState)) {
                if (!isRecording) {
                    startRecording(phoneNumber);
                }
            } else if ("IDLE".equals(callState)) {
                if (isRecording) {
                    stopRecordingAndPrepareUpload();
                }
            }
        } else {
            Log.w(TAG, "Intent is null in onStartCommand.");
        }
        return START_STICKY;
    }

    private Notification createNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("通话录音服务")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            Notification notification = createNotification(text);
            notificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    private void startRecording(String number) {
        if (isRecording) {
            Log.w(TAG, "Already recording.");
            return;
        }
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String safeNumber = (number != null && !number.isEmpty()) ? number.replaceAll("[^a-zA-Z0-9.-]", "_") : "Unknown";
        String fileName = "CallRec_" + safeNumber + "_" + timeStamp + ".mp4";

        File storageDir = getExternalFilesDir(Environment.DIRECTORY_RECORDINGS);
        if (storageDir == null || (!storageDir.exists() && !storageDir.mkdirs())) {
            Log.e(TAG, "Failed to access/create recordings directory.");
            Toast.makeText(this, "无法访问/创建录音目录", Toast.LENGTH_SHORT).show();
            return;
        }
        currentFilePath = new File(storageDir, fileName).getAbsolutePath();
        Log.d(TAG, "Recording to file: " + currentFilePath);

        mediaRecorder = new MediaRecorder();
        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
        } catch (RuntimeException e) {
            Log.w(TAG, "VOICE_COMMUNICATION failed, falling back to MIC: " + e.getMessage());
            try {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            } catch (RuntimeException e2) {
                 Log.e(TAG, "Failed to set any audio source: " + e2.getMessage());
                 Toast.makeText(this, "无法设置录音源: " + e2.getMessage(), Toast.LENGTH_LONG).show();
                 cleanupMediaRecorder();
                 return;
            }
        }
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setOutputFile(currentFilePath);

        try {
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            Log.i(TAG, "Recording started.");
            Toast.makeText(this, "录音开始: " + fileName, Toast.LENGTH_SHORT).show();
            updateNotification("正在录音: " + ((phoneNumber != null && !phoneNumber.equals("UnknownNumber")) ? phoneNumber : "进行中"));
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "MediaRecorder prepare/start failed: " + e.getMessage());
            Toast.makeText(this, "录音启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            cleanupMediaRecorder();
        }
    }

    private void stopRecordingAndPrepareUpload() {
        if (!isRecording || mediaRecorder == null) {
            Log.w(TAG, "Not recording or mediaRecorder is null.");
            if (currentFilePath != null && new File(currentFilePath).exists() && new File(currentFilePath).length() > 0) {
                 Log.d(TAG, "File exists though not in recording state, scheduling upload: " + currentFilePath);
                 scheduleUploadWorker(currentFilePath, phoneNumber);
            }
            currentFilePath = null;
            isRecording = false;
            cleanupMediaRecorder();
            updateNotification("通话录音服务待命中");
            return;
        }
        try {
            mediaRecorder.stop();
            Log.i(TAG, "Recording stopped.");
        } catch (RuntimeException e) {
            Log.e(TAG, "MediaRecorder stop() failed: " + e.getMessage());
        } finally {
            isRecording = false;
            cleanupMediaRecorder();
            File recordedFile = (currentFilePath != null) ? new File(currentFilePath) : null;
            if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 0) {
                 Log.i(TAG, "File saved: " + currentFilePath + " (Size: " + recordedFile.length() + " bytes)");
                 Toast.makeText(this, "录音已保存: " + recordedFile.getName(), Toast.LENGTH_LONG).show();
                 updateNotification("录音已保存，准备上传...");
                 scheduleUploadWorker(currentFilePath, phoneNumber);
            } else {
                Log.w(TAG, "Recorded file invalid: " + currentFilePath);
                Toast.makeText(this, "录音文件无效", Toast.LENGTH_SHORT).show();
                updateNotification("录音失败或文件无效");
            }
            currentFilePath = null;
            updateNotification("通话录音服务待命中");
        }
    }

    private void cleanupMediaRecorder() {
        if (mediaRecorder != null) {
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
            Log.d(TAG, "MediaRecorder resources released.");
        }
    }

    private void scheduleUploadWorker(String filePath, String associatedNumber) {
        if (filePath == null || filePath.isEmpty()) {
            Log.e(TAG, "File path is null/empty for upload.");
            return;
        }
        Log.d(TAG, "Scheduling upload for: " + filePath);
        Data inputData = new Data.Builder()
                .putString(UploadWorker.KEY_FILE_PATH, filePath)
                .putString(UploadWorker.KEY_PHONE_NUMBER, associatedNumber != null ? associatedNumber : "Unknown")
                .build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest uploadWorkRequest = new OneTimeWorkRequest.Builder(UploadWorker.class)
                .setInputData(inputData)
                .setConstraints(constraints)
                .addTag("call_recording_upload")
                .build();
        WorkManager.getInstance(getApplicationContext()).enqueue(uploadWorkRequest);
        Log.i(TAG, "Upload task enqueued for: " + filePath);
        Toast.makeText(this, "文件已加入上传队列", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service Destroyed");
        if (isRecording) {
            Log.w(TAG, "Service destroyed while recording. Attempting to save.");
            stopRecordingAndPrepareUpload();
        } else {
            cleanupMediaRecorder();
        }
        IS_SERVICE_RUNNING = false;
        stopForeground(true);
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID, "Call Recording Service Channel", NotificationManager.IMPORTANCE_LOW);
            serviceChannel.setDescription("Channel for call recording foreground service");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
            else Log.e(TAG, "NotificationManager is null.");
        }
    }
}
