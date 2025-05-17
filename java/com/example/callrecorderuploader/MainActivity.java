package com.example.callrecorderuploader;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
// import androidx.lifecycle.Observer; // No longer explicitly needed if lambda is used for LiveData
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.example.callrecorderuploader.service.RecordingService;
import com.example.callrecorderuploader.worker.UploadWorker;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements RecordingLogAdapter.OnManualUploadClickListener {

    private static final String TAG = "MainActivity";
    private static final int PERMISSIONS_REQUEST_CODE = 100;
    private static final int OVERLAY_PERMISSION_REQUEST_CODE = 101;
    private static final int REQUEST_CODE_PICK_AUDIO = 102;

    private TextView tvStatus;
    private Button btnToggleService;
    private Button btnGrantOverlayPermission;
    private Button btnSelectAndUpload;
    private TextView tvAutoUploadServiceStatus;

    private RecyclerView rvRecordingLog;
    private RecordingLogAdapter recordingLogAdapter;
    private final List<RecordingEntry> recordingEntriesList = new ArrayList<>(); // 主数据列表

    private boolean wasOverlayPermissionGrantedPreviously = false;

    String[] permissionsBase = new String[]{
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
    };
    String[] permissionsStorageLegacy = new String[]{
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
    };
    String[] permissionsAndroid13AndUp = new String[]{
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate: Activity creating.");

        tvStatus = findViewById(R.id.tvStatus);
        btnToggleService = findViewById(R.id.btnToggleService);
        btnGrantOverlayPermission = findViewById(R.id.btnGrantOverlayPermission);
        btnSelectAndUpload = findViewById(R.id.btnSelectAndUpload);
        tvAutoUploadServiceStatus = findViewById(R.id.tvAutoUploadServiceStatus);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            wasOverlayPermissionGrantedPreviously = Settings.canDrawOverlays(this);
        }

        setupRecyclerView();

        btnToggleService.setOnClickListener(v -> {
            if (RecordingService.IS_SERVICE_RUNNING) {
                Toast.makeText(this, R.string.service_managed_auto_toast, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.service_start_auto_toast, Toast.LENGTH_LONG).show();
            }
            updateButtonState();
            updateAutoUploadServiceStatusText();
        });
        btnGrantOverlayPermission.setOnClickListener(v -> requestOverlayPermission());
        btnSelectAndUpload.setOnClickListener(v -> openAudioPicker());

        updateButtonState();
        updateOverlayPermissionButton();
        // updateAutoUploadServiceStatusText(); // Called in onResume and after permission checks

        // Permissions checked in onResume, which is always called after onCreate
        // WorkManager observation setup
        observeUploads();
        Log.d(TAG, "onCreate: Finished.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume: Activity resuming.");
        if (checkAndRequestPermissions()) {
            Log.d(TAG, "onResume: Base permissions granted or already available.");
            tvStatus.setText(getString(R.string.base_permissions_granted));
            checkOverlayPermission();
        } else {
            Log.d(TAG, "onResume: Base permissions not all granted, request initiated or pending.");
            // tvStatus text will be updated by onRequestPermissionsResult or if already denied.
        }
        updateButtonState();
        updateOverlayPermissionButton();
        updateAutoUploadServiceStatusText(); // Update status based on current state
        loadRecordingsAsync(); // Reload recordings
        Log.d(TAG, "onResume: Finished.");
    }

    private void setupRecyclerView() {
        rvRecordingLog = findViewById(R.id.rvRecordingLog);
        rvRecordingLog.setLayoutManager(new LinearLayoutManager(this));
        recordingLogAdapter = new RecordingLogAdapter(this, new ArrayList<>(), this); // Start with empty list
        rvRecordingLog.setAdapter(recordingLogAdapter);
        Log.d(TAG, "RecyclerView setup complete.");
    }

    private void loadRecordingsAsync() {
        Log.d(TAG, "loadRecordingsAsync: Starting to load recordings from disk.");
        new Thread(() -> {
            List<RecordingEntry> diskEntries = new ArrayList<>();
            File storageDir = getExternalFilesDir(Environment.DIRECTORY_RECORDINGS); // App-specific directory
            if (storageDir != null && storageDir.exists()) {
                File[] files = storageDir.listFiles();
                if (files != null) {
                    Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
                    for (File file : files) {
                        Log.d(TAG, "loadRecordingsAsync: Found file on disk: " + file.getName());
                        diskEntries.add(new RecordingEntry(file.getAbsolutePath(), file.getName(), file.lastModified(), getString(R.string.status_checking_status), null));
                    }
                } else {
                    Log.d(TAG, "loadRecordingsAsync: storageDir.listFiles() returned null.");
                }
            } else {
                Log.w(TAG, "loadRecordingsAsync: App-specific storage directory not found or doesn't exist: " + (storageDir != null ? storageDir.getAbsolutePath() : "null"));
            }

            new Handler(Looper.getMainLooper()).post(() -> {
                Log.d(TAG, "loadRecordingsAsync: Updating UI with " + diskEntries.size() + " entries from disk.");
                synchronized (recordingEntriesList) { // Synchronize access to the list
                    // Merge logic: Add disk entries if not already in memory by file path.
                    // Preserve existing entries in memory as they might have WorkRequest IDs or more current statuses.
                    for (RecordingEntry diskEntry : diskEntries) {
                        boolean foundInMemory = false;
                        for (RecordingEntry memEntry : recordingEntriesList) {
                            if (memEntry.getFilePath().equals(diskEntry.getFilePath())) {
                                foundInMemory = true;
                                break;
                            }
                        }
                        if (!foundInMemory) {
                            recordingEntriesList.add(diskEntry);
                            Log.d(TAG, "loadRecordingsAsync: Added new disk entry to list: " + diskEntry.getFileName());
                        }
                    }
                    Collections.sort(recordingEntriesList, (e1, e2) -> Long.compare(e2.getCreationTimestamp(), e1.getCreationTimestamp()));
                    recordingLogAdapter.updateData(new ArrayList<>(recordingEntriesList)); // Update adapter with a copy
                }
                if (recordingEntriesList.isEmpty()) {
                    Log.d(TAG, "loadRecordingsAsync: No recordings found after async load and merge.");
                }
                // After loading local files, the WorkManager observer will update their statuses.
            });
        }).start();
    }

    private void observeUploads() {
        Log.d(TAG, "observeUploads: Setting up WorkManager LiveData observer.");
        WorkManager.getInstance(this).getWorkInfosByTagLiveData("call_recording_upload")
                .observe(this, workInfos -> {
                    if (workInfos == null) {
                        Log.d(TAG, "observeUploads: WorkInfos list is null. No updates.");
                        return;
                    }
                    Log.d(TAG, "observeUploads: Received " + workInfos.size() + " WorkInfo updates.");

                    boolean isAnyWorkRunning = false;
                    Set<String> processedFilePathsInThisBatch = new HashSet<>();

                    synchronized (recordingEntriesList) { // Synchronize access to the list
                        for (WorkInfo workInfo : workInfos) {
                            String workInfoId = workInfo.getId().toString();
                            WorkInfo.State state = workInfo.getState();
                            String statusMessage;
                            RecordingEntry associatedEntry = null;

                            for (RecordingEntry entry : recordingEntriesList) {
                                if (entry.getWorkRequestId() != null && entry.getWorkRequestId().equals(workInfoId)) {
                                    associatedEntry = entry;
                                    break;
                                }
                            }

                            String filePathFromOutput = null;
                            if (state == WorkInfo.State.SUCCEEDED || state == WorkInfo.State.FAILED) {
                                filePathFromOutput = workInfo.getOutputData().getString(UploadWorker.KEY_FILE_PATH);
                                if (filePathFromOutput != null && associatedEntry == null) {
                                    for (RecordingEntry entry : recordingEntriesList) {
                                        if (entry.getFilePath().equals(filePathFromOutput)) {
                                            associatedEntry = entry;
                                            associatedEntry.setWorkRequestId(workInfoId);
                                            Log.d(TAG, "observeUploads: Matched WorkInfo " + workInfoId + " to entry via OutputData filePath: " + filePathFromOutput);
                                            break;
                                        }
                                    }
                                }
                            }

                            if (associatedEntry == null && filePathFromOutput != null && !processedFilePathsInThisBatch.contains(filePathFromOutput)) {
                                File f = new File(filePathFromOutput);
                                if (f.exists()) {
                                    Log.d(TAG, "observeUploads: Creating new RecordingEntry for file from WorkInfo OutputData (File exists). WorkID: " + workInfoId + ", File: " + filePathFromOutput + ", State: " + state);
                                    associatedEntry = new RecordingEntry(filePathFromOutput, f.getName(), f.lastModified(), "", workInfoId);
                                    recordingEntriesList.add(associatedEntry);
                                } else {
                                    Log.w(TAG, "observeUploads: File from WorkInfo OutputData does NOT exist, not creating entry. File: " + filePathFromOutput);
                                }
                            }

                            if (associatedEntry == null) {
                                // Log.d(TAG, "observeUploads: WorkInfo " + workInfoId + " (State: " + state + ") could not be matched or created for a list item.");
                                if (state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED) {
                                    isAnyWorkRunning = true;
                                }
                                continue;
                            }

                            if (processedFilePathsInThisBatch.contains(associatedEntry.getFilePath()) &&
                                    associatedEntry.getWorkRequestId() != null &&
                                    !associatedEntry.getWorkRequestId().equals(workInfoId)) {
                                Log.w(TAG, "observeUploads: File " + associatedEntry.getFilePath() + " already processed by WorkID " + associatedEntry.getWorkRequestId() + ". Ignoring update from different WorkID " + workInfoId);
                                continue;
                            }

                            switch (state) {
                                case ENQUEUED: statusMessage = getString(R.string.status_queued); break;
                                case RUNNING: statusMessage = getString(R.string.status_uploading); isAnyWorkRunning = true; break;
                                case SUCCEEDED:
                                    String successMsg = workInfo.getOutputData().getString(UploadWorker.OUTPUT_KEY_MESSAGE);
                                    statusMessage = getString(R.string.status_upload_success) + (successMsg != null ? ": " + successMsg : "!");
                                    break;
                                case FAILED:
                                    String errorMsg = workInfo.getOutputData().getString(UploadWorker.OUTPUT_KEY_ERROR);
                                    statusMessage = getString(R.string.status_upload_failed_generic) + (errorMsg != null ? ": " + errorMsg : ".");
                                    break;
                                case BLOCKED: statusMessage = getString(R.string.status_blocked); isAnyWorkRunning = true; break;
                                case CANCELLED: statusMessage = getString(R.string.status_cancelled); break;
                                default: statusMessage = getString(R.string.status_checking_status); break;
                            }

                            Log.d(TAG, "observeUploads: Updating Entry: File=" + associatedEntry.getFileName() + ", WorkID=" + workInfoId + ", NewState=" + state + ", StatusMsg='" + statusMessage + "'");
                            associatedEntry.setUploadStatus(statusMessage);
                            processedFilePathsInThisBatch.add(associatedEntry.getFilePath());
                        }

                        Collections.sort(recordingEntriesList, (e1, e2) -> Long.compare(e2.getCreationTimestamp(), e1.getCreationTimestamp()));
                        recordingLogAdapter.updateData(new ArrayList<>(recordingEntriesList));
                    } // end synchronized block

                    if (isAnyWorkRunning) {
                        tvAutoUploadServiceStatus.setText(getString(R.string.auto_upload_status_active));
                    } else {
                        updateAutoUploadServiceStatusText();
                    }
                });
        Log.d(TAG, "observeUploads: Observer setup complete.");
    }

    private void enqueueUploadRequest(String filePathOrUriString, String phoneNumberIdentifier, boolean isManualSelection) {
        Log.d(TAG, "enqueueUploadRequest: Queuing upload for: " + filePathOrUriString + ", Identifier: " + phoneNumberIdentifier);
        // File existence for direct paths is checked in UploadWorker.
        // For URIs, ContentResolver will handle existence.

        Data inputData = new Data.Builder()
                .putString(UploadWorker.KEY_FILE_PATH, filePathOrUriString)
                .putString(UploadWorker.KEY_PHONE_NUMBER, phoneNumberIdentifier)
                .build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest uploadWorkRequest = new OneTimeWorkRequest.Builder(UploadWorker.class)
                .setInputData(inputData)
                .addTag("call_recording_upload")
                .build();

        WorkManager.getInstance(getApplicationContext()).enqueue(uploadWorkRequest);
        String workId = uploadWorkRequest.getId().toString();
        Log.i(TAG, "enqueueUploadRequest: Enqueued WorkRequest with ID: " + workId + " for: " + filePathOrUriString);

        synchronized (recordingEntriesList) { // Synchronize access
            RecordingEntry entryToUpdate = null;
            // For URIs, filePathOrUriString is the Uri.toString(). We need to find by a more stable identifier if possible,
            // or create a new entry if this URI hasn't been seen.
            // If it's a direct file path, we can match by that.
            boolean isUri = filePathOrUriString.startsWith("content://"); // Simple check

            if (!isUri) { // If it's a file path, try to find existing entry
                for (RecordingEntry entry : recordingEntriesList) {
                    if (entry.getFilePath().equals(filePathOrUriString)) {
                        entryToUpdate = entry;
                        break;
                    }
                }
            }
            // If it's a URI or no existing entry found for a file path, we might create a new one.
            // However, for URIs, the actual file name might not be known until UploadWorker processes it.
            // For now, let's assume if an entry isn't found, a new one will be formed when WorkInfo comes back with OutputData.
            // Or, if we *know* this is a new file (e.g., from file picker), create it.

            String initialStatus = isManualSelection ? getString(R.string.status_queued_manual_selection) : getString(R.string.status_queued_manual_retry);
            String tempDisplayName = filePathOrUriString.substring(filePathOrUriString.lastIndexOf('/') + 1); // Simple display name

            if (entryToUpdate != null) { // Existing entry found (likely for a direct file path retry)
                Log.d(TAG, "enqueueUploadRequest: Updating existing entry for " + filePathOrUriString + " with new WorkID: " + workId);
                entryToUpdate.setWorkRequestId(workId);
                entryToUpdate.setUploadStatus(initialStatus);
            } else if (isManualSelection || isUri) { // New file from picker, or a URI that needs a new entry
                Log.d(TAG, "enqueueUploadRequest: Creating new entry for " + filePathOrUriString + " with WorkID: " + workId);
                // For URIs, actual filename/timestamp might be unknown here, use placeholder or get from URI if simple
                long lastModified = System.currentTimeMillis(); // Placeholder if actual not available
                if (!isUri) { // If it was a file path but not found, use its properties
                    File f = new File(filePathOrUriString);
                    if(f.exists()) lastModified = f.lastModified();
                }

                RecordingEntry newEntry = new RecordingEntry(
                        filePathOrUriString, // Store URI string or path
                        tempDisplayName,    // Temporary name
                        lastModified,
                        initialStatus,
                        workId
                );
                recordingEntriesList.add(0, newEntry);
            }
            Collections.sort(recordingEntriesList, (e1, e2) -> Long.compare(e2.getCreationTimestamp(), e1.getCreationTimestamp()));
            recordingLogAdapter.updateData(new ArrayList<>(recordingEntriesList));
        }
        Toast.makeText(this, getString(R.string.upload_request_queued, filePathOrUriString.substring(filePathOrUriString.lastIndexOf('/') + 1)), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onManualUploadClick(RecordingEntry entry) {
        Log.d(TAG, "onManualUploadClick for: " + entry.getFileName());
        enqueueUploadRequest(entry.getFilePath(), "ManualRetry-" + entry.getFileName(), false);
    }

    private void openAudioPicker() {
        Log.d(TAG, "openAudioPicker: Opening audio file picker.");
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMIUI%2Fsound_recorder%2Fcall_rec")); // Example, might not work reliably
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.choose_audio_file_title)), REQUEST_CODE_PICK_AUDIO);
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(this, R.string.file_manager_missing_toast, Toast.LENGTH_SHORT).show();
            Log.e(TAG, "openAudioPicker: ActivityNotFoundException - File manager likely missing.");
        }
    }

    private boolean checkAndRequestPermissions() {
        List<String> listPermissionsNeeded = new ArrayList<>();
        String[] currentPermissionsToRequest;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentPermissionsToRequest = permissionsAndroid13AndUp;
        } else {
            currentPermissionsToRequest = permissionsBase;
        }

        for (String perm : currentPermissionsToRequest) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(perm);
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { // Legacy storage for API < 29
            for (String perm : permissionsStorageLegacy) {
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    if (!listPermissionsNeeded.contains(perm)) listPermissionsNeeded.add(perm);
                }
            }
        } // For API 29 (Q), requestLegacyExternalStorage="true" in manifest is used.
        // For API 30+ (R+), requestLegacyExternalStorage is ignored. Scoped storage applies.
        // MANAGE_EXTERNAL_STORAGE would be needed for broad access on R+, not handled here.

        if (!listPermissionsNeeded.isEmpty()) {
            Log.i(TAG, "checkAndRequestPermissions: Requesting permissions: " + listPermissionsNeeded);
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[0]), PERMISSIONS_REQUEST_CODE);
            return false;
        }
        Log.i(TAG, "checkAndRequestPermissions: All necessary permissions are already granted.");
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.d(TAG, "onRequestPermissionsResult: requestCode=" + requestCode);
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            boolean allGranted = true;
            StringBuilder deniedPermissionsLog = new StringBuilder("Permissions Denied: ");
            StringBuilder grantedPermissionsLog = new StringBuilder("Permissions Granted: ");
            boolean someDenied = false;
            boolean someGranted = false;

            if (grantResults.length == 0 && permissions.length > 0) {
                Log.w(TAG, "onRequestPermissionsResult: grantResults is empty, user might have cancelled the dialog or system error.");
                allGranted = false;
                for (String perm : permissions) {
                    deniedPermissionsLog.append(perm.substring(perm.lastIndexOf('.')+1)).append("; "); // Short name
                    someDenied = true;
                }
            } else {
                for (int i = 0; i < grantResults.length; i++) {
                    String permName = (i < permissions.length) ? permissions[i] : "UnknownPermission";
                    String shortPermName = permName.substring(permName.lastIndexOf('.')+1);

                    if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                        allGranted = false;
                        deniedPermissionsLog.append(shortPermName).append("; ");
                        Log.w(TAG, "Permission DENIED: " + permName);
                        someDenied = true;
                    } else {
                        grantedPermissionsLog.append(shortPermName).append("; ");
                        Log.i(TAG, "Permission GRANTED: " + permName);
                        someGranted = true;
                    }
                }
            }

            if (allGranted) {
                tvStatus.setText(getString(R.string.base_permissions_granted));
                Log.i(TAG, "onRequestPermissionsResult: All requested permissions granted. " + (someGranted ? grantedPermissionsLog.toString() : ""));
                checkOverlayPermission();
            } else {
                String statusText = getString(R.string.some_permissions_denied);
                if (someDenied) {
                    statusText += "\n详情: " + deniedPermissionsLog.toString();
                    Log.w(TAG, "onRequestPermissionsResult: " + deniedPermissionsLog.toString());
                }
                if (someGranted) {
                    Log.i(TAG, "onRequestPermissionsResult: (but also " + grantedPermissionsLog.toString() + ")");
                }
                tvStatus.setText(statusText);
                Toast.makeText(this, getString(R.string.please_grant_all_permissions) + (someDenied ? " 特别是：" + deniedPermissionsLog.toString().trim() : ""), Toast.LENGTH_LONG).show();
            }
            updateButtonState();
        }
    }

    private void checkOverlayPermission() {
        Log.d(TAG, "checkOverlayPermission called.");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean canDrawOverlays = Settings.canDrawOverlays(this);
            // String currentStatusText = tvStatus.getText().toString(); // Avoid reading and appending repeatedly, set fresh text.
            StringBuilder newStatus = new StringBuilder();
            // Preserve base permission status if already set
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { // Example check
                newStatus.append(getString(R.string.base_permissions_granted));
            } else {
                newStatus.append(getString(R.string.please_grant_base_permissions));
            }

            if (canDrawOverlays) {
                newStatus.append("\n").append(getString(R.string.overlay_permission_granted_text_status));
            } else {
                newStatus.append("\n").append(getString(R.string.overlay_permission_not_granted_text_status));
            }
            tvStatus.setText(newStatus.toString());
            wasOverlayPermissionGrantedPreviously = canDrawOverlays;
        }
        updateOverlayPermissionButton();
    }

    private void requestOverlayPermission() {
        Log.d(TAG, "requestOverlayPermission called.");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                try {
                    startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE);
                } catch (Exception e) {
                    Log.e(TAG, "Could not open overlay settings: " + e.getMessage());
                    Toast.makeText(this, R.string.cannot_open_overlay_settings, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode);
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean canDrawOverlaysNow = Settings.canDrawOverlays(this);
                if (canDrawOverlaysNow && !wasOverlayPermissionGrantedPreviously) {
                    Toast.makeText(this, R.string.overlay_permission_granted_toast, Toast.LENGTH_SHORT).show();
                } else if (!canDrawOverlaysNow && wasOverlayPermissionGrantedPreviously) {
                    Toast.makeText(this, R.string.overlay_permission_revoked_toast, Toast.LENGTH_LONG).show();
                } else if (!canDrawOverlaysNow) {
                    Toast.makeText(this, R.string.overlay_permission_not_granted_toast, Toast.LENGTH_LONG).show();
                }
                // wasOverlayPermissionGrantedPreviously = canDrawOverlaysNow; // This is updated in checkOverlayPermission
                checkOverlayPermission(); // This updates text and button
            }
        } else if (requestCode == REQUEST_CODE_PICK_AUDIO && resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                Uri fileUri = data.getData();
                Log.i(TAG, "onActivityResult: File selected: " + fileUri.toString());
                enqueueUploadRequest(fileUri.toString(), "ManualSelection-" + System.currentTimeMillis(), true);
            } else {
                Log.w(TAG, "onActivityResult: File picker returned OK but data or URI is null.");
            }
        }
    }

    private void updateButtonState() {
        if (RecordingService.IS_SERVICE_RUNNING) {
            btnToggleService.setText(getString(R.string.service_running_auto_short));
        } else {
            btnToggleService.setText(getString(R.string.service_standby_auto_short));
        }
        Log.d(TAG,"updateButtonState: Service button text set to: " + btnToggleService.getText());
    }

    private void updateOverlayPermissionButton() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                btnGrantOverlayPermission.setText(getString(R.string.overlay_permission_granted_button));
                btnGrantOverlayPermission.setEnabled(false);
            } else {
                btnGrantOverlayPermission.setText(getString(R.string.grant_overlay_permission_button));
                btnGrantOverlayPermission.setEnabled(true);
            }
        } else {
            btnGrantOverlayPermission.setText(getString(R.string.overlay_permission_legacy_button));
            btnGrantOverlayPermission.setEnabled(false);
        }
        Log.d(TAG,"updateOverlayPermissionButton: Overlay button text set to: " + btnGrantOverlayPermission.getText() + ", enabled: " + btnGrantOverlayPermission.isEnabled());
    }

    private void updateAutoUploadServiceStatusText() {
        // This method should be non-blocking.
        // It primarily relies on the LiveData observer to reflect active uploads.
        boolean isAnyWorkTrulyActive = false;
        List<WorkInfo> currentWorkInfos = WorkManager.getInstance(this).getWorkInfosByTagLiveData("call_recording_upload").getValue();
        if (currentWorkInfos != null) {
            for (WorkInfo wi : currentWorkInfos) {
                WorkInfo.State state = wi.getState();
                if (state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED) {
                    isAnyWorkTrulyActive = true;
                    break;
                }
            }
        }

        if (isAnyWorkTrulyActive) {
            // The observeUploads callback will set this to "active" more reliably
            // tvAutoUploadServiceStatus.setText(getString(R.string.auto_upload_status_active));
        } else if (RecordingService.IS_SERVICE_RUNNING) {
            tvAutoUploadServiceStatus.setText(getString(R.string.service_running_auto_status));
        }
        else {
            tvAutoUploadServiceStatus.setText(getString(R.string.auto_upload_standby_status));
        }
        Log.d(TAG, "updateAutoUploadServiceStatusText: Status set to: " + tvAutoUploadServiceStatus.getText());
    }

    public static String getPathFromUri(final Context context, final Uri uri) {
        // (Keep the getPathFromUri and its helper methods as provided in the previous full MainActivity response)
        // ...
        final boolean isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
        if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
            if (isExternalStorageDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                if (split.length > 1 && "primary".equalsIgnoreCase(split[0])) {
                    return Environment.getExternalStorageDirectory() + "/" + split[1];
                }
            } else if (isDownloadsDocument(uri)) {
                final String id = DocumentsContract.getDocumentId(uri);
                if (id != null && id.startsWith("raw:/")) {
                    return id.substring(4);
                }
                if (id != null && !android.text.TextUtils.isDigitsOnly(id)) {
                    File checkIfPath = new File(id);
                    if(checkIfPath.exists()) return id;
                    String pathFromQuery = getDataColumn(context, uri, null, null);
                    if(pathFromQuery != null) return pathFromQuery;
                }
                try {
                    final Uri contentUri = ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"), Long.parseLong(id));
                    return getDataColumn(context, contentUri, null, null);
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Downloads ID is not a number: " + id + " for URI: " + uri);
                    return getDataColumn(context, uri, null, null);
                }
            } else if (isMediaDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                if (split.length > 1) {
                    final String type = split[0];
                    Uri contentUri = null;
                    if ("image".equals(type)) contentUri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    else if ("video".equals(type)) contentUri = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                    else if ("audio".equals(type)) contentUri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

                    if (contentUri != null) {
                        final String selection = "_id=?";
                        final String[] selectionArgs = new String[]{split[1]};
                        return getDataColumn(context, contentUri, selection, selectionArgs);
                    }
                }
            }
        } else if ("content".equalsIgnoreCase(uri.getScheme())) {
            return getDataColumn(context, uri, null, null);
        } else if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }
        return null;
    }

    public static String getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs) {
        Cursor cursor = null;
        final String column = "_data";
        final String[] projection = {column};
        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                final int columnIndex = cursor.getColumnIndex(column);
                if (columnIndex > -1) {
                    return cursor.getString(columnIndex);
                } else {
                    Log.e(TAG, "Column '_data' not found for URI: " + uri + " (projection: " + Arrays.toString(projection) + ")");
                }
            } else {
                // Log.d(TAG, "getDataColumn: Cursor is null or empty for URI: " + uri);
            }
        } catch (Exception e) {
            Log.e(TAG, "GetDataColumn: Exception for URI: " + uri, e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    public static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }
    public static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }
    public static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }
}