package com.smarthelp.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SmartHelp.Main";

    // UI Elements
    private MaterialButton btnStartStop;
    private TextView txtStatus;
    private View viewStatusDot;

    // State
    private boolean isServiceRunning = false;

    // Permission launchers
    private ActivityResultLauncher<Intent> overlayPermissionLauncher;
    private ActivityResultLauncher<Intent> screenCaptureLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private ActivityResultLauncher<String> audioPermissionLauncher;

    // Store the media projection result
    private int resultCode;
    private Intent resultData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "MainActivity created");

        initViews();
        setupPermissionLaunchers();
        setupClickListeners();
        updateUI();

        // Test connection to server
        new ApiClient().testConnection(new ApiClient.AnalysisCallback() {
            @Override
            public void onSuccess(ApiClient.GuidanceResponse response) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Server Connected!", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Connection Warning")
                            .setMessage("Could not connect to server at 192.168.0.154:3000.\n\nPlease ensure your computer's server is running and firewall is open.")
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    private void initViews() {
        btnStartStop = findViewById(R.id.btnStartStop);
        txtStatus = findViewById(R.id.txtStatus);
        viewStatusDot = findViewById(R.id.viewStatusDot);
    }

    private void setupPermissionLaunchers() {
        // Notification permission launcher (Android 13+)
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    Log.d(TAG, "Notification permission granted: " + isGranted);
                    // Continue regardless of result, notification will just not show
                    checkAudioPermission();
                }
        );

        // Audio permission launcher
        audioPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        Log.d(TAG, "Audio permission granted");
                    } else {
                        Log.w(TAG, "Audio permission denied");
                        Toast.makeText(this,
                                "Microphone permission is required for voice commands",
                                Toast.LENGTH_LONG).show();
                    }
                    // Always continue to next permission check
                    checkOverlayPermission();
                }
        );

        // Overlay permission launcher
        overlayPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (Settings.canDrawOverlays(this)) {
                        Log.d(TAG, "Overlay permission granted");
                        requestScreenCapturePermission();
                    } else {
                        Log.w(TAG, "Overlay permission denied");
                        Toast.makeText(this,
                                "Overlay permission is required for visual guidance",
                                Toast.LENGTH_LONG).show();
                    }
                }
        );

        // Screen capture permission launcher
        screenCaptureLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Log.d(TAG, "Screen capture result: " + result.getResultCode());
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        resultCode = result.getResultCode();
                        resultData = result.getData();
                        Log.d(TAG, "Screen capture permission granted");
                        startServices();
                    } else {
                        Log.w(TAG, "Screen capture permission denied");
                        Toast.makeText(this,
                                "Screen capture permission is required",
                                Toast.LENGTH_LONG).show();
                    }
                }
        );
    }

    private void setupClickListeners() {
        btnStartStop.setOnClickListener(v -> {
            Log.d(TAG, "Button clicked, isServiceRunning: " + isServiceRunning);
            if (isServiceRunning) {
                stopServices();
            } else {
                checkAndRequestPermissions();
            }
        });
    }

    private void checkAndRequestPermissions() {
        Log.d(TAG, "Checking permissions...");
        Toast.makeText(this, "Checking permissions...", Toast.LENGTH_SHORT).show();

        // Step 1: Check notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting notification permission");
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }

        checkAudioPermission();
    }

    private void checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Requesting audio permission");
            Toast.makeText(this, "Requesting audio permission...", Toast.LENGTH_SHORT).show();
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }

        checkOverlayPermission();
    }

    private void checkOverlayPermission() {
        // Step 2: Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Overlay permission not granted, showing dialog");
            Toast.makeText(this, "Please allow overlay permission", Toast.LENGTH_SHORT).show();
            showOverlayPermissionDialog();
            return;
        }

        Log.d(TAG, "All permissions granted, requesting screen capture");
        requestScreenCapturePermission();
    }

    private void showOverlayPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_overlay_title)
                .setMessage(R.string.permission_overlay_message)
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    Intent intent = new Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())
                    );
                    overlayPermissionLauncher.launch(intent);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void requestScreenCapturePermission() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_screen_title)
                .setMessage(R.string.permission_screen_message)
                .setPositiveButton("Allow", (dialog, which) -> {
                    try {
                        MediaProjectionManager projectionManager =
                                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent());
                    } catch (Exception e) {
                        Log.e(TAG, "Error requesting screen capture", e);
                        Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startServices() {
        Log.d(TAG, "Starting services...");
        updateStatus("Connecting...", R.color.warning);

        try {
            // Start the overlay service first (it doesn't need foreground)
            Intent overlayIntent = new Intent(this, OverlayService.class);
            startService(overlayIntent);
            Log.d(TAG, "OverlayService started");

            // Start the screen capture service
            Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
            serviceIntent.putExtra("resultCode", resultCode);
            serviceIntent.putExtra("resultData", resultData);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.d(TAG, "ScreenCaptureService started");

            isServiceRunning = true;
            updateUI();

            // Minimize the app so user can see other apps
            Toast.makeText(this, "SmartHelp+ is now active. Open any app!", Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Log.e(TAG, "Error starting services", e);
            Toast.makeText(this, "Error starting service: " + e.getMessage(), Toast.LENGTH_LONG).show();
            isServiceRunning = false;
            updateUI();
        }
    }

    private void stopServices() {
        Log.d(TAG, "Stopping services...");

        try {
            // Stop screen capture service
            Intent screenIntent = new Intent(this, ScreenCaptureService.class);
            stopService(screenIntent);

            // Stop overlay service
            Intent overlayIntent = new Intent(this, OverlayService.class);
            stopService(overlayIntent);

            Log.d(TAG, "Services stopped");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping services", e);
        }

        isServiceRunning = false;
        updateUI();
    }

    private void updateUI() {
        if (isServiceRunning) {
            btnStartStop.setText(R.string.btn_stop_help);
            btnStartStop.setIconResource(android.R.drawable.ic_media_pause);
            updateStatus(getString(R.string.status_active), R.color.success);
        } else {
            btnStartStop.setText(R.string.btn_start_help);
            btnStartStop.setIconResource(android.R.drawable.ic_media_play);
            updateStatus(getString(R.string.status_ready), R.color.success);
        }
    }

    private void updateStatus(String status, int colorResId) {
        txtStatus.setText(status);
        viewStatusDot.setBackgroundTintList(
                getResources().getColorStateList(colorResId, getTheme())
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "MainActivity destroyed");
        // Don't stop services here - let them run in background
    }
}
