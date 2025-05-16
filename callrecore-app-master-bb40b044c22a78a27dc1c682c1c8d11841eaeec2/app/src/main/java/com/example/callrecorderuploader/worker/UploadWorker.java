package com.example.callrecorderuploader.worker;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.callrecorderuploader.R; // Assuming you have this for string resources
import com.example.callrecorderuploader.service.FloatingWindowService; // Assuming this service exists

import org.json.JSONObject; // For parsing JSON response

import java.io.File;
import java.io.IOException;
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
    public static final String KEY_FILE_PATH = "key_file_path";
    public static final String KEY_PHONE_NUMBER = "key_phone_number"; // Kept from original, HTML doesn't explicitly send this with file

    // Updated UPLOAD_URL to match the HTML's endpoint
    private static final String UPLOAD_URL = "https://hideboot.jujia618.com/upload/audioRecord";
    private static final String UPLOAD_NOTIFICATION_CHANNEL_ID = "UploadNotificationChannel";
    private static final int UPLOAD_NOTIFICATION_ID_START = 20000; // Unique ID base for notifications

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
                        "File Uploads", // User-visible name
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Notifications for file upload status"); // User-visible description
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Shows or updates an upload notification.
     * @param fileName Name of the file being uploaded.
     * @param message The message to display in the notification.
     * @param isProgress Whether this is an ongoing progress notification.
     * @param progress Current progress (0-100) if isProgress is true.
     */
    @SuppressLint("NotificationPermission")
    private void showUploadNotification(String fileName, String message, boolean isProgress, int progress) {
        if (notificationManager == null) return;

        // Generate a unique ID for each file's notification to allow individual updates/cancellations
        int notificationId = UPLOAD_NOTIFICATION_ID_START + fileName.hashCode();

        // Using a string resource for title if available, otherwise fallback
        String contentTitle = getApplicationContext().getString(R.string.upload_notification_title_template, fileName);
        // Example R.string.upload_notification_title_template: "Uploading: %1$s"

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), UPLOAD_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher) // Replace with your app's icon
                .setContentTitle(contentTitle)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(isProgress) // Makes the notification non-dismissable if it's an ongoing progress
                .setAutoCancel(!isProgress); // Allows dismissal if it's a final status

        if (isProgress) {
            builder.setProgress(100, progress, false); // Show progress bar
        } else {
            builder.setProgress(0, 0, false); // Remove progress bar for final status
        }

        try {
            notificationManager.notify(notificationId, builder.build());
        } catch (Exception e) {
            Log.e(TAG, "Error showing notification for " + fileName + ": " + e.getMessage());
        }
    }

    /**
     * Removes the upload notification for a specific file.
     * @param fileName Name of the file whose notification should be removed.
     */
    private void removeUploadNotification(String fileName) {
        if (notificationManager == null) return;
        int notificationId = UPLOAD_NOTIFICATION_ID_START + fileName.hashCode();
        try {
            notificationManager.cancel(notificationId);
        } catch (Exception e) {
            Log.e(TAG, "Error removing notification for " + fileName + ": " + e.getMessage());
        }
    }

    /**
     * Manages the floating window display (specific to the example app).
     * @param show True to show, false to hide.
     * @param message Message to display in the floating window.
     */
    private void manageFloatingWindow(boolean show, String message) {
        Context context = getApplicationContext();
        // Check for overlay permission before attempting to show the window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission not granted. Floating window for '" + message + "' not shown by worker.");
            // Optionally, you could send a one-time notification to the user to grant permission.
            return;
        }
        Intent windowIntent = new Intent(context, FloatingWindowService.class); // Assuming FloatingWindowService is correctly implemented
        windowIntent.setAction(show ? FloatingWindowService.ACTION_SHOW : FloatingWindowService.ACTION_HIDE);
        if (show) {
            windowIntent.putExtra(FloatingWindowService.EXTRA_MESSAGE, message);
        }
        try {
            context.startService(windowIntent);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error starting/stopping FloatingWindowService (possibly due to background restrictions): " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Generic error with FloatingWindowService: " + e.getMessage());
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        String filePath = getInputData().getString(KEY_FILE_PATH);
        String phoneNumber = getInputData().getString(KEY_PHONE_NUMBER); // This might be optional or specific to your app's needs

        if (filePath == null || filePath.isEmpty()) {
            Log.e(TAG, "File path is null or empty.");
            return Result.failure();
        }

        File fileToUpload = new File(filePath);
        if (!fileToUpload.exists() || fileToUpload.length() == 0) {
            Log.e(TAG, "File does not exist or is empty: " + filePath);
            // No notification here as the file is invalid before any attempt
            return Result.failure();
        }

        String fileName = fileToUpload.getName();
        // Using string resource for uploading message
        String uploadingMessage = getApplicationContext().getString(R.string.uploading_file_message, fileName);
        // Example R.string.uploading_file_message: "Uploading ${fileName}..."

        Log.i(TAG, "Attempting to upload file: " + filePath + " for number: " + (phoneNumber != null ? phoneNumber : "N/A"));

        // Show initial "preparing" or "uploading" notification
        showUploadNotification(fileName, getApplicationContext().getString(R.string.status_upload_preparing), true, 0);
        manageFloatingWindow(true, uploadingMessage); // Show floating window if used

        // Configure OkHttpClient
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS) // Connection timeout
                .writeTimeout(60, TimeUnit.SECONDS)   // Write timeout
                .readTimeout(60, TimeUnit.SECONDS)    // Read timeout
                .build();

        Result resultStatus = Result.failure(); // Default to failure

        try {
            // Determine MIME type (the HTML uses accept="audio/*", here we infer)
            // You might want a more robust way to get MIME types
            String mediaTypeString = "application/octet-stream"; // Default MIME type
            if (fileName.toLowerCase().endsWith(".mp3")) mediaTypeString = "audio/mpeg";
            else if (fileName.toLowerCase().endsWith(".amr")) mediaTypeString = "audio/amr";
            else if (fileName.toLowerCase().endsWith(".wav")) mediaTypeString = "audio/wav";
            else if (fileName.toLowerCase().endsWith(".m4a")) mediaTypeString = "audio/mp4"; // m4a is mp4 container
            else if (fileName.toLowerCase().endsWith(".ogg")) mediaTypeString = "audio/ogg";
            // Add other audio types as needed

            RequestBody fileBody = RequestBody.create(fileToUpload, MediaType.parse(mediaTypeString));

            // Build the multipart request body
            // The HTML form sends the file with the name 'file'
            MultipartBody.Builder multipartBodyBuilder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName, fileBody);

            // Add optional parameters if they are indeed required by your server
            // The HTML example doesn't explicitly send these with the file,
            // but your server might expect them.
            if (phoneNumber != null && !phoneNumber.isEmpty()) {
                multipartBodyBuilder.addFormDataPart("phoneNumber", phoneNumber);
            }
            multipartBodyBuilder.addFormDataPart("uploadTime", String.valueOf(System.currentTimeMillis()));


            RequestBody requestBody = multipartBodyBuilder.build();

            // Create the request
            Request request = new Request.Builder()
                    .url(UPLOAD_URL)
                    .post(requestBody)
                    // Add any necessary headers here, e.g., Authorization
                    // .addHeader("Authorization", "Bearer YOUR_TOKEN")
                    .build();

            // Execute the request
            showUploadNotification(fileName, getApplicationContext().getString(R.string.status_uploading), true, 50); // Indicate active upload
            Response response = client.newCall(request).execute();
            ResponseBody responseBody = response.body();
            String responseBodyString = responseBody != null ? responseBody.string() : "No response body";

            // Process the response
            // The HTML checks for response.ok (which is 200-299) AND a "code: 200" in the JSON body.
            if (response.isSuccessful()) {
                try {
                    JSONObject jsonResponse = new JSONObject(responseBodyString);
                    int serverCode = jsonResponse.optInt("code", -1); // Default to -1 if 'code' not found
                    String serverMessage = jsonResponse.optString("message", "Unknown server message");

                    if (serverCode == 200) {
                        Log.i(TAG, "Upload successful for " + fileName + ". Server: " + serverMessage);
                        showUploadNotification(fileName, getApplicationContext().getString(R.string.status_upload_success), false, 0);
                        // Remove notification after a delay
                        new android.os.Handler(getApplicationContext().getMainLooper()).postDelayed(() -> removeUploadNotification(fileName), 7000);
                        resultStatus = Result.success();
                    } else {
                        Log.e(TAG, "Upload failed for " + fileName + " (server error). Server Code: " + serverCode + ", Message: " + serverMessage);
                        String failureMsg = getApplicationContext().getString(R.string.status_upload_failed_server, serverMessage);
                        showUploadNotification(fileName, failureMsg, false, 0);
                        resultStatus = Result.failure(); // Non-200 code from server logic is a failure
                    }
                } catch (Exception e) { //JSONException or other parsing errors
                    Log.e(TAG, "Error parsing server JSON response for " + fileName + ": " + responseBodyString, e);
                    String errorMsg = getApplicationContext().getString(R.string.status_upload_failed_response_parse_error, e.getMessage());
                    showUploadNotification(fileName, errorMsg, false, 0);
                    resultStatus = Result.failure();
                }
            } else {
                // HTTP error (not 2xx)
                Log.e(TAG, "Upload failed for " + fileName + " (HTTP error). Code: " + response.code() + ", Message: " + response.message());
                Log.e(TAG, "Server Response (if any): " + responseBodyString);
                String httpErrorMsg = getApplicationContext().getString(R.string.status_upload_failed_http_error, response.code(), response.message());
                showUploadNotification(fileName, httpErrorMsg, false, 0);
                // Retry for server-side errors (5xx), fail for client-side errors (4xx)
                resultStatus = (response.code() >= 500) ? Result.retry() : Result.failure();
            }

            if (responseBody != null) {
                responseBody.close();
            }

        } catch (IOException e) {
            Log.e(TAG, "IOException during upload for " + fileName, e);
            String ioErrorMsg = getApplicationContext().getString(R.string.status_upload_error_io, e.getMessage());
            showUploadNotification(fileName, ioErrorMsg, false, 0);
            resultStatus = Result.retry(); // IOException could be due to network issues, so retry
        } catch (Exception e) {
            // Catch-all for any other unexpected errors
            Log.e(TAG, "Unexpected exception during upload for " + fileName, e);
            String unexpectedErrorMsg = getApplicationContext().getString(R.string.status_upload_error_unknown, e.getMessage());
            showUploadNotification(fileName, unexpectedErrorMsg, false, 0);
            resultStatus = Result.failure();
        } finally {
            manageFloatingWindow(false, null); // Hide floating window
            // If not successful, the notification will persist until manually cleared or app is killed,
            // unless you add a timeout for failure notifications as well.
            if(resultStatus != Result.success()){
                // Optionally remove error notifications after a longer delay
                // new android.os.Handler(getApplicationContext().getMainLooper()).postDelayed(() -> removeUploadNotification(fileName), 30000);
            }
        }
        return resultStatus;
    }

    // It's good practice to define string resources in res/values/strings.xml
    // Example strings you would add to your strings.xml:
    /*
    <string name="upload_notification_title_template">Uploading: %1$s</string>
    <string name="uploading_file_message">Uploading %1$s...</string>
    <string name="status_upload_preparing">Preparing to upload...</string>
    <string name="status_uploading">Uploading...</string>
    <string name="status_upload_success">Upload successful!</string>
    <string name="status_upload_failed_server">Upload failed: %1$s</string>
    <string name="status_upload_failed_response_parse_error">Upload error (response): %1$s</string>
    <string name="status_upload_failed_http_error">Upload failed (HTTP %1$d): %2$s</string>
    <string name="status_upload_error_io">Upload error (Network): %1$s</string>
    <string name="status_upload_error_unknown">Upload error (Unknown): %1$s</string>
    */
}
