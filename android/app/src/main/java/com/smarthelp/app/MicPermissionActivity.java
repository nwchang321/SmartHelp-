package com.smarthelp.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Transparent activity launched from OverlayService when the user taps the mic
 * but RECORD_AUDIO has not been granted. Runs the runtime permission dialog;
 * if the user permanently denied, falls back to the app settings page.
 */
public class MicPermissionActivity extends AppCompatActivity {

    private ActivityResultLauncher<String> micLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        micLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    boolean zh = "zh".equals(AppPrefs.getLanguage(this));
                    if (granted) {
                        Toast.makeText(this,
                                zh ? "麦克风已开启，请再次点击麦克风" : "Mic enabled — tap the mic again",
                                Toast.LENGTH_SHORT).show();
                    } else if (!ActivityCompat.shouldShowRequestPermissionRationale(
                            this, Manifest.permission.RECORD_AUDIO)) {
                        // Permanently denied — guide user to settings
                        showSettingsDialog();
                        return;
                    } else {
                        Toast.makeText(this,
                                zh ? "需要麦克风权限才能使用语音助手" : "Mic permission is required for voice help",
                                Toast.LENGTH_LONG).show();
                    }
                    finish();
                });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            finish();
            return;
        }

        micLauncher.launch(Manifest.permission.RECORD_AUDIO);
    }

    private void showSettingsDialog() {
        boolean zh = "zh".equals(AppPrefs.getLanguage(this));
        new AlertDialog.Builder(this)
                .setTitle(zh ? "开启麦克风权限" : "Enable Microphone")
                .setMessage(zh
                        ? "请到设置中开启麦克风权限，才能使用语音助手。"
                        : "Please enable the microphone permission in Settings to use voice help.")
                .setPositiveButton(zh ? "打开设置" : "Open Settings", (d, w) -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception ignored) {}
                    finish();
                })
                .setNegativeButton(zh ? "取消" : "Cancel", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }
}
