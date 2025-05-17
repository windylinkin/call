package com.example.callrecorderuploader.worker;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns; // 用于从 Uri 获取文件名和大小
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.callrecorderuploader.R;
import com.example.callrecorderuploader.service.FloatingWindowService;

import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream; // 用于复制文件
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class UploadWorker extends Worker {
    private static final String TAG = "UploadWorker";
    public static final String KEY_FILE_PATH = "key_file_path"; // 可以是绝对路径或 Uri.toString()
    public static final String KEY_PHONE_NUMBER = "key_phone_number";

    private static final String UPLOAD_URL = "https://hideboot.jujia618.com/upload/audioRecord"; // 您的上传URL
    private static final String UPLOAD_NOTIFICATION_CHANNEL_ID = "UploadNotificationChannel";
    private static final int UPLOAD_NOTIFICATION_ID_START = 20000; // 确保这个ID的唯一性

    public static final String OUTPUT_KEY_MESSAGE = "message";
    public static final String OUTPUT_KEY_ERROR = "error";

    private NotificationManager notificationManager;

    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createUploadNotificationChannel(context);
    }

    private void createUploadNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager != null && notificationManager.getNotificationChannel(UPLOAD_NOTIFICATION_CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        UPLOAD_NOTIFICATION_CHANNEL_ID,
                        context.getString(R.string.upload_notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription(context.getString(R.string.upload_notification_channel_description));
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    @SuppressLint("NotificationPermission")
    private void showUploadNotification(String displayFileName, String message, boolean isProgress, int progress) {
        if (notificationManager == null) return;
        // 使用 displayFileName 的哈希码确保每个文件通知的唯一性（如果文件名可能重复但路径不同，则需要更唯一的ID）
        int notificationId = UPLOAD_NOTIFICATION_ID_START + displayFileName.hashCode();
        String contentTitle = getApplicationContext().getString(R.string.upload_notification_title_template, displayFileName);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), UPLOAD_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher) // 确保您有这个图标资源
                .setContentTitle(contentTitle)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(isProgress)
                .setAutoCancel(!isProgress);

        if (isProgress) {
            builder.setProgress(100, progress, false);
        } else {
            builder.setProgress(0, 0, false); // 移除进度条
        }

        try {
            notificationManager.notify(notificationId, builder.build());
        } catch (Exception e) {
            Log.e(TAG, "Error showing notification for " + displayFileName + ": " + e.getMessage());
        }
    }

    private void removeUploadNotification(String displayFileName) {
        if (notificationManager == null) return;
        int notificationId = UPLOAD_NOTIFICATION_ID_START + displayFileName.hashCode();
        try {
            notificationManager.cancel(notificationId);
        } catch (Exception e) {
            Log.e(TAG, "Error removing notification for " + displayFileName + ": " + e.getMessage());
        }
    }

    private void manageFloatingWindow(boolean show, String message) {
        Context context = getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context) && show) {
            Log.w(TAG, "Overlay permission not granted. Floating window for '" + message + "' not shown by worker.");
            return;
        }
        Intent windowIntent = new Intent(context, FloatingWindowService.class);
        windowIntent.setAction(show ? FloatingWindowService.ACTION_SHOW : FloatingWindowService.ACTION_HIDE);
        if (show) {
            windowIntent.putExtra(FloatingWindowService.EXTRA_MESSAGE, message != null ? message : context.getString(R.string.default_uploading_message));
        }
        try {
            context.startService(windowIntent);
        } catch (Exception e) { // 捕捉更通用的异常，包括 IllegalStateException
            Log.e(TAG, "Error starting/stopping FloatingWindowService: " + e.getMessage());
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        String originalInputPathOrUri = getInputData().getString(KEY_FILE_PATH);
        String phoneNumber = getInputData().getString(KEY_PHONE_NUMBER);
        Log.d(TAG, "UploadWorker: doWork() started for input: " + originalInputPathOrUri);

        if (originalInputPathOrUri == null || originalInputPathOrUri.isEmpty()) {
            Log.e(TAG, "File path/URI is null or empty.");
            Data outputData = new Data.Builder().putString(OUTPUT_KEY_ERROR, "File path/URI is null or empty.").build();
            return Result.failure(outputData);
        }

        File fileForUpload = null; // 将用于OkHttp RequestBody的最终File对象
        Uri uriInput = null;
        String displayFileName = "uploadfile"; // 用于通知和日志的显示文件名
        long fileSize = 0;
        boolean isTempFileUsed = false;

        // 1. 处理输入：可能是直接文件路径，也可能是Uri字符串
        if (originalInputPathOrUri.startsWith("content://") || originalInputPathOrUri.startsWith("file://")) {
            try {
                uriInput = Uri.parse(originalInputPathOrUri);
                // 从Uri获取元数据
                Cursor cursor = getApplicationContext().getContentResolver().query(uriInput, null, null, null, null);
                if (cursor != null) {
                    try {
                        if (cursor.moveToFirst()) {
                            int displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            if (displayNameIndex != -1) {
                                displayFileName = cursor.getString(displayNameIndex);
                            }
                            int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                            if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                                fileSize = cursor.getLong(sizeIndex);
                            }
                        }
                    } finally {
                        cursor.close();
                    }
                }
                // 如果上面的方法获取文件名失败，尝试备用方案
                if (displayFileName.equals("uploadfile") || displayFileName.isEmpty()) {
                    String lastSegment = uriInput.getLastPathSegment();
                    if (lastSegment != null && !lastSegment.isEmpty()){
                        // 对于 content://media/external/audio/media/123 这样的 URI， getLastPathSegment() 可能是数字ID
                        // 如果它包含文件名特征（如扩展名），可以使用它
                        if (lastSegment.contains(".")) displayFileName = lastSegment;
                        else displayFileName = lastSegment + "_" + System.currentTimeMillis(); // 如果只是ID，附加时间戳
                    }
                }
                Log.d(TAG, "Processing URI: " + uriInput + ", DisplayName: " + displayFileName + ", Size: " + fileSize);

            } catch (Exception e) {
                Log.e(TAG, "Error parsing URI or querying metadata: " + originalInputPathOrUri, e);
                Data outputData = new Data.Builder()
                        .putString(OUTPUT_KEY_ERROR, "Invalid URI or metadata query failed: " + e.getMessage())
                        .putString(KEY_FILE_PATH, originalInputPathOrUri)
                        .build();
                return Result.failure(outputData);
            }
        } else { // 假设是传统的绝对文件路径
            fileForUpload = new File(originalInputPathOrUri);
            displayFileName = fileForUpload.getName();
            if (!fileForUpload.exists()) {
                Log.e(TAG, "File (absolute path) does not exist: " + originalInputPathOrUri);
                Data outputData = new Data.Builder()
                        .putString(OUTPUT_KEY_ERROR, "File (absolute path) does not exist: " + displayFileName)
                        .putString(KEY_FILE_PATH, originalInputPathOrUri)
                        .build();
                return Result.failure(outputData);
            }
            fileSize = fileForUpload.length();
            Log.d(TAG, "Processing File Path: " + originalInputPathOrUri + ", DisplayName: " + displayFileName + ", Size: " + fileSize);
        }

        if (fileSize == 0 && uriInput == null) { // 如果是直接文件路径且大小为0
            Log.e(TAG, "File is empty (from direct path or URI size 0): " + originalInputPathOrUri);
            Data outputData = new Data.Builder()
                    .putString(OUTPUT_KEY_ERROR, "File is empty: " + displayFileName)
                    .putString(KEY_FILE_PATH, originalInputPathOrUri)
                    .build();
            return Result.failure(outputData);
        }
        // 对于Uri，fileSize 可能在某些情况下无法获取或为0，但内容仍然存在，所以继续处理

        // UI反馈
        String uploadingMessage = getApplicationContext().getString(R.string.uploading_specific_file, displayFileName);
        showUploadNotification(displayFileName, getApplicationContext().getString(R.string.status_upload_preparing), true, 0);
        manageFloatingWindow(true, uploadingMessage);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();

        Data outputDataOnError; // 用于错误情况
        RequestBody requestFileBody;

        try {
            // 2. 准备 RequestBody
            if (uriInput != null) { // 处理 Uri 输入
                InputStream inputStream = null;
                try {
                    inputStream = getApplicationContext().getContentResolver().openInputStream(uriInput);
                    if (inputStream == null) {
                        throw new FileNotFoundException("ContentResolver returned null InputStream for URI: " + uriInput);
                    }
                    // 复制到应用缓存文件
                    File tempCacheFile = new File(getApplicationContext().getCacheDir(), "upload_temp_" + System.currentTimeMillis() + "_" + displayFileName.replaceAll("[^a-zA-Z0-9._-]", "_"));
                    Log.d(TAG, "Copying URI content to temp cache file: " + tempCacheFile.getAbsolutePath());
                    try (OutputStream fos = new FileOutputStream(tempCacheFile)) {
                        byte[] buffer = new byte[4096];
                        int read;
                        long totalCopied = 0;
                        while ((read = inputStream.read(buffer)) != -1) {
                            fos.write(buffer, 0, read);
                            totalCopied += read;
                        }
                        Log.d(TAG, "Finished copying to temp cache file. Copied size: " + totalCopied);
                        if (totalCopied == 0 && fileSize != 0) { // 如果cursor报告了大小但没复制出内容
                            Log.w(TAG, "Copied 0 bytes from URI but cursor reported size: " + fileSize);
                        }
                        if (totalCopied == 0 && fileSize == 0) { // 如果都没获取到大小且没复制出内容
                            Log.e(TAG, "Copied 0 bytes from URI and no size info, assuming empty file: " + originalInputPathOrUri);
                            tempCacheFile.delete(); // 删除空的临时文件
                            throw new FileNotFoundException("URI content appears to be empty or unreadable.");
                        }
                    }
                    fileForUpload = tempCacheFile; // 现在用这个临时文件上传
                    isTempFileUsed = true;
                    Log.d(TAG, "Using temp cache file for upload: " + fileForUpload.getAbsolutePath());
                    requestFileBody = RequestBody.create(fileForUpload, MediaType.parse(determineMimeType(displayFileName)));

                } finally {
                    if (inputStream != null) {
                        try {
                            inputStream.close();
                        } catch (IOException e) {
                            Log.e(TAG, "Error closing input stream from URI", e);
                        }
                    }
                }
            } else if (fileForUpload != null) { // 处理直接文件路径输入 (应为应用专属目录)
                if (!fileForUpload.canRead()) {
                    Log.e(TAG, "Cannot read file (EACCES check): " + fileForUpload.getAbsolutePath());
                    outputDataOnError = new Data.Builder()
                            .putString(OUTPUT_KEY_ERROR, "Cannot read file (permissions): " + displayFileName)
                            .putString(KEY_FILE_PATH, originalInputPathOrUri)
                            .build();
                    throw new SecurityException("Permission denied for file: " + fileForUpload.getAbsolutePath()); // 抛出异常以便捕获
                }
                Log.d(TAG, "Using direct file path for upload: " + fileForUpload.getAbsolutePath());
                requestFileBody = RequestBody.create(fileForUpload, MediaType.parse(determineMimeType(displayFileName)));
            } else {
                Log.e(TAG, "Unexpected state: Neither URI nor direct file path was processed correctly.");
                throw new IllegalStateException("No valid file source (URI or path) for upload.");
            }

            // 3. 构建和执行网络请求
            MultipartBody.Builder multipartBodyBuilder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", displayFileName, requestFileBody); // 使用 displayFileName

            if (phoneNumber != null && !phoneNumber.isEmpty()) {
                multipartBodyBuilder.addFormDataPart("phoneNumber", phoneNumber);
            }
            multipartBodyBuilder.addFormDataPart("uploadTime", String.valueOf(System.currentTimeMillis()));
            RequestBody requestBody = multipartBodyBuilder.build();

            Request request = new Request.Builder()
                    .url(UPLOAD_URL)
                    .post(requestBody)
                    .build();

            Log.d(TAG, "Starting upload for: " + displayFileName);
            showUploadNotification(displayFileName, getApplicationContext().getString(R.string.status_uploading), true, 50);
            Response response = client.newCall(request).execute();
            ResponseBody responseBody = response.body();
            String responseBodyString = responseBody != null ? responseBody.string() : "No response body";

            if (response.isSuccessful()) {
                try {
                    JSONObject jsonResponse = new JSONObject(responseBodyString);
                    int serverCode = jsonResponse.optInt("code", -1);
                    String serverMessage = jsonResponse.optString("message", "Unknown server message");

                    if (serverCode == 200) {
                        Log.i(TAG, "Upload successful for " + displayFileName + ". Server: " + serverMessage);
                        showUploadNotification(displayFileName, getApplicationContext().getString(R.string.status_upload_success), false, 0);
                        String finalDisplayFileName = displayFileName;
                        new android.os.Handler(getApplicationContext().getMainLooper()).postDelayed(() -> removeUploadNotification(finalDisplayFileName), 7000);
                        Data outputData = new Data.Builder()
                                .putString(OUTPUT_KEY_MESSAGE, serverMessage)
                                .putString(KEY_FILE_PATH, originalInputPathOrUri) // 返回原始输入路径/URI
                                .build();
                        return Result.success(outputData);
                    } else {
                        String errorDetail = "Server error " + serverCode + ": " + serverMessage;
                        Log.e(TAG, "Upload failed (server logic error) for " + displayFileName + ". " + errorDetail);
                        showUploadNotification(displayFileName, getApplicationContext().getString(R.string.status_upload_failed_server, serverMessage), false, 0);
                        outputDataOnError = new Data.Builder()
                                .putString(OUTPUT_KEY_ERROR, errorDetail)
                                .putString(KEY_FILE_PATH, originalInputPathOrUri)
                                .build();
                        return Result.failure(outputDataOnError);
                    }
                } catch (Exception e) {
                    String errorDetail = "Response parse error: " + e.getMessage();
                    Log.e(TAG, "Error parsing server JSON response for " + displayFileName + ": " + responseBodyString, e);
                    showUploadNotification(displayFileName, getApplicationContext().getString(R.string.status_upload_failed_response_parse_error, e.getMessage()), false, 0);
                    outputDataOnError = new Data.Builder()
                            .putString(OUTPUT_KEY_ERROR, errorDetail)
                            .putString(KEY_FILE_PATH, originalInputPathOrUri)
                            .build();
                    return Result.failure(outputDataOnError);
                }
            } else {
                String errorDetail = "HTTP " + response.code() + ": " + response.message() + " - Body: " + responseBodyString;
                Log.e(TAG, "Upload failed (HTTP error) for " + displayFileName + ". " + errorDetail);
                showUploadNotification(displayFileName, getApplicationContext().getString(R.string.status_upload_failed_http_error, response.code(), response.message()), false, 0);
                outputDataOnError = new Data.Builder()
                        .putString(OUTPUT_KEY_ERROR, errorDetail)
                        .putString(KEY_FILE_PATH, originalInputPathOrUri)
                        .build();
                return (response.code() >= 500 && response.code() <=599) ? Result.retry() : Result.failure(outputDataOnError); // 仅对5xx错误重试
            }

        } catch (FileNotFoundException e) { // 包括之前为URI内容为空抛出的
            Log.e(TAG, "FileNotFoundException (or content unreadable) during upload for " + originalInputPathOrUri, e);
            outputDataOnError = new Data.Builder()
                    .putString(OUTPUT_KEY_ERROR, "File not found or content unreadable: " + e.getMessage())
                    .putString(KEY_FILE_PATH, originalInputPathOrUri)
                    .build();
            return Result.failure(outputDataOnError);
        } catch (SecurityException e) { // 捕获由 canRead() 失败抛出的异常
            Log.e(TAG, "SecurityException (permission denied) during upload for " + originalInputPathOrUri, e);
            outputDataOnError = new Data.Builder()
                    .putString(OUTPUT_KEY_ERROR, "Permission denied for file access: " + e.getMessage())
                    .putString(KEY_FILE_PATH, originalInputPathOrUri)
                    .build();
            return Result.failure(outputDataOnError);
        }
        catch (IOException e) {
            Log.e(TAG, "IOException during upload for " + originalInputPathOrUri, e);
            // 对于网络相关的IO异常，通常可以重试
            outputDataOnError = new Data.Builder() // 虽然retry不带data，但可以准备好以防逻辑变为failure
                    .putString(OUTPUT_KEY_ERROR, "Network IO error: " + e.getMessage())
                    .putString(KEY_FILE_PATH, originalInputPathOrUri)
                    .build();
            return Result.retry();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected exception during upload for " + originalInputPathOrUri, e);
            outputDataOnError = new Data.Builder()
                    .putString(OUTPUT_KEY_ERROR, "Unknown error during upload: " + e.getMessage())
                    .putString(KEY_FILE_PATH, originalInputPathOrUri)
                    .build();
            return Result.failure(outputDataOnError);
        } finally {
            manageFloatingWindow(false, null);
            if (isTempFileUsed && fileForUpload != null && fileForUpload.exists()) {
                Log.d(TAG, "Deleting temporary cache file: " + fileForUpload.getAbsolutePath());
                if (!fileForUpload.delete()) {
                    Log.w(TAG, "Failed to delete temporary upload file: " + fileForUpload.getAbsolutePath());
                }
            }
        }
    }

    private String determineMimeType(String fileName) {
        String lowerFileName = fileName.toLowerCase();
        if (lowerFileName.endsWith(".mp3")) return "audio/mpeg";
        if (lowerFileName.endsWith(".amr")) return "audio/amr";
        if (lowerFileName.endsWith(".wav")) return "audio/wav";
        if (lowerFileName.endsWith(".m4a") || lowerFileName.endsWith(".mp4")) return "audio/mp4";
        if (lowerFileName.endsWith(".ogg")) return "audio/ogg";
        // ... 其他类型
        return "application/octet-stream"; // 默认MIME类型
    }
}