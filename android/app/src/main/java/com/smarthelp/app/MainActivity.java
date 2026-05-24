package com.smarthelp.app;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SmartHelp.Main";

    private MaterialCardView cardStartAssistant;
    private LinearLayout cardHero;
    private LinearLayout layoutStartSurface;
    private LinearLayout layoutStatus;
    private LinearLayout layoutWelcomeContent;
    private LinearLayout layoutWelcomeTopBar;
    private NestedScrollView scrollWelcome;
    private View frameStartOrb;
    private View viewStartOrbRing;
    private TextView txtGreeting;
    private TextView txtSubtitle;
    private TextView txtStartLabel;
    private TextView txtStartHint;
    private TextView txtStatus;

    private View viewConnDot;
    private TextView txtConnStatus;


    private boolean introPlayed = false;
    private ObjectAnimator orbRingScaleXAnimator;
    private ObjectAnimator orbRingScaleYAnimator;
    private ObjectAnimator orbRingAlphaAnimator;
    private ObjectAnimator orbFloatAnimator;

    private boolean isServiceRunning = false;
    private Boolean lastConnectionStatus = null;

    // Receives overlay lifecycle and LiveKit connection updates.
    private android.content.BroadcastReceiver appStateReceiver;

    private ActivityResultLauncher<Intent> overlayPermissionLauncher;
    private ActivityResultLauncher<Intent> accessibilityPermissionLauncher;
    private ActivityResultLauncher<Intent> screenCaptureLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private ActivityResultLauncher<String> audioPermissionLauncher;

    private int resultCode;
    private Intent resultData;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "MainActivity created");

        // Pre-warm LiveKit token so OverlayService connects faster
        com.smarthelp.app.network.ServerConnection.preWarm(this);

        initViews();

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, windowInsets) -> {
            androidx.core.graphics.Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            if (cardHero != null) {
                cardHero.setPadding(
                        cardHero.getPaddingLeft(),
                        insets.top + (int) (36 * getResources().getDisplayMetrics().density),
                        cardHero.getPaddingRight(),
                        cardHero.getPaddingBottom());
            }
            if (scrollWelcome != null) {
                scrollWelcome.setPadding(
                        scrollWelcome.getPaddingLeft(),
                        scrollWelcome.getPaddingTop(),
                        scrollWelcome.getPaddingRight(),
                        insets.bottom);
            }
            return WindowInsetsCompat.CONSUMED;
        });

        setupPermissionLaunchers();
        setupClickListeners();
        updateWelcomeCopy();
        updateUI();
        startWelcomeAnimations();
        updateConnectionStatus(null); // Show neutral state; LiveKit status managed by OverlayService
        showOnboardingIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isServiceRunning = OverlayService.isRunning();
        applyCleanLanguageUI();
        updateUI();
        // Check token server health on resume
        checkTokenServerHealthAsync();

        appStateReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                String action = intent.getAction();
                if (ScreenCaptureService.ACTION_SERVICE_STOPPED.equals(action)) {
                    isServiceRunning = false;
                    updateConnectionStatus(false);
                    updateUI();
                    return;
                }
                if (OverlayService.ACTION_CONNECTION_STATUS.equals(action)) {
                    Boolean connected = intent.hasExtra(OverlayService.EXTRA_CONNECTED)
                            ? intent.getBooleanExtra(OverlayService.EXTRA_CONNECTED, false)
                            : null;
                    updateConnectionStatus(connected);
                }
            }
        };
        android.content.IntentFilter appStateFilter = new android.content.IntentFilter(ScreenCaptureService.ACTION_SERVICE_STOPPED);
        appStateFilter.addAction(OverlayService.ACTION_CONNECTION_STATUS);
        androidx.core.content.ContextCompat.registerReceiver(
                this,
                appStateReceiver,
                appStateFilter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (appStateReceiver != null) {
            try { unregisterReceiver(appStateReceiver); } catch (Exception ignored) {}
            appStateReceiver = null;
        }
    }


    private void initViews() {
        cardStartAssistant = findViewById(R.id.cardStartAssistant);
        cardHero            = findViewById(R.id.cardHero);
        layoutStartSurface = findViewById(R.id.layoutStartSurface);
        layoutStatus = findViewById(R.id.layoutStatus);
        layoutWelcomeContent = findViewById(R.id.layoutWelcomeContent);
        layoutWelcomeTopBar = findViewById(R.id.layoutWelcomeTopBar);
        scrollWelcome = findViewById(R.id.scrollWelcome);
        frameStartOrb = findViewById(R.id.frameStartOrb);
        viewStartOrbRing = findViewById(R.id.viewStartOrbRing);
        txtGreeting         = findViewById(R.id.txtGreeting);
        txtSubtitle         = findViewById(R.id.txtSubtitle);
        txtStartLabel = findViewById(R.id.txtStartLabel);
        txtStartHint = findViewById(R.id.txtStartHint);
        txtStatus = findViewById(R.id.txtStatus);

        viewConnDot  = findViewById(R.id.viewConnDot);
        txtConnStatus = findViewById(R.id.txtConnStatus);


        // Settings button
        ImageView btnSettingsBtn = findViewById(R.id.btnSettings);
        if (btnSettingsBtn != null) {
            btnSettingsBtn.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        }
    }


    private void showOnboardingIfNeeded() {
        if (AppPrefs.isOnboardingShown(this)) return;

        android.view.View sheetView = getLayoutInflater().inflate(R.layout.dialog_onboarding, null);
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(sheetView);
        sheet.setCancelable(false);

        sheetView.findViewById(R.id.btnOnboardingDone).setOnClickListener(v -> {
            AppPrefs.setOnboardingShown(this);
            sheet.dismiss();
            if (!AppPrefs.hasPrivacyConsent(this)) {
                showPrivacyNoticeDialog(false, null);
            }
        });

        sheet.show();
    }


    private void updateConnectionStatus(Boolean connected) {
        if (txtConnStatus == null) return;
        lastConnectionStatus = connected;
        setDotColor(connected == null ? 0xFF9B9B9B : (connected ? 0xFF22C55E : 0xFFEF4444));
        normalizeConnectionCopy(connected);
    }

    private void setDotColor(int color) {
        if (viewConnDot == null) return;
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        viewConnDot.setBackground(dot);
    }

    private void normalizeConnectionCopy(Boolean connected) {
        if (txtConnStatus == null) return;
        boolean zh = "zh".equals(AppPrefs.getLanguage(this));
        if (connected == null) {
            txtConnStatus.setText(zh ? "\u68c0\u6d4b\u4e2d..." : "Checking...");
        } else if (connected) {
            txtConnStatus.setText(zh ? "\u5df2\u8fde\u63a5" : "Online");
        } else {
            txtConnStatus.setText(zh ? "\u672a\u8fde\u63a5" : "Offline");
        }
    }

    /**
     * Quick synchronous check if the token server is reachable.
     * Used as a guard before starting services.
     */
    private boolean isTokenServerReachable() {
        String[] healthUrls = {
                AppPrefs.getTokenEndpointUrl(this).replaceAll("/token(\\?.*)?$", "/health"),
                "http://127.0.0.1:8765/health",
                "http://192.168.0.154:8765/health",
        };

        for (String healthUrl : healthUrls) {
            Log.d(TAG, "Health check: " + healthUrl);
            try {
                java.net.URL url = new java.net.URL(healthUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code == 200) {
                    Log.d(TAG, "Health check OK: " + healthUrl);
                    return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "Health check failed: " + healthUrl + " - " + e.getMessage());
            }
        }
        Log.e(TAG, "Token server unreachable from all endpoints");
        return false;
    }

    /**
     * Async health check \u2014 updates the connection status dot/text in the top right.
     */
    private void checkTokenServerHealthAsync() {
        updateConnectionStatus(null); // Show "Checking..."
        new Thread(() -> {
            boolean reachable = isTokenServerReachable();
            runOnUiThread(() -> {
                // If OverlayService is running, prefer its LiveKit status
                if (OverlayService.isRunning()) {
                    Boolean liveKitStatus = OverlayService.getLiveKitConnectionStatus();
                    if (liveKitStatus != null) {
                        updateConnectionStatus(liveKitStatus);
                        return;
                    }
                }
                updateConnectionStatus(reachable);
            });
        }).start();
    }


    private void startWithQuery(String query) {
        AppPrefs.addRecentTask(this, query);
        if (isServiceRunning) {
            Intent intent = new Intent("com.smarthelp.SEND_QUERY");
            intent.setPackage(getPackageName());
            intent.putExtra("query", query);
            sendBroadcast(intent);
            Toast.makeText(this, "Sending: " + query, Toast.LENGTH_SHORT).show();
        } else {
            // Save pending query, then go through normal permission/start flow
            AppPrefs.setPendingQuery(this, query);
            checkAndRequestPermissions();
        }
    }


    private void startWelcomeAnimations() {
        if (layoutWelcomeContent == null) return;
        layoutWelcomeContent.post(() -> {
            if (introPlayed) return;
            introPlayed = true;
            playIntroSequence();
            startAmbientMotion();
        });
    }

    private void playIntroSequence() {
        List<View> views = new ArrayList<>();
        addIfPresent(views, layoutWelcomeTopBar);
        addIfPresent(views, txtGreeting);
        addIfPresent(views, txtSubtitle);
        addIfPresent(views, frameStartOrb);
        addIfPresent(views, txtStartLabel);
        addIfPresent(views, txtStartHint);

        for (View view : views) {
            view.setAlpha(0f);
            view.setTranslationY(40f);
        }

        // Orb starts smaller and bounces in
        if (frameStartOrb != null) { frameStartOrb.setScaleX(0.7f); frameStartOrb.setScaleY(0.7f); }

        long delay = 0L;
        for (View view : views) {
            view.animate()
                    .alpha(1f).translationY(0f)
                    .setStartDelay(delay)
                    .setDuration(600L)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            delay += 80L;
        }
        if (frameStartOrb != null) {
            frameStartOrb.animate().scaleX(1f).scaleY(1f)
                    .setStartDelay(240L).setDuration(900L)
                    .setInterpolator(new OvershootInterpolator(0.8f)).start();
        }
        if (scrollWelcome != null) {
            scrollWelcome.setAlpha(0f);
            scrollWelcome.animate().alpha(1f).setDuration(380L)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private void startAmbientMotion() {
        if (viewStartOrbRing != null) {
            orbRingScaleXAnimator = ObjectAnimator.ofFloat(viewStartOrbRing, View.SCALE_X, 1f, 1.08f, 1f);
            orbRingScaleYAnimator = ObjectAnimator.ofFloat(viewStartOrbRing, View.SCALE_Y, 1f, 1.08f, 1f);
            orbRingAlphaAnimator  = ObjectAnimator.ofFloat(viewStartOrbRing, View.ALPHA, 0.95f, 0.55f, 0.95f);
            startLoopingAnimator(orbRingScaleXAnimator, 2400L, 900L);
            startLoopingAnimator(orbRingScaleYAnimator, 2400L, 900L);
            startLoopingAnimator(orbRingAlphaAnimator, 2400L, 900L);
        }
        if (frameStartOrb != null) {
            orbFloatAnimator = ObjectAnimator.ofFloat(frameStartOrb, View.TRANSLATION_Y, 0f, -8f, 0f);
            orbFloatAnimator.setDuration(2500L);
            orbFloatAnimator.setRepeatCount(ObjectAnimator.INFINITE);
            orbFloatAnimator.setInterpolator(new DecelerateInterpolator());
            orbFloatAnimator.start();
        }
    }

    private void addIfPresent(List<View> views, View view) {
        if (view != null) views.add(view);
    }

    private void startLoopingAnimator(ObjectAnimator animator, long duration, long startDelay) {
        animator.setDuration(duration);
        animator.setStartDelay(startDelay);
        animator.setRepeatCount(ObjectAnimator.INFINITE);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.start();
    }


    private void applyCleanLanguageUI() {
        boolean zh = "zh".equals(AppPrefs.getLanguage(this));
        updateWelcomeCopy(zh);
        updateUI(zh);
        normalizeConnectionCopy(lastConnectionStatus);
        normalizeMainCopy(zh);
    }

    private void applyLanguageUI() {
        applyCleanLanguageUI();
    }


    private void normalizeMainCopy(boolean zh) {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (txtGreeting != null) {
            if (zh) {
                if (hour < 12) {
                    txtGreeting.setText("\u65e9\u4e0a\u597d");
                } else if (hour < 18) {
                    txtGreeting.setText("\u4e0b\u5348\u597d");
                } else {
                    txtGreeting.setText("\u665a\u4e0a\u597d");
                }
            } else if (hour < 12) {
                txtGreeting.setText("Good morning");
            } else if (hour < 18) {
                txtGreeting.setText("Good afternoon");
            } else {
                txtGreeting.setText("Good evening");
            }
        }

        if (txtSubtitle != null) {
            txtSubtitle.setText(zh
                    ? "\u6211\u4f1a\u7528\u8bed\u97f3\u548c\u9ad8\u4eae\u4e00\u6b65\u6b65\u5e2e\u60a8\u64cd\u4f5c\u624b\u673a\u3002"
                    : "Gentle voice and visual guidance for everyday phone tasks.");
        }

        if (isServiceRunning) {
            if (txtStartLabel != null) txtStartLabel.setText(zh ? "\u505c\u6b62\u52a9\u624b" : "Stop assistant");
            if (txtStartHint != null) txtStartHint.setText(zh
                    ? "SmartHelp+ \u6b63\u5728\u8fd0\u884c\uff0c\u8bf7\u6253\u5f00\u5e94\u7528\u5e76\u8bf4\u51fa\u8981\u505a\u7684\u4e8b\u3002"
                    : "SmartHelp+ is active. Open any app and tell it what you want to do.");
            if (txtStatus != null) txtStatus.setText(zh ? "\u6b63\u5728\u5e2e\u52a9\u60a8" : "Helping you now...");
        } else {
            if (txtStartLabel != null) txtStartLabel.setText(zh ? "\u5f00\u59cb\u8bed\u97f3\u5f15\u5bfc" : "Start voice guide");
            if (txtStartHint != null) txtStartHint.setText(zh
                    ? "\u5f00\u59cb\u524d\u4f1a\u7533\u8bf7\u9ea6\u514b\u98ce\u3001\u60ac\u6d6e\u7a97\u548c\u5f55\u5c4f\u6743\u9650\u3002"
                    : "We will ask for microphone, overlay, and screen permissions before helping you.");
        }

    }

    private void updateWelcomeCopy() {
        updateWelcomeCopy("zh".equals(AppPrefs.getLanguage(this)));
    }

    private void updateWelcomeCopy(boolean zh) {
        normalizeMainCopy(zh);
    }


    private void setupPermissionLaunchers() {
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    Log.d(TAG, "notificationPermissionLauncher: granted=" + isGranted);
                    checkAudioPermission();
                });

        audioPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    Log.d(TAG, "audioPermissionLauncher: granted=" + isGranted);
                    if (!isGranted) {
                        handleAudioPermissionDenied();
                        return;
                    }
                    checkOverlayPermission();
                });

        overlayPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Log.d(TAG, "overlayPermissionLauncher: canDraw=" + Settings.canDrawOverlays(this));
                    if (Settings.canDrawOverlays(this)) checkAccessibilityPermission();
                    else Toast.makeText(this, "Overlay permission is required to show guidance.", Toast.LENGTH_LONG).show();

                });

        accessibilityPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> requestScreenCapturePermission());

        screenCaptureLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Log.d(TAG, "screenCaptureLauncher: resultCode=" + result.getResultCode()
                            + ", hasData=" + (result.getData() != null));
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        resultCode = result.getResultCode();
                        resultData = result.getData();
                        startServices();
                    } else {
                        Toast.makeText(this,
                                "zh".equals(AppPrefs.getLanguage(this))
                                        ? "\u9700\u8981\u5f55\u5c4f\u6743\u9650\u624d\u80fd\u5f00\u59cb\u8bed\u97f3\u5f15\u5bfc\u3002"
                                        : "Screen capture permission is required.",
                                Toast.LENGTH_LONG).show();
                    }
                });
    }


    private void setupClickListeners() {
        // Main start/stop button
        if (cardStartAssistant != null) {
            cardStartAssistant.setOnClickListener(v -> {
                Log.d(TAG, "Start orb clicked; isServiceRunning=" + isServiceRunning);
                if (isServiceRunning) stopServices();
                else checkAndRequestPermissions();
            });
        }

    }


    private void checkAndRequestPermissions() {
        Log.d(TAG, "checkAndRequestPermissions: consent=" + AppPrefs.hasPrivacyConsent(this));
        if (!AppPrefs.hasPrivacyConsent(this)) {
            showPrivacyNoticeDialog(true, this::checkAndRequestPermissionsInternal);
            return;
        }
        checkAndRequestPermissionsInternal();
    }

    private void checkAndRequestPermissionsInternal() {
        Log.d(TAG, "checkAndRequestPermissionsInternal");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Notification permission missing; requesting");
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }
        checkAudioPermission();
    }

    private void checkAudioPermission() {
        int audioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        Log.d(TAG, "checkAudioPermission: granted=" + (audioPermission == PackageManager.PERMISSION_GRANTED));
        if (audioPermission != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermission();
            return;
        }
        checkOverlayPermission();
    }

    private void requestAudioPermission() {
        Log.d(TAG, "requestAudioPermission: rationale="
                + shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO));
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            showMicrophoneRationaleDialog();
            return;
        }
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
    }

    private void handleAudioPermissionDenied() {
        Log.w(TAG, "handleAudioPermissionDenied: rationale="
                + shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO));
        if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            showMicrophoneSettingsDialog();
            return;
        }
        Toast.makeText(this, R.string.permission_mic_denied, Toast.LENGTH_LONG).show();
        checkOverlayPermission();
    }

    private void showMicrophoneRationaleDialog() {
        Log.d(TAG, "showMicrophoneRationaleDialog");
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_mic_title)
                .setMessage(R.string.permission_mic_message)
                .setPositiveButton(R.string.permission_mic_allow, (dialog, which) ->
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO))
                .setNegativeButton(R.string.permission_mic_skip, (dialog, which) ->
                        checkOverlayPermission())
                .show();
    }

    private void showMicrophoneSettingsDialog() {
        Log.d(TAG, "showMicrophoneSettingsDialog");
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_mic_title)
                .setMessage(R.string.permission_mic_settings_message)
                .setPositiveButton(R.string.permission_open_settings, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton(R.string.permission_mic_skip, (dialog, which) ->
                        checkOverlayPermission())
                .show();
    }

    private void checkOverlayPermission() {
        Log.d(TAG, "checkOverlayPermission: canDraw=" + Settings.canDrawOverlays(this));
        boolean zh = "zh".equals(AppPrefs.getLanguage(this));
        if (!Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.permission_overlay_title)
                    .setMessage(R.string.permission_overlay_message)
                    .setPositiveButton(zh ? "\u6253\u5f00\u8bbe\u7f6e" : "Open Settings", (dialog, which) -> {
                        Log.d(TAG, "Opening overlay settings");
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        overlayPermissionLauncher.launch(intent);
                    })
                    .setNegativeButton(zh ? "\u53d6\u6d88" : "Cancel", null)
                    .show();
            return;
        }
        checkAccessibilityPermission();
    }

    private void checkAccessibilityPermission() {
        if (isSmartHelpAccessibilityEnabled()) {
            requestScreenCapturePermission();
            return;
        }

        boolean zh = "zh".equals(AppPrefs.getLanguage(this));
        new AlertDialog.Builder(this)
                .setTitle(zh ? "\u5efa\u8bae\u5f00\u542f\u7cbe\u51c6\u9ad8\u4eae" : "Enable precise highlights")
                .setMessage(zh
                        ? "\u5f00\u542f SmartHelp+ \u65e0\u969c\u788d\u670d\u52a1\u540e\uff0c\u6211\u4eec\u53ef\u4ee5\u8bfb\u53d6\u5c4f\u5e55\u4e0a\u7684\u6309\u94ae\u8fb9\u754c\uff0c\u9ad8\u4eae\u4f4d\u7f6e\u4f1a\u66f4\u51c6\u3002\u4e0d\u4f1a\u81ea\u52a8\u70b9\u51fb\u3002"
                        : "SmartHelp+ can read visible button bounds for more accurate highlights. It will not auto-click.")
                .setPositiveButton(zh ? "\u6253\u5f00\u8bbe\u7f6e" : "Open Settings", (dialog, which) ->
                        accessibilityPermissionLauncher.launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton(zh ? "\u8df3\u8fc7" : "Skip", (dialog, which) ->
                        requestScreenCapturePermission())
                .show();
    }

    private boolean isSmartHelpAccessibilityEnabled() {
        if (SmartHelpAccessibilityService.isRunning()) {
            return true;
        }
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) {
            return false;
        }
        String expected = new ComponentName(this, SmartHelpAccessibilityService.class).flattenToString();
        return enabledServices.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
    }

    private void requestScreenCapturePermission() {
        Log.d(TAG, "requestScreenCapturePermission");
        boolean zh = "zh".equals(AppPrefs.getLanguage(this));
        new AlertDialog.Builder(this)
                .setTitle(zh ? "\u9700\u8981\u5f55\u5c4f\u6743\u9650" : getString(R.string.permission_screen_title))
                .setMessage(PrivacySafety.getPrivacyPermissionMessage(this))
                .setPositiveButton(zh ? "\u5141\u8bb8" : "Allow", (dialog, which) -> {
                    Log.d(TAG, "Screen capture dialog accepted; launching system consent");
                    try {
                        MediaProjectionManager pm =
                                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                        Intent captureIntent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                                ? pm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                                : pm.createScreenCaptureIntent();
                        screenCaptureLauncher.launch(captureIntent);
                    } catch (Exception e) {
                        Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(zh ? "\u53d6\u6d88" : "Cancel", null)
                .show();
    }

    private void showPrivacyNoticeDialog(boolean requireConsent, Runnable onAccepted) {
        Log.d(TAG, "showPrivacyNoticeDialog: requireConsent=" + requireConsent);
        boolean zh = "zh".equals(AppPrefs.getLanguage(this));
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(PrivacySafety.getPrivacyNoticeTitle(this))
                .setMessage(PrivacySafety.getPrivacyNoticeMessage(this));

        if (requireConsent) {
            builder.setPositiveButton(zh ? "\u540c\u610f\u5e76\u7ee7\u7eed" : "Agree and Continue", (dialog, which) -> {
                AppPrefs.setPrivacyConsent(this, true);
                if (onAccepted != null) onAccepted.run();
            });
            builder.setNegativeButton(zh ? "\u53d6\u6d88" : "Cancel", null);
        } else {
            builder.setPositiveButton(zh ? "\u77e5\u9053\u4e86" : "Close", null);
        }

        builder.show();
    }


    private void startServices() {
        Log.d(TAG, "Starting services...");
        
        // Run network check in background to avoid NetworkOnMainThreadException
        new Thread(() -> {
            boolean isReachable = isTokenServerReachable();
            
            runOnUiThread(() -> {
                if (!isReachable) {
                    boolean zh = "zh".equals(AppPrefs.getLanguage(MainActivity.this));
                    String endpoint = AppPrefs.getTokenEndpointUrl(MainActivity.this);
                    Toast.makeText(MainActivity.this,
                            zh ? "无法连接服务器: " + endpoint
                               : "Cannot reach server: " + endpoint,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                
                try {
                    Intent overlayIntent = new Intent(MainActivity.this, OverlayService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(overlayIntent);
                    } else {
                        startService(overlayIntent);
                    }

                    Intent serviceIntent = new Intent(MainActivity.this, ScreenCaptureService.class);
                    serviceIntent.putExtra("resultCode", resultCode);
                    serviceIntent.putExtra("resultData", resultData);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }

                    isServiceRunning = true;
                    updateUI();
                    Toast.makeText(MainActivity.this, "SmartHelp+ is active. Open any app and try it.", Toast.LENGTH_LONG).show();
                    moveTaskToBack(true);
                } catch (Exception e) {
                    Log.e(TAG, "Error starting services", e);
                    Toast.makeText(MainActivity.this, "Start failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    isServiceRunning = false;
                    updateUI();
                }
            });
        }).start();
    }

    private void stopServices() {
        try {
            stopService(new Intent(this, ScreenCaptureService.class));
            stopService(new Intent(this, OverlayService.class));
        } catch (Exception e) {
            Log.e(TAG, "Error stopping services", e);
        }
        isServiceRunning = false;
        AppPrefs.clearPendingQuery(this);
        updateUI();
    }


    private void updateUI() {
        updateUI("zh".equals(AppPrefs.getLanguage(this)));
    }

    private void updateUI(boolean zh) {
        if (isServiceRunning) {
            if (layoutStatus != null) layoutStatus.setVisibility(View.VISIBLE);
            if (layoutStartSurface != null) {
                layoutStartSurface.setBackgroundResource(R.drawable.bg_welcome_cta_active);
            }
            if (orbRingScaleXAnimator != null) orbRingScaleXAnimator.setDuration(1600L);
            if (orbRingScaleYAnimator != null) orbRingScaleYAnimator.setDuration(1600L);
            if (orbRingAlphaAnimator != null) orbRingAlphaAnimator.setDuration(1600L);
        } else {
            if (layoutStatus != null) layoutStatus.setVisibility(View.GONE);
            if (layoutStartSurface != null) {
                layoutStartSurface.setBackgroundResource(R.drawable.bg_welcome_cta_idle);
            }
            if (orbRingScaleXAnimator != null) orbRingScaleXAnimator.setDuration(2400L);
            if (orbRingScaleYAnimator != null) orbRingScaleYAnimator.setDuration(2400L);
            if (orbRingAlphaAnimator != null) orbRingAlphaAnimator.setDuration(2400L);
        }
        normalizeMainCopy(zh);
    }

    @Override
    protected void onDestroy() {
        if (orbRingScaleXAnimator != null) orbRingScaleXAnimator.cancel();
        if (orbRingScaleYAnimator != null) orbRingScaleYAnimator.cancel();
        if (orbRingAlphaAnimator  != null) orbRingAlphaAnimator.cancel();
        if (orbFloatAnimator      != null) orbFloatAnimator.cancel();
        super.onDestroy();
        Log.d(TAG, "MainActivity destroyed");
    }
}
