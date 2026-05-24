package com.smarthelp.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.SensorPrivacyManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;

/**
 * Foreground speech-capture activity used by the overlay mic. It avoids the
 * OEM speech-dialog UI and keeps all error handling inside the app.
 */
public class OverlayVoiceCaptureActivity extends AppCompatActivity {

    private SpeechRecognizer speechRecognizer;
    private TextView txtVoiceTitle;
    private TextView txtVoiceStatus;
    private TextView txtVoiceTranscript;
    private boolean launchRequested = false;
    private boolean overlayRestoreSent = false;
    private boolean finishingCapture = false;
    private boolean retryingOffline = false;
    private boolean ignoreClientErrorOnce = false;
    private String latestTranscript;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_capture);
        overridePendingTransition(0, 0);

        txtVoiceTitle = findViewById(R.id.txtVoiceTitle);
        txtVoiceStatus = findViewById(R.id.txtVoiceStatus);
        txtVoiceTranscript = findViewById(R.id.txtVoiceTranscript);

        MaterialButton btnCancel = findViewById(R.id.btnVoiceCancel);
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> finishCaptureTask());
        }

        updateListeningUi();
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        scheduleSpeechCaptureLaunch();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            scheduleSpeechCaptureLaunch();
        }
    }

    private void scheduleSpeechCaptureLaunch() {
        View root = findViewById(android.R.id.content);
        if (root == null) {
            launchSpeechCaptureIfNeeded();
            return;
        }
        root.postDelayed(this::launchSpeechCaptureIfNeeded, 450);
    }

    private void launchSpeechCaptureIfNeeded() {
        if (launchRequested || isFinishing()) return;
        launchRequested = true;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            startActivity(new Intent(this, MicPermissionActivity.class));
            finishCaptureTask();
            return;
        }

        // Android 12+: check system-level microphone privacy toggle.
        if (isMicPrivacyToggleOff()) {
            showMicPrivacyDialog();
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showFailureAndClose(isChineseUi()
                    ? "此设备暂时无法使用语音识别。"
                    : "Voice recognition is not available on this device.");
            return;
        }

        ensureSpeechRecognizer();
        startSpeechRecognition(false);
    }

    private void ensureSpeechRecognizer() {
        if (speechRecognizer != null) return;

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                updateListeningUi();
            }

            @Override
            public void onBeginningOfSpeech() {
                updateStatus(isChineseUi() ? "正在听，请继续说…" : "Listening, keep speaking…");
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
                updateStatus(isChineseUi() ? "正在处理…" : "Processing…");
            }

            @Override
            public void onError(int error) {
                if (finishingCapture) return;
                if (ignoreClientErrorOnce && error == SpeechRecognizer.ERROR_CLIENT) {
                    ignoreClientErrorOnce = false;
                    return;
                }

                String transcript = latestTranscript == null ? "" : latestTranscript.trim();
                if ((error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                        && !transcript.isEmpty()) {
                    dispatchQuery(transcript);
                    finishCaptureTask();
                    return;
                }

                if ((error == SpeechRecognizer.ERROR_NETWORK
                        || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                        || error == SpeechRecognizer.ERROR_SERVER)
                        && !retryingOffline) {
                    retryingOffline = true;
                    updateStatus(isChineseUi()
                            ? "网络语音失败，正在尝试离线识别…"
                            : "Online speech failed, trying offline recognition…");
                    restartSpeechRecognition(true);
                    return;
                }

                showFailureAndClose(mapSpeechError(error));
            }

            @Override
            public void onResults(Bundle results) {
                String transcript = extractTranscript(results);
                if (transcript == null || transcript.isEmpty()) {
                    transcript = latestTranscript == null ? null : latestTranscript.trim();
                }

                if (transcript == null || transcript.isEmpty()) {
                    showFailureAndClose(isChineseUi()
                            ? "没有听清楚，请再试一次。"
                            : "Didn't catch that. Please try again.");
                    return;
                }

                dispatchQuery(transcript);
                finishCaptureTask();
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                String transcript = extractTranscript(partialResults);
                if (transcript == null || transcript.isEmpty()) return;
                latestTranscript = transcript;
                if (txtVoiceTranscript != null) {
                    txtVoiceTranscript.setVisibility(View.VISIBLE);
                    txtVoiceTranscript.setText(transcript);
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });
    }

    private void restartSpeechRecognition(boolean preferOffline) {
        if (speechRecognizer == null) {
            startSpeechRecognition(preferOffline);
            return;
        }
        ignoreClientErrorOnce = true;
        speechRecognizer.cancel();
        View root = findViewById(android.R.id.content);
        if (root != null) {
            root.postDelayed(() -> startSpeechRecognition(preferOffline), 250);
        } else {
            startSpeechRecognition(preferOffline);
        }
    }

    private void startSpeechRecognition(boolean preferOffline) {
        if (speechRecognizer == null) return;
        latestTranscript = null;
        if (txtVoiceTranscript != null) {
            txtVoiceTranscript.setText("");
            txtVoiceTranscript.setVisibility(View.GONE);
        }
        updateListeningUi();
        try {
            speechRecognizer.startListening(buildSpeechIntent(preferOffline));
        } catch (SecurityException e) {
            showFailureAndClose(isChineseUi()
                    ? "麦克风权限不可用，请重新授权后再试。"
                    : mapPermissionFailureMessage());
        } catch (Exception e) {
            showFailureAndClose(isChineseUi()
                    ? "语音输入暂时不可用。"
                    : "Voice input is temporarily unavailable.");
        }
    }

    private Intent buildSpeechIntent(boolean preferOffline) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        String languageTag = getSpeechLanguageTag();
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        intent.putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 1200L);
        intent.putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 800L);
        intent.putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 0L);
        return intent;
    }

    private String getSpeechLanguageTag() {
        String language = AppPrefs.getLanguage(this);
        if ("zh".equals(language)) return "zh-CN";
        if ("ms".equals(language)) return "ms-MY";
        return "en-US";
    }

    private String extractTranscript(Bundle results) {
        if (results == null) return null;
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) return null;
        String transcript = matches.get(0);
        return transcript == null ? null : transcript.trim();
    }

    private void updateListeningUi() {
        if (txtVoiceTitle != null) {
            txtVoiceTitle.setText(isChineseUi() ? "正在听…" : "Listening…");
        }
        updateStatus(isChineseUi() ? "请说出你要做的事" : "Say what you want to do");
    }

    private void updateStatus(String status) {
        if (txtVoiceStatus != null) {
            txtVoiceStatus.setText(status);
        }
    }

    private String mapSpeechError(int error) {
        boolean zh = isChineseUi();
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            return mapPermissionFailureMessage();
        }
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_NETWORK:
                return zh ? "语音服务需要网络，请检查连接后重试。" : "Speech recognition needs internet. Check your connection and try again.";
            case SpeechRecognizer.ERROR_AUDIO:
                return zh ? "麦克风暂时不可用，请再试一次。" : "The microphone is temporarily unavailable. Please try again.";
            case SpeechRecognizer.ERROR_SERVER:
                return zh ? "语音服务暂时不可用，请稍后再试。" : "The speech service is temporarily unavailable. Please try again.";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
            case SpeechRecognizer.ERROR_NO_MATCH:
                return zh ? "没有听清楚，请再试一次。" : "Didn't catch that. Please try again.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return zh ? "语音服务正忙，请稍后再试。" : "Speech recognition is busy. Please try again.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return zh ? "麦克风权限不可用，请重新授权后再试。" : "Microphone permission is unavailable. Please try again.";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                return zh ? "当前语言的语音识别不可用。" : "Speech recognition for the current language is unavailable.";
            default:
                return zh ? "语音输入暂时不可用，请再试一次。" : "Voice input is temporarily unavailable. Please try again.";
        }
    }

    private void dispatchQuery(String query) {
        Intent intent = new Intent("com.smarthelp.SEND_QUERY");
        intent.setPackage(getPackageName());
        intent.putExtra("query", query);
        sendBroadcast(intent);
    }

    private boolean isMicPrivacyToggleOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SensorPrivacyManager spm = (SensorPrivacyManager) getSystemService(SensorPrivacyManager.class);
            if (spm != null) {
                try {
                    java.lang.reflect.Method method = SensorPrivacyManager.class.getMethod("isSensorPrivacyEnabled", Integer.TYPE);
                    Object enabled = method.invoke(spm, SensorPrivacyManager.Sensors.MICROPHONE);
                    return enabled instanceof Boolean && (Boolean) enabled;
                } catch (Exception ignored) {
                    try {
                        java.lang.reflect.Method method = SensorPrivacyManager.class.getMethod("isSensorPrivacyEnabled", Integer.TYPE, Integer.TYPE);
                        Object enabled = method.invoke(spm, SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE, SensorPrivacyManager.Sensors.MICROPHONE);
                        return enabled instanceof Boolean && (Boolean) enabled;
                    } catch (Exception ignoredAgain) {
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private void showMicPrivacyDialog() {
        boolean zh = isChineseUi();
        new AlertDialog.Builder(this)
                .setTitle(zh ? "麦克风已被系统关闭" : "Microphone is disabled")
                .setMessage(zh
                        ? "系统隐私设置已关闭麦克风。\n请下拉快捷设置，点击麦克风图标开启。"
                        : "Your device's microphone toggle is OFF.\nPull down Quick Settings and tap the microphone icon to enable it.")
                .setPositiveButton(zh ? "打开隐私设置" : "Open Privacy Settings", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_PRIVACY_SETTINGS));
                    } catch (Exception ignored) {}
                    finishCaptureTask();
                })
                .setNegativeButton(zh ? "取消" : "Cancel", (d, w) -> finishCaptureTask())
                .setCancelable(false)
                .show();
    }

    private void showFailureAndClose(String message) {
        boolean appHasMicPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        // If permission is granted but still blocked, it's an "Always allow" issue on overlay apps.
        // Show a dialog with a direct link to the permission settings instead of just a toast.
        if (appHasMicPermission) {
            boolean zh = isChineseUi();
            if (isFinishing() || isDestroyed()) {
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
                finishCaptureTask();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(zh ? "麦克风被系统拦截" : "Microphone blocked")
                    .setMessage(zh
                            ? "SmartHelp+ 是悬浮窗应用，运行时系统视为「后台」。\n\n请将麦克风权限改为「始终允许」：\n设置 → 应用 → SmartHelp+ → 权限 → 麦克风 → 始终允许"
                            : "SmartHelp+ runs as an overlay, so Android treats it as background.\n\nFix: set mic permission to 'Always allow':\nSettings → Apps → SmartHelp+ → Permissions → Microphone → Always allow")
                    .setPositiveButton(zh ? "去设置" : "Open Settings", (d, w) -> {
                        try {
                            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            i.setData(android.net.Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception ignored) {}
                        finishCaptureTask();
                    })
                    .setNegativeButton(zh ? "取消" : "Cancel", (d, w) -> finishCaptureTask())
                    .setCancelable(false)
                    .show();
            return;
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finishCaptureTask();
    }

    private String mapPermissionFailureMessage() {
        boolean zh = isChineseUi();
        boolean appHasMicPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (!appHasMicPermission) {
            return zh
                    ? "请开启 SmartHelp+ 的麦克风权限后再试。"
                    : "Enable SmartHelp+ microphone permission, then try again.";
        }
        // Permission is granted but blocked — most likely "Allow only when in use" on an overlay app.
        // Overlay apps run while another app is in the foreground, so the OS treats them as background.
        // The fix is to set mic permission to "Always allow".
        return zh
                ? "麦克风被系统拦截。SmartHelp+ 是悬浮窗应用，请将麦克风权限改为「始终允许」。"
                : "Mic blocked. As an overlay app, SmartHelp+ needs mic set to 'Always allow'.";
    }

    private void finishCaptureTask() {
        if (finishingCapture) return;
        finishingCapture = true;
        restoreOverlay();
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finish();
        }
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        if (speechRecognizer != null) {
            try {
                speechRecognizer.destroy();
            } catch (Exception ignored) {
            }
            speechRecognizer = null;
        }
        restoreOverlay();
        super.onDestroy();
    }

    private void restoreOverlay() {
        if (overlayRestoreSent) return;
        overlayRestoreSent = true;
        Intent intent = new Intent("com.smarthelp.SHOW_OVERLAY");
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private boolean isChineseUi() {
        return "zh".equals(AppPrefs.getLanguage(this));
    }
}
