package com.smarthelp.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {

    private RadioButton rbEnglish;
    private TextView txtPermMicStatus;
    private TextView txtPermOverlayStatus;
    private TextView txtPermNotificationStatus;
    private TextView txtPermissionsSummaryStatus;

    private TextView tvSettingsTitle;
    private TextView tvSettingsSubtitle;
    private TextView btnSettingsDone;
    private TextView tvSectionLanguage;
    private TextView tvLangEnglishName;
    private TextView tvLangEnglishSub;
    private TextView tvSectionPermissions;
    private TextView tvPermissionsSummaryLabel;
    private TextView tvPermissionsSummarySub;
    private TextView tvMicLabel;
    private TextView tvMicSub;
    private TextView tvOverlayLabel;
    private TextView tvOverlaySub;
    private TextView tvNotificationLabel;
    private TextView tvNotificationSub;
    private TextView tvSectionAbout;
    private TextView tvAboutAppLabel;
    private TextView tvAboutVersionLabel;
    private TextView tvAboutDeveloperLabel;
    private TextView tvAppVersion;

    // Help & Tutorial section
    private TextView tvSectionHelp;
    private TextView tvReplayOnboardingLabel;
    private TextView tvReplayOnboardingSub;
    private TextView tvHowToUseLabel;
    private TextView tvHowToUseSub;

    // Developer mode (hidden — 5-tap on version)
    private int devModeTapCount = 0;
    private boolean devModeEnabled = false;
    private long lastDevTapMs = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_settings);

        WindowInsetsControllerCompat wic = new WindowInsetsControllerCompat(
                getWindow(), getWindow().getDecorView());
        wic.setAppearanceLightStatusBars(true);
        wic.setAppearanceLightNavigationBars(true);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.layoutSettingsHero), (v, insets) -> {
                    int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
                    v.setPadding(
                            v.getPaddingLeft(),
                            statusBarHeight + dp(44),
                            v.getPaddingRight(),
                            v.getPaddingBottom());
                    return WindowInsetsCompat.CONSUMED;
                });

        bindViews();
        setupInteractions();
        applyLanguageUI(AppPrefs.getLanguage(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    private void bindViews() {
        ImageButton btnBack = findViewById(R.id.btnSettingsBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        tvSettingsTitle = findViewById(R.id.tvSettingsTitle);
        tvSettingsSubtitle = findViewById(R.id.tvSettingsSubtitle);
        btnSettingsDone = findViewById(R.id.btnSettingsDone);
        if (btnSettingsDone != null) btnSettingsDone.setOnClickListener(v -> saveAndFinish());

        tvSectionLanguage = findViewById(R.id.tvSectionLanguage);
        rbEnglish = findViewById(R.id.rbLangEnglish);
        tvLangEnglishName = findViewById(R.id.tvLangEnglishName);
        tvLangEnglishSub = findViewById(R.id.tvLangEnglishSub);

        txtPermMicStatus = findViewById(R.id.txtPermMicStatus);
        txtPermOverlayStatus = findViewById(R.id.txtPermOverlayStatus);
        txtPermNotificationStatus = findViewById(R.id.txtPermNotificationStatus);
        tvSectionPermissions = findViewById(R.id.tvSectionPermissions);
        tvPermissionsSummaryLabel = findViewById(R.id.tvPermissionsSummaryLabel);
        tvPermissionsSummarySub = findViewById(R.id.tvPermissionsSummarySub);
        txtPermissionsSummaryStatus = findViewById(R.id.txtPermissionsSummaryStatus);
        tvMicLabel = findViewById(R.id.tvMicLabel);
        tvMicSub = findViewById(R.id.tvMicSub);
        tvOverlayLabel = findViewById(R.id.tvOverlayLabel);
        tvOverlaySub = findViewById(R.id.tvOverlaySub);
        tvNotificationLabel = findViewById(R.id.tvNotificationLabel);
        tvNotificationSub = findViewById(R.id.tvNotificationSub);

        // Help & Tutorial section
        tvSectionHelp = findViewById(R.id.tvSectionHelp);
        tvReplayOnboardingLabel = findViewById(R.id.tvReplayOnboardingLabel);
        tvReplayOnboardingSub = findViewById(R.id.tvReplayOnboardingSub);
        tvHowToUseLabel = findViewById(R.id.tvHowToUseLabel);
        tvHowToUseSub = findViewById(R.id.tvHowToUseSub);

        tvSectionAbout = findViewById(R.id.tvSectionAbout);
        tvAboutAppLabel = findViewById(R.id.tvAboutAppLabel);
        tvAboutVersionLabel = findViewById(R.id.tvAboutVersionLabel);
        tvAboutDeveloperLabel = findViewById(R.id.tvAboutDeveloperLabel);
        tvAppVersion = findViewById(R.id.tvAppVersion);

        if (rbEnglish != null) rbEnglish.setChecked(true);

        if (tvAppVersion != null) {
            try {
                tvAppVersion.setText(getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
            } catch (Exception ignored) {
                tvAppVersion.setText("2.0.0");
            }
            tvAppVersion.setOnClickListener(v -> {
                long now = System.currentTimeMillis();
                if (now - lastDevTapMs > 2000) {
                    devModeTapCount = 0;
                }
                lastDevTapMs = now;
                devModeTapCount++;
                if (devModeTapCount >= 5) {
                    devModeTapCount = 0;
                    devModeEnabled = true;
                    boolean zh = "zh".equals(AppPrefs.getLanguage(this));
                    Toast.makeText(this,
                            zh ? "开发者模式已开启" : "Developer mode enabled",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

    }

    private void setupInteractions() {
        LinearLayout rowEn = findViewById(R.id.rowLangEnglish);
        if (rowEn != null) rowEn.setOnClickListener(v -> selectLanguage("en"));

        LinearLayout rowPermMic = findViewById(R.id.rowPermMic);
        LinearLayout rowPermOverlay = findViewById(R.id.rowPermOverlay);
        LinearLayout rowPermNotification = findViewById(R.id.rowPermNotification);
        LinearLayout rowPermissionsOverview = findViewById(R.id.rowPermissionsOverview);

        if (rowPermMic != null) {
            rowPermMic.setOnClickListener(v -> openAppPermissionSettings());
        }
        if (rowPermOverlay != null) {
            rowPermOverlay.setOnClickListener(v -> openOverlaySettings());
        }
        if (rowPermNotification != null) {
            rowPermNotification.setOnClickListener(v -> openNotificationSettings());
        }
        if (rowPermissionsOverview != null) {
            rowPermissionsOverview.setOnClickListener(v -> showPermissionsDialog());
        }

        // Help & Tutorial
        LinearLayout rowReplayOnboarding = findViewById(R.id.rowReplayOnboarding);
        LinearLayout rowHowToUse = findViewById(R.id.rowHowToUse);
        if (rowReplayOnboarding != null) {
            rowReplayOnboarding.setOnClickListener(v -> replayOnboarding());
        }
        if (rowHowToUse != null) {
            rowHowToUse.setOnClickListener(v -> showHowToUseDialog());
        }
    }

    private void selectLanguage(String lang) {
        String selected = "en";
        if (rbEnglish != null) rbEnglish.setChecked(true);
        AppPrefs.setLanguage(this, selected);
        applyLanguageUI(selected);

        Intent broadcast = new Intent("com.smarthelp.CHANGE_LANGUAGE");
        broadcast.setPackage(getPackageName());
        broadcast.putExtra("lang", selected);
        sendBroadcast(broadcast);
    }

    private void applyLanguageUI(String lang) {
        boolean zh = "zh".equals(lang);

        setText(tvSettingsTitle, zh ? "设置" : "Settings");
        setText(tvSettingsSubtitle, zh
                ? "常用设置放前面，开发者选项隐藏在版本号中"
                : "Essentials first, developer options hidden in version number");
        setText(btnSettingsDone, zh ? "完成" : "Done");

        setText(tvSectionLanguage, zh ? "常用设置" : "Experience");
        setText(tvLangEnglishName, zh ? "英文" : "English");
        setText(tvLangEnglishSub, zh ? "点击选择" : "Tap to select");

        setText(tvSectionPermissions, zh ? "权限" : "Permissions");
        setText(tvPermissionsSummaryLabel, zh ? "权限检查" : "Permission Check");
        setText(tvMicLabel, zh ? "麦克风" : "Microphone");
        setText(tvMicSub, zh ? "用于语音对话" : "For voice conversations");
        setText(tvOverlayLabel, zh ? "悬浮窗" : "Overlay");
        setText(tvOverlaySub, zh ? "用于显示引导高亮" : "For showing guidance highlights");
        setText(tvNotificationLabel, zh ? "通知" : "Notifications");
        setText(tvNotificationSub, zh ? "用于显示服务通知" : "For showing service notifications");

        setText(tvSectionHelp, zh ? "帮助" : "Help");
        setText(tvReplayOnboardingLabel, zh ? "重看新手引导" : "Replay Onboarding");
        setText(tvReplayOnboardingSub, zh ? "重新查看初次使用说明" : "Review the first-time tutorial again");
        setText(tvHowToUseLabel, zh ? "使用方法" : "How to Use");
        setText(tvHowToUseSub, zh ? "了解语音引导和视觉高亮" : "Learn about voice guidance and visual highlights");

        setText(tvSectionAbout, zh ? "关于" : "About");
        setText(tvAboutAppLabel, zh ? "应用名称" : "App Name");
        setText(tvAboutVersionLabel, zh ? "版本" : "Version");
        setText(tvAboutDeveloperLabel, zh ? "开发者" : "Developer");

        updatePermissionStatus();
    }

    private boolean isMicPermissionGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean canShowOverlay() {
        return Settings.canDrawOverlays(this);
    }

    private boolean areNotificationsEnabledForApp() {
        return NotificationManagerCompat.from(this).areNotificationsEnabled();
    }

    private void openAppPermissionSettings() {
        startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private void openOverlaySettings() {
        startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
    }

    private void openNotificationSettings() {
        Intent intent = new Intent();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
        }
        startActivity(intent);
    }

    private void updatePermissionStatus() {
        boolean zh = "zh".equals(AppPrefs.getLanguage(this));
        if (txtPermMicStatus != null) {
            boolean granted = isMicPermissionGranted();
            txtPermMicStatus.setText(granted
                    ? (zh ? "已授权 ✓" : "Granted ✓")
                    : (zh ? "未授权" : "Not granted"));
            txtPermMicStatus.setTextColor(granted ? 0xFF22C55E : 0xFFEF4444);
        }
        if (txtPermOverlayStatus != null) {
            boolean granted = canShowOverlay();
            txtPermOverlayStatus.setText(granted
                    ? (zh ? "已授权 ✓" : "Granted ✓")
                    : (zh ? "未授权" : "Not granted"));
            txtPermOverlayStatus.setTextColor(granted ? 0xFF22C55E : 0xFFEF4444);
        }
        if (txtPermNotificationStatus != null) {
            boolean granted = areNotificationsEnabledForApp();
            txtPermNotificationStatus.setText(granted
                    ? (zh ? "已授权 ✓" : "Granted ✓")
                    : (zh ? "未授权" : "Not granted"));
            txtPermNotificationStatus.setTextColor(granted ? 0xFF22C55E : 0xFFEF4444);
        }
        refreshPermissionSummary();
    }

    private void refreshPermissionSummary() {
        if (tvPermissionsSummarySub == null || txtPermissionsSummaryStatus == null) return;

        boolean zh = "zh".equals(AppPrefs.getLanguage(this));
        boolean mic = isMicPermissionGranted();
        boolean overlay = canShowOverlay();
        boolean notifications = areNotificationsEnabledForApp();

        boolean coreReady = mic && overlay && notifications;
        if (coreReady) {
            txtPermissionsSummaryStatus.setText(zh ? "已就绪" : "All set");
            txtPermissionsSummaryStatus.setTextColor(0xFF22C55E);
            tvPermissionsSummarySub.setText(zh
                    ? "麦克风、悬浮窗、通知均可用"
                    : "Microphone, overlay, and notifications are ready");
            return;
        }

        int readyCount = 0;
        if (mic) readyCount++;
        if (overlay) readyCount++;
        if (notifications) readyCount++;

        txtPermissionsSummaryStatus.setText(zh
                ? readyCount + "/3 已完成"
                : readyCount + "/3 ready");
        txtPermissionsSummaryStatus.setTextColor(0xFFEF4444);

        StringBuilder missing = new StringBuilder();
        if (!mic) missing.append(zh ? "麦克风" : "Microphone");
        if (!overlay) {
            if (missing.length() > 0) missing.append(zh ? "、" : ", ");
            missing.append(zh ? "悬浮窗" : "Overlay");
        }
        if (!notifications) {
            if (missing.length() > 0) missing.append(zh ? "、" : ", ");
            missing.append(zh ? "通知" : "Notifications");
        }
        tvPermissionsSummarySub.setText(zh
                ? "点击检查：" + missing
                : "Tap to review: " + missing);
    }

    private void showPermissionsDialog() {
        boolean zh = "zh".equals(AppPrefs.getLanguage(this));

        List<CharSequence> items = new ArrayList<>();
        items.add(permissionItemLabel(zh ? "麦克风" : "Microphone", isMicPermissionGranted()));
        items.add(permissionItemLabel(zh ? "悬浮窗" : "Overlay", canShowOverlay()));
        items.add(permissionItemLabel(zh ? "通知" : "Notifications", areNotificationsEnabledForApp()));

        new AlertDialog.Builder(this)
                .setTitle(zh ? "权限检查" : "Permission Check")
                .setMessage(zh
                        ? "点击一项可直接打开对应的系统设置。"
                        : "Tap any item to open the relevant system settings.")
                .setItems(items.toArray(new CharSequence[0]), (dialog, which) -> {
                    switch (which) {
                        case 0:
                            openAppPermissionSettings();
                            break;
                        case 1:
                            openOverlaySettings();
                            break;
                        case 2:
                            openNotificationSettings();
                            break;
                        default:
                            break;
                    }
                })
                .setNegativeButton(zh ? "取消" : "Cancel", null)
                .show();
    }

    private CharSequence permissionItemLabel(String label, boolean enabled) {
        boolean zh = "zh".equals(AppPrefs.getLanguage(this));
        if (enabled) {
            return label + (zh ? "  -  已就绪" : "  -  Ready");
        }
        return label + (zh ? "  -  需要处理" : "  -  Needs attention");
    }

    private void saveAndFinish() {
        sendSettingsUpdatedBroadcast();
        finish();
    }

    private void sendSettingsUpdatedBroadcast() {
        Intent intent = new Intent("com.smarthelp.SETTINGS_UPDATED");
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void setText(TextView tv, String text) {
        if (tv != null) tv.setText(text);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // ── Help & Tutorial ───────────────────────────────────────────────────────

    private void replayOnboarding() {
        try {
            View sheetView = getLayoutInflater().inflate(R.layout.dialog_onboarding, null);
            BottomSheetDialog sheet = new BottomSheetDialog(this);
            sheet.setContentView(sheetView);
            View btnDone = sheetView.findViewById(R.id.btnOnboardingDone);
            if (btnDone != null) btnDone.setOnClickListener(v -> sheet.dismiss());
            sheet.show();
        } catch (Exception e) {
            boolean zh = "zh".equals(AppPrefs.getLanguage(this));
            Toast.makeText(this,
                    zh ? "无法加载引导" : "Could not load onboarding",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void showHowToUseDialog() {
        boolean zh = "zh".equals(AppPrefs.getLanguage(this));
        String message = zh
                ? "1. 点击首页大按钮启动 SmartHelp+\n\n"
                  + "2. 打开任意应用，按住麦克风说出您想做的事\n"
                  + "   例如：\"帮我打电话给儿子\"\n\n"
                  + "3. AI 会用语音和箭头一步步引导您\n\n"
                  + "4. 每完成一步，AI 会自动进入下一步\n\n"
                  + "遇到困难时可以长按麦克风再次说话"
                : "1. Tap the big button on the home screen to start SmartHelp+\n\n"
                  + "2. Open any app, hold the mic and say what you want to do\n"
                  + "   e.g.: \"Help me call my son\"\n\n"
                  + "3. AI will guide you step by step with voice and arrows\n\n"
                  + "4. After each step, AI automatically moves to the next\n\n"
                  + "If you get stuck, hold the mic and speak again";

        new AlertDialog.Builder(this)
                .setTitle(zh ? "使用方法" : "How to Use SmartHelp+")
                .setMessage(message)
                .setPositiveButton(zh ? "知道了" : "Got it", null)
                .show();
    }
}
