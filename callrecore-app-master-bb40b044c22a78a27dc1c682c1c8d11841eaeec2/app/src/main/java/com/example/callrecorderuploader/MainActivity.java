package com.example.callrecorderuploader;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

import com.example.callrecorderuploader.service.RecordingService;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSIONS_REQUEST_CODE = 100;
    private static final int OVERLAY_PERMISSION_REQUEST_CODE = 101;
    private TextView tvStatus;
    private Button btnToggleService;
    private Button btnGrantOverlayPermission;

    String[] permissions = new String[]{
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        btnToggleService = findViewById(R.id.btnToggleService);
        btnGrantOverlayPermission = findViewById(R.id.btnGrantOverlayPermission);

        if (checkAndRequestPermissions()) {
            tvStatus.setText("基本权限已授予。");
            checkOverlayPermission();
        } else {
            tvStatus.setText("请授予基本权限。");
        }

        btnToggleService.setOnClickListener(v -> {
            if (RecordingService.IS_SERVICE_RUNNING) {
                Toast.makeText(this, "服务由电话状态自动管理。", Toast.LENGTH_LONG).show();
            } else {
                 Toast.makeText(this, "服务将由电话呼叫自动启动。", Toast.LENGTH_LONG).show();
            }
            updateButtonState();
        });

        btnGrantOverlayPermission.setOnClickListener(v -> {
            requestOverlayPermission();
        });

        updateButtonState();
        updateOverlayPermissionButton();
    }

    private void updateButtonState() {
        if (RecordingService.IS_SERVICE_RUNNING) {
            btnToggleService.setText("服务运行中 (自动)");
        } else {
            btnToggleService.setText("服务待命 (自动)");
        }
    }

    private void updateOverlayPermissionButton() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                btnGrantOverlayPermission.setText("悬浮窗权限已授予");
                btnGrantOverlayPermission.setEnabled(false);
            } else {
                btnGrantOverlayPermission.setText("授予悬浮窗权限");
                btnGrantOverlayPermission.setEnabled(true);
            }
        } else {
            btnGrantOverlayPermission.setText("悬浮窗权限 (API < 23)");
            btnGrantOverlayPermission.setEnabled(false);
        }
    }

    private boolean checkAndRequestPermissions() {
        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String perm : permissions) {
            if ((perm.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE) || perm.equals(Manifest.permission.READ_EXTERNAL_STORAGE))
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                continue;
            }
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(perm);
            }
        }
        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[0]), PERMISSIONS_REQUEST_CODE);
            return false;
        }
        return true;
    }

    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                tvStatus.append("\n悬浮窗权限未授予。");
            } else {
                 tvStatus.append("\n悬浮窗权限已授予。");
            }
        }
        updateOverlayPermissionButton();
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                try {
                    startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE);
                } catch (Exception e) {
                    Log.e(TAG, "Could not open overlay settings: " + e.getMessage());
                    Toast.makeText(this, "无法打开悬浮窗设置界面", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "悬浮窗权限已授予。", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            boolean allBasePermissionsGranted = true;
            for (int i = 0; i < grantResults.length; i++) {
                String currentPerm = permissions[i];
                 if ((currentPerm.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE) || currentPerm.equals(Manifest.permission.READ_EXTERNAL_STORAGE))
                        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    continue;
                }
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allBasePermissionsGranted = false;
                    Log.w(TAG, "Permission denied: " + currentPerm);
                }
            }

            if (allBasePermissionsGranted) {
                tvStatus.setText("基本权限已授予。");
                checkOverlayPermission();
            } else {
                tvStatus.setText("部分或全部基本权限被拒绝。");
                Toast.makeText(this, "请授予所有必要权限。", Toast.LENGTH_LONG).show();
            }
            updateButtonState();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    tvStatus.append("\n悬浮窗权限已授予。");
                    Toast.makeText(this, "悬浮窗权限授予成功！", Toast.LENGTH_SHORT).show();
                } else {
                    tvStatus.append("\n悬浮窗权限仍未授予。");
                    Toast.makeText(this, "悬浮窗权限未授予。", Toast.LENGTH_LONG).show();
                }
                updateOverlayPermissionButton();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateButtonState();
        if (checkAndRequestPermissions()) {
             checkOverlayPermission();
        }
        updateOverlayPermissionButton();
    }
}
