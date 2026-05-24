/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.animation.ValueAnimator
 *  android.app.Notification
 *  android.app.NotificationChannel
 *  android.app.NotificationManager
 *  android.app.PendingIntent
 *  android.app.Service
 *  android.content.BroadcastReceiver
 *  android.content.Context
 *  android.content.Intent
 *  android.content.IntentFilter
 *  android.content.res.ColorStateList
 *  android.graphics.Rect
 *  android.graphics.drawable.Drawable
 *  android.graphics.drawable.GradientDrawable
 *  android.media.MediaPlayer
 *  android.net.Uri
 *  android.os.Build$VERSION
 *  android.os.Bundle
 *  android.os.Handler
 *  android.os.IBinder
 *  android.os.Looper
 *  android.speech.RecognitionListener
 *  android.speech.SpeechRecognizer
 *  android.speech.tts.TextToSpeech
 *  android.speech.tts.Voice
 *  android.text.TextUtils$TruncateAt
 *  android.util.Base64
 *  android.util.DisplayMetrics
 *  android.util.Log
 *  android.view.ContextThemeWrapper
 *  android.view.LayoutInflater
 *  android.view.View
 *  android.view.ViewConfiguration
 *  android.view.ViewGroup$LayoutParams
 *  android.view.WindowManager
 *  android.view.WindowManager$BadTokenException
 *  android.view.WindowManager$LayoutParams
 *  android.view.inputmethod.InputMethodManager
 *  android.widget.Button
 *  android.widget.EditText
 *  android.widget.ImageView
 *  android.widget.LinearLayout
 *  android.widget.LinearLayout$LayoutParams
 *  android.widget.ScrollView
 *  android.widget.TextView
 *  android.widget.Toast
 *  androidx.core.app.NotificationCompat$Builder
 *  androidx.core.content.ContextCompat
 *  com.google.android.material.R$attr
 *  com.google.android.material.button.MaterialButton
 *  com.google.android.material.floatingactionbutton.FloatingActionButton
 *  com.smarthelp.app.R$drawable
 *  com.smarthelp.app.R$id
 *  com.smarthelp.app.R$layout
 *  com.smarthelp.app.R$style
 */
package com.smarthelp.app;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.TextUtils;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.smarthelp.app.AppPrefs;
import com.smarthelp.app.MainActivity;
import com.smarthelp.app.MicPermissionActivity;
import com.smarthelp.app.OverlayVoiceCaptureActivity;
import com.smarthelp.app.R;
import com.smarthelp.app.ScreenCaptureService;
import com.smarthelp.app.SettingsActivity;
import com.smarthelp.app.SmartHelpAccessibilityService;
import com.smarthelp.app.VoiceWaveView;
import com.smarthelp.app.network.MessageProtocol;
import com.smarthelp.app.network.ServerConnection;
import com.smarthelp.app.overlay.HighlightRenderer;
import com.smarthelp.app.session.TaskSessionLocal;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class OverlayService
extends Service {
    private static final String TAG = "SmartHelp.Overlay";
    private static final String CHANNEL_ID = "smarthelp_overlay_channel";
    private static final int BALL_CLOSE_LONG_PRESS_MS = 600;
    private static final int MIC_FLOAT_BOTTOM_MARGIN_DP = 80;
    private static final int MIC_AI_RESPONSE_BOTTOM_OFFSET_DP = 190;
    private static final int MIC_USER_TRANSCRIPT_BOTTOM_OFFSET_DP = 270;
    // LiveKit's setMicrophoneEnabled(true) is async; the track takes ~100-300ms to
    // actually start publishing. Tell the user to wait this long before speaking so
    // the first words aren't dropped.
    private static final long MIC_WARMUP_MS = 300L;
    // Silence auto-stop: how long we wait without transcript activity before
    // closing the mic. Elderly users often pause mid-sentence, so be generous.
    private static final long SILENCE_AUTO_STOP_MS = 10000L;
    // Initial polling interval after a highlight is shown.
    private static final long AUTO_ANALYZE_INITIAL_INTERVAL_MS = 5000L;
    // Long-interval polling used after the first noTapReminderCount cap is
    // hit. Without this we'd stop polling and the user would wait for the
    // server-side 60s timeout to advance — terrible UX when accessibility
    // events don't fire (e.g., MIUI restrictions).
    private static final long AUTO_ANALYZE_LONG_INTERVAL_MS = 10000L;
    private static final long TAP_ARROW_SPEECH_DEBOUNCE_MS = 3500L;
    // After an accessibility event fires (user tapped something), wait this
    // long before screenshotting the result. 3 s gives Android time to
    // finish page transitions / animations / WhatsApp bottom-sheet expands
    // before the verification screenshot is taken — short waits caught the
    // screen mid-animation and produced wrong "screen unchanged" results.
    private static final long USER_ACTION_VERIFY_DELAY_MS = 3000L;
    // Delay before showing the Yes/No completion overlay so the AI's final
    // hand-off TTS sentence ("Pick a photo. Tap the green Send button.") and
    // the "Anything else?" follow-up have time to play through before the
    // buttons appear. Without this delay the overlay flashes on screen
    // before elderly users hear the guidance.
    private static final long TASK_COMPLETE_DIALOG_DELAY_MS = 3000L;
    private static final boolean VOICE_ONLY_OVERLAY = false;
    public static final String ACTION_CONNECTION_STATUS = "com.smarthelp.CONNECTION_STATUS";
    private static final String ACTION_USER_ACTION_DETECTED = "com.smarthelp.USER_ACTION_DETECTED";
    private static final String ACTION_SENSITIVE_SCREEN_DETECTED = "com.smarthelp.SENSITIVE_SCREEN_DETECTED";
    public static final String EXTRA_CONNECTED = "connected";
    private static final String[] DEMO_FAMILY_RELATIONS = new String[]{"daughter", "son", "wife", "husband", "mother", "mom", "mum", "father", "dad", "sister", "brother", "grandmother", "grandma", "grandfather", "grandpa"};
    private static volatile boolean running = false;
    private static volatile Boolean liveKitConnected = null;
    private static volatile boolean skipServerConnectionForTests = false;
    private boolean foregroundStarted = false;
    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams params;
    private HighlightRenderer highlightRenderer;
    private TextView txtGuidance;
    private TextView txtUserQuery;
    private TextView txtListening;
    private TextView txtHint;
    private TextView txtQuickActionsTitle;
    private TextView txtQuickActionsSubtitle;
    private TextView txtWelcomeLabel;
    private TextView txtWelcomeSubtitle;
    private TextView txtInputLabel;
    private View layoutThinking;
    private TextView txtThinkingDots;
    private View btnMicrophone;
    private View btnMinimize;
    private View btnChat;
    private View micGlowView;
    private MaterialButton btnDoneStep;
    private MaterialButton btnShowAgain;
    private MaterialButton btnNeedHelp;
    private View layoutDoneButtons;
    private VoiceWaveView voiceWaveView;
    private View cardGuidance;
    private View cardUserQuery;
    private View cardInputBar;
    private View layoutQuickActionsSection;
    private LinearLayout layoutQuickActions;
    private TextView btnMoreActions;
    private View cardError;
    private TextView txtErrorMessage;
    private View btnErrorRetry;
    private TextView txtStepProgress;
    private TaskSessionLocal session;
    private View ballView;
    private WindowManager.LayoutParams ballParams;
    private TextView txtGuidanceBall;
    private TextView txtGuidanceBallStep;
    private TextView txtGuidanceBallMeta;
    private View cardGuidanceBall;
    private View layoutDoneBallButtons;
    private MaterialButton btnDoneBall;
    private MaterialButton btnShowAgainBall;
    private MaterialButton btnNeedHelpBall;
    private MaterialButton btnExpandPanel;
    private boolean ballCloseDragMode = false;
    private View cardInstructionBubble;
    private TextView txtInstructionBubble;
    private TextView txtInstructionBubbleStep;
    private View imgBubbleTail;
    private ImageView imgBall;
    private TextView txtBallStepBadge;
    private View closeTargetView;
    private View closeTargetCircle;
    private TextView txtCloseTargetLabel;
    private WindowManager.LayoutParams closeTargetParams;
    private boolean isCloseTargetVisible = false;
    private boolean isCloseTargetHovering = false;
    private View chatView;
    private WindowManager.LayoutParams chatParams;
    private LinearLayout chatMessageContainer;
    private ScrollView chatScrollView;
    private View layoutChatEmpty;
    private boolean isChatOpen = false;
    private boolean isBallMode = false;
    private OverlayUiState uiState = OverlayUiState.IDLE;
    private Runnable thinkingAnimRunnable;
    private int thinkingDotCount = 0;
    private EditText etQueryInput;
    private ServerConnection serverConnection;
    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;
    private TextToSpeech localTts;
    private boolean localTtsReady = false;
    private String pendingLocalSpeech;
    private MediaPlayer aiVoicePlayer;
    private String lastAiVoiceAudioData;
    private String lastAiVoiceText;
    private boolean speechStopRequested = false;
    private boolean isListening = false;
    private String currentVoiceText;
    private String latestLiveTranscript;
    private String lastFinalTranscriptAddedToChat;
    private Runnable autoAnalyzeRunnable;
    private Runnable userActionAnalyzeRunnable;
    private int noTapReminderCount = 0;
    private long lastTapArrowSpeechAtMs = 0L;
    private String lastTapArrowSpeechText = "";
    private Runnable silenceRunnable;
    private Runnable micHoldStartRunnable;
    private Runnable clearMicStatusRunnable;
    private Runnable pendingTranscriptSubmitRunnable;
    private Runnable ballCloseLongPressRunnable;
    private ValueAnimator micGlowAnimator;
    private boolean micHoldArmed = false;
    private BroadcastReceiver guidanceReceiver;
    private float initialX;
    private float initialY;
    private float initialTouchX;
    private float initialTouchY;
    private boolean isDragging = false;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isMinimized = false;
    private boolean restoreOverlayAfterCapture = false;
    private boolean restoreHighlightAfterCapture = false;
    private boolean restoreBallAfterCapture = false;
    private boolean restoreChatAfterCapture = false;
    private boolean awaitingCompletionFollowup = false;
    private boolean awaitingServerReply = false;
    private boolean awaitingGuidedUserAction = false;
    private boolean pendingWhatsAppDemoLaunchOnScreenshot = false;
    private boolean micControlsMinimized = false;
    private boolean shuttingDown = false;
    private View taskCompleteDialogView;
    // Floating mic overlay (bottom-center, replaces embedded mic card)
    private View micFloatView;
    private View micAiResponseView;
    private View micUserTranscriptView;
    private WindowManager.LayoutParams micFloatParams;
    private WindowManager.LayoutParams micAiResponseParams;
    private WindowManager.LayoutParams micUserTranscriptParams;
    private View micRing1;
    private View micRing2;
    private View micRing3;
    private View micIdleHalo;
    private View layoutMicControls;
    private TextView txtMicHint;
    private TextView txtMicAiResponse;
    private TextView txtMicUserTranscript;
    private Runnable hideMicUserTranscriptRunnable;
    private View layoutMicTextInput;
    private EditText etMicTextInput;
    private View btnMicTextSend;
    private ImageView imgMicFloat;
    private ValueAnimator micRippleAnim1;
    private ValueAnimator micRippleAnim2;
    private ValueAnimator micRippleAnim3;
    private ValueAnimator micIdleHaloAnimator;
    private Runnable hideMicHintRunnable;
    private boolean micHintShownOnce = false;

    public static boolean isRunning() {
        return running;
    }

    public static Boolean getLiveKitConnectionStatus() {
        return liveKitConnected;
    }

    static void setSkipServerConnectionForTests(boolean skip) {
        skipServerConnectionForTests = skip;
    }

    public void onCreate() {
        super.onCreate();
        running = true;
        this.shuttingDown = false;
        this.publishConnectionStatus(null);
        Log.d((String)TAG, (String)"OverlayService created");
        this.createNotificationChannel();
        this.startOverlayForegroundIfNeeded();
        this.session = new TaskSessionLocal();
        this.serverConnection = new ServerConnection((Context)this, message -> this.handler.post(() -> this.handleServerMessage(message)));
        this.initSpeechRecognizer();
        this.initLocalTts();
        if (skipServerConnectionForTests) {
            Log.d((String)TAG, (String)"Skipping LiveKit connection for instrumentation test");
        } else {
            this.serverConnection.connect();
        }
        this.windowManager = (WindowManager)this.getSystemService(Context.WINDOW_SERVICE);
        try {
            this.createGuidanceOverlay();
            this.createHighlightOverlay();
            this.createBallOverlay();
            this.createCloseTargetOverlay();
            this.createChatOverlay();
            this.createMicFloatOverlay();
            this.createMicAiResponseOverlay();
            this.createMicUserTranscriptOverlay();
            this.refreshLocalizedUi();
        }
        catch (WindowManager.BadTokenException e) {
            Log.e((String)TAG, "OVERLAY PERMISSION DENIED - addView failed: " + e.getMessage());
            Log.e((String)TAG, (String)"Go to Settings -> Apps -> SmartHelp+ -> Display over other apps -> Enable");
            Toast.makeText((Context)this, (CharSequence)"Permission error: Please re-grant 'Display over other apps' in Settings", (int)1).show();
            this.stopSelf();
            return;
        }
        catch (Exception e) {
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.e((String)TAG, (String)("OVERLAY CREATION FAILED: " + msg));
            Toast.makeText((Context)this, (CharSequence)("Overlay error: " + msg), (int)1).show();
            this.stopSelf();
            return;
        }
        this.registerGuidanceReceiver();
        boolean zh0 = "zh".equals(AppPrefs.getLanguage((Context)this));
        this.updateGuidanceText(zh0 ? "\u6b63\u5728\u8fde\u63a5..." : "Connecting...");
        this.syncVoiceOnlyInputSurface();
    }

    public void switchLanguage(String lang) {
        this.configureLocalTtsLanguage();
        this.refreshLocalizedUi();
        Log.d((String)TAG, (String)("Language switched to: " + lang));
    }

    private void handleServerMessage(MessageProtocol.ServerMessage message) {
        Log.d((String)TAG, (String)("handleServerMessage: " + (Object)((Object)message.getType())));
        switch (message.getType()) {
            case READY: {
                String pending;
                this.publishConnectionStatus(true);
                Log.d((String)TAG, (String)"Connected to LiveKit agent - session ready");

                // Data-side handling that must NEVER be skipped, even mid-recording —
                // dropping a pendingQuery here means a Settings-deeplink user query is silently lost.
                String savedLang = AppPrefs.getLanguage((Context)this);
                if (!"en".equals(savedLang)) {
                    this.switchLanguage(savedLang);
                }
                if ((pending = AppPrefs.getPendingQuery((Context)this)) != null) {
                    AppPrefs.clearPendingQuery((Context)this);
                    this.handler.postDelayed(() -> this.handleUserQuery(pending), 800L);
                    return;
                }

                // UI reset is the disruptive part — late READY arriving during recording
                // would otherwise reset uiState / hide the listening surface and mute the mic.
                if (this.isListening) {
                    Log.d((String)TAG, (String)"READY received while recording - skipping UI reset to keep mic alive");
                    return;
                }
                this.uiState = OverlayUiState.IDLE;
                this.awaitingCompletionFollowup = false;
                this.micControlsMinimized = false;
                boolean zh = this.isChineseUi();
                if (this.currentVoiceText == null) {
                    String welcome;
                    this.currentVoiceText = welcome = this.getWelcomePrompt();
                    this.updateGuidanceText(welcome);
                    this.addChatMessage(false, welcome);
                    this.speakLocalPrompt(welcome);  // Use local TTS for instant welcome
                    this.showInstructionBubble(welcome);
                    // Show welcome above mic
                    this.showMicAiResponse(welcome);
                }
                if (this.txtHint != null) {
                    this.txtHint.setText((CharSequence)this.getTapMicHint());
                }
                if (this.txtListening != null) {
                    this.txtListening.setVisibility(View.GONE);
                }
                this.updateQuickActionsVisibility();
                this.syncVoiceOnlyInputSurface();
                return;
            }
            case DISCONNECTED: {
                this.publishConnectionStatus(false);
                if (this.shuttingDown || !running) {
                    Log.d((String)TAG, (String)"WebSocket disconnected during shutdown; reconnect skipped");
                    return;
                }
                Log.w((String)TAG, (String)"WebSocket disconnected - will reconnect in 2s");
                this.uiState = OverlayUiState.ANALYZING;
                this.switchToInputMode();
                this.updateGuidanceText(this.getReconnectingText());
                this.handler.postDelayed(() -> {
                    if (!this.shuttingDown && running && this.serverConnection != null && !this.serverConnection.isConnected()) {
                        this.serverConnection.connect();
                    }
                }, 2000L);
                return;
            }
            case HIGHLIGHT: {
                this.hideMicStatus();
                this.hideMicAiResponse();
                this.hideThinking();
                this.awaitingGuidedUserAction = false;
                // Switch to ball mode when we receive actual task guidance
                if (!this.isBallMode) {
                    this.switchToBallMode();
                }
                this.session.incrementStepCount();
                this.updateStepProgress();
                this.session.setReferenceScreenshotHash(ScreenCaptureService.latestScreenshotHash);
                this.noTapReminderCount = 0;
                int x = message.getHighlightX();
                int y = message.getHighlightY();
                boolean highlightCompleted = message.isCompleted();
                if (message.isBlockerDetected()) {
                    Log.d((String)TAG, (String)("Blocker detected: " + message.getBlockerReason()));
                }
                if (highlightCompleted) {
                    this.hideHighlight();
                    this.hideDoneButton();
                    this.cancelAutoAnalysis();
                } else if (x == 50 && y == 50) {
                    this.session.clearLastHighlight();
                    String direction = message.getDirection();
                    if (direction != null && !direction.isEmpty()) {
                        this.showScrollDirection(direction);
                        this.awaitingGuidedUserAction = true;
                    } else {
                        this.showHighlightPercent(50, 50);
                    }
                    this.cancelAutoAnalysis();
                } else if (x >= 0 && y >= 0) {
                    this.showHighlight(message);
                    this.showTapArrowGuidance(message);
                    this.awaitingGuidedUserAction = true;
                    this.scheduleAutoAnalysis();
                } else {
                    this.session.clearLastHighlight();
                    this.hideHighlight();
                    this.cancelAutoAnalysis();
                }
                if (OverlayService.shouldShowTaskActionButtons(highlightCompleted, this.session.hasActiveTask())) {
                    this.showDoneButton();
                }
                return;
            }
            case TASK_COMPLETE: {
                this.uiState = OverlayUiState.COMPLETED;
                this.hideMicStatus();
                this.hideHighlight();
                this.hideDoneButton();
                this.cancelAutoAnalysis();
                this.awaitingGuidedUserAction = false;
                this.noTapReminderCount = 0;
                this.session.markCompleted();
                this.awaitingCompletionFollowup = true;
                String followupMsg = this.getCompletionFollowupPrompt();
                this.updateGuidanceText(followupMsg);
                if (this.isBallMode) {
                    this.showInstructionBubble(followupMsg);
                }
                this.addChatMessage(false, followupMsg);
                this.currentVoiceText = followupMsg;
                this.speakNaturalPrompt(followupMsg);
                this.updateStepProgress();
                this.updateQuickActionsVisibility();
                Log.d((String)TAG, (String)("Task complete: " + message.getGoal()));
                // Delay the Yes/No overlay so the AI's final hand-off
                // sentence (e.g. "Pick a photo. Tap the green Send button.")
                // and the "All done. Do you need anything else?" follow-up
                // both have time to play through TTS before the buttons
                // appear. Without this delay the overlay flashes onto the
                // screen before the user finishes hearing the guidance.
                final String completionGoal = message.getGoal();
                this.handler.postDelayed(() -> {
                    // Re-check state in case user cancelled or started a new
                    // task during the 3 s wait — don't pop a stale overlay.
                    if (this.uiState == OverlayUiState.COMPLETED
                            && this.awaitingCompletionFollowup) {
                        this.showTaskCompleteDialog(completionGoal, this.isChineseUi());
                    }
                }, TASK_COMPLETE_DIALOG_DELAY_MS);
                return;
            }
            case THINKING: {
                this.hideMicStatus();
                this.uiState = OverlayUiState.ANALYZING;
                this.showThinking();
                return;
            }
            case REQUEST_SCREENSHOT: {
                this.hideMicStatus();
                this.hideHighlight();
                this.hideDoneButton();
                this.cancelAutoAnalysis();
                this.awaitingGuidedUserAction = false;
                this.noTapReminderCount = 0;
                // Replace stale instruction (from the previous step / timeout
                // fallback) with a Capturing placeholder so the user does not
                // see "Hold on, looking again" while we are actually mid-step.
                // After the screenshot is captured and sent (SCREENSHOT_READY
                // handler), the bubble switches to "Analyzing...".
                String capturingMsg = this.getCapturingText();
                this.currentVoiceText = capturingMsg;
                this.updateGuidanceText(capturingMsg);
                this.showInstructionBubble(capturingMsg);
                this.showThinking();
                this.session.incrementStepCount();
                this.updateStepProgress();
                if (this.pendingWhatsAppDemoLaunchOnScreenshot) {
                    this.pendingWhatsAppDemoLaunchOnScreenshot = false;
                    if (this.launchWhatsAppForDemo()) {
                        this.handler.postDelayed(() -> this.analyzeCurrentScreen(false), 1400L);
                        return;
                    }
                }
                this.analyzeCurrentScreen(false);
                return;
            }
            case ERROR: {
                this.publishConnectionStatus(false);
                Log.e((String)TAG, (String)("LiveClient error: " + message.getText()));
                this.hideMicStatus();
                this.showError(this.getAssistantUnavailableText());
                this.stopListening();
                return;
            }
            case TRANSCRIPTION: {
                String transcript;
                boolean isPartial = message.isPartial();
                String string = transcript = message.getText() == null ? "" : message.getText().trim();
                if (transcript.isEmpty()) {
                    if (!isPartial) {
                        // Final empty transcription - clear the display
                        this.latestLiveTranscript = "";
                    }
                    return;
                }
                this.latestLiveTranscript = transcript;
                if (this.txtHint != null) {
                    this.txtHint.setVisibility(View.GONE);
                }
                // Show transcript above mic (partial or final)
                this.showMicTranscript(transcript, !isPartial);
                if (!isPartial) {
                    this.addFinalTranscriptToChat(transcript);
                }
                if (this.isBallMode) {
                    this.showInstructionBubble(transcript);
                }
                if (this.isListening) {
                    this.scheduleSilenceTimer();
                } else if (!isPartial && this.pendingTranscriptSubmitRunnable != null) {
                    this.scheduleTranscriptSubmission();
                }
                return;
            }
            case TEXT: {
                String serverText = this.sanitizeGuidanceText(message.getText());
                if (!serverText.isEmpty()) {
                    boolean suppressActionText = this.session != null
                            && this.session.hasActiveTask()
                            && this.isTapActionText(serverText)
                            && !this.looksLikeServerQuestion(serverText);
                    if (message.shouldDisplay() && !suppressActionText) {
                        this.hideMicStatus();
                        this.hideThinking();
                        this.currentVoiceText = serverText;
                        this.updateGuidanceText(serverText);
                        if (this.isBallMode) {
                            this.showInstructionBubble(serverText);
                            this.hideMicAiResponse();
                        } else {
                            // Show AI response above mic only while the full input UI is active.
                            // Ball mode already has its own instruction bubble; showing both
                            // creates duplicate labels like "Tap Gallery" on the screen.
                            this.showMicAiResponse(serverText);
                        }
                        this.addChatMessage(false, serverText);
                        this.awaitingServerReply = this.looksLikeServerQuestion(serverText);
                        this.updateQuickActionsVisibility();
                        if (this.awaitingServerReply) {
                            String answerHint;
                            this.switchToInputMode();
                            String string = answerHint = this.isChineseUi() ? "\u8bf4\u51fa\u7b54\u6848\uff0c\u6216\u70b9\u952e\u76d8\u8f93\u5165" : "Speak your answer, or tap the keyboard";
                            if (this.txtHint != null) {
                                this.txtHint.setText((CharSequence)answerHint);
                                this.txtHint.setVisibility(View.VISIBLE);
                            }
                        }
                    }
                    if (suppressActionText) {
                        this.hideMicAiResponse();
                    }
                    if (message.shouldSpeak() && !suppressActionText) {
                        this.speakLocalPrompt(serverText);
                    }
                }
                return;
            }
            case CONNECTING: {
                this.publishConnectionStatus(null);
                return;
            }
            case AUDIO: {
                this.playAiVoice(message.getAudioData(), message.getAudioText());
                return;
            }
        }
    }

    private void publishConnectionStatus(Boolean connected) {
        liveKitConnected = connected;
        Intent intent = new Intent(ACTION_CONNECTION_STATUS);
        intent.setPackage(this.getPackageName());
        if (connected != null) {
            intent.putExtra(EXTRA_CONNECTED, (Serializable)connected);
        }
        this.sendBroadcast(intent);
    }

    private void showTapArrowGuidance() {
        String message = this.getTapArrowText();
        this.currentVoiceText = message;
        this.updateGuidanceText(message);
        if (this.isBallMode) {
            this.showInstructionBubble(message);
        }
        this.speakTapArrowGuidance(message);
    }

    private void showTapArrowGuidance(MessageProtocol.ServerMessage highlight) {
        String instruction = this.getHighlightInstructionText(highlight);
        this.currentVoiceText = instruction;
        this.updateGuidanceText(instruction);
        if (this.isBallMode) {
            this.showInstructionBubble(instruction);
        }
        this.speakTapArrowGuidance(instruction);
    }

    private void speakTapArrowGuidance(String instruction) {
        // Speak whatever is shown on screen ("Tap WhatsApp", "Tap Attach", ...)
        // instead of the generic "Please tap the orange dot". The server
        // intentionally suppresses Gemini TTS for action guidance to avoid the
        // ~3s synth latency on fast UI steps; the local TTS line MUST therefore
        // match the visible instruction or the user hears one thing and sees
        // another.
        String spoken = (instruction == null || instruction.trim().isEmpty())
                ? this.getTapArrowText()
                : instruction;
        long now = System.currentTimeMillis();
        boolean differentFromLast = !spoken.equals(this.lastTapArrowSpeechText);
        boolean debounceElapsed = (now - this.lastTapArrowSpeechAtMs) >= TAP_ARROW_SPEECH_DEBOUNCE_MS;
        if (differentFromLast || debounceElapsed) {
            this.lastTapArrowSpeechAtMs = now;
            this.lastTapArrowSpeechText = spoken;
            this.speakLocalPrompt(spoken);
        }
    }

    private String getHighlightInstructionText(MessageProtocol.ServerMessage highlight) {
        if (highlight == null) {
            return this.getTapArrowText();
        }
        String target = this.cleanHighlightTarget(highlight.getTarget());
        if (target.isEmpty() || this.isGalleryTarget(target)) {
            return this.getTapArrowText();
        }
        return this.isChineseUi() ? "\u8bf7\u70b9\u51fb " + target : "Tap " + target;
    }

    private String cleanHighlightTarget(String target) {
        if (target == null) {
            return "";
        }
        String cleaned = this.sanitizeGuidanceText(target).trim();
        int resourceIndex = cleaned.indexOf(" com.");
        if (resourceIndex >= 0) {
            cleaned = cleaned.substring(0, resourceIndex).trim();
        }
        return cleaned;
    }

    private boolean isGalleryTarget(String target) {
        String normalized = target == null ? "" : target.trim().toLowerCase();
        return normalized.equals("gallery")
                || normalized.equals("\u76f8\u518c")
                || normalized.equals("\u56fe\u5e93")
                || normalized.contains("gallery_spinner");
    }

    private void updateGuidanceText(String text) {
        this.handler.post(() -> {
            this.hideError();
            if (this.txtGuidance != null) {
                this.txtGuidance.setText((CharSequence)this.sanitizeGuidanceText(text));
            }
            if (VOICE_ONLY_OVERLAY) {
                this.hidePanelOverlays();
                return;
            }
            if (this.cardGuidance != null) {
                this.cardGuidance.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hidePanelOverlays() {
        if (this.cardGuidance != null) {
            this.cardGuidance.setVisibility(View.GONE);
        }
        if (this.cardUserQuery != null) {
            this.cardUserQuery.setVisibility(View.GONE);
        }
        if (this.cardInputBar != null) {
            this.cardInputBar.setVisibility(View.GONE);
        }
        if (this.cardError != null) {
            this.cardError.setVisibility(View.GONE);
        }
        if (this.layoutThinking != null) {
            this.layoutThinking.setVisibility(View.GONE);
        }
        if (this.overlayView != null) {
            this.overlayView.setVisibility(View.GONE);
        }
        if (!VOICE_ONLY_OVERLAY && this.ballView != null && !this.micControlsMinimized && !this.shouldFloatingBallShowGuidance()) {
            this.ballView.setVisibility(View.GONE);
        }
        this.hideInstructionBubble();
    }

    private String sanitizeGuidanceText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\x00-\\x08\\x0E-\\x1F\\x7F\\x80-\\x9F]", "").replaceAll("[\u2190\u2191\u2193\u2192\u25c4\u25ba\u25b2\u25bc\u2605\u2606\u2660\u2663\u2665\u2666\u25d8\u25cb\u25d9\u2642\u2640\u266a\u266b\u263c\u25ba\u25c4\u2195\u203c\u00b6\u00a7\u25ac\u21a8\u2191\u2193\u2192\u2190\u221f\u2194\u25b2\u25bc]", "").trim();
    }

    private void hideForScreenCapture() {
        this.restoreOverlayAfterCapture = this.overlayView != null && this.overlayView.getVisibility() == View.VISIBLE;
        this.restoreHighlightAfterCapture = this.highlightRenderer != null && this.highlightRenderer.isVisible();
        this.restoreBallAfterCapture = this.ballView != null && this.ballView.getVisibility() == View.VISIBLE;
        boolean bl = this.restoreChatAfterCapture = this.chatView != null && this.chatView.getVisibility() == View.VISIBLE;
        if (this.overlayView != null) {
            this.overlayView.setVisibility(View.INVISIBLE);
        }
        if (this.highlightRenderer != null) {
            this.highlightRenderer.temporaryHide();
        }
        if (this.ballView != null) {
            this.ballView.setVisibility(View.INVISIBLE);
        }
        if (this.closeTargetView != null) {
            this.closeTargetView.setVisibility(View.INVISIBLE);
        }
        if (this.chatView != null) {
            this.chatView.setVisibility(View.INVISIBLE);
        }
    }

    private void restoreAfterScreenCapture() {
        if (VOICE_ONLY_OVERLAY) {
            this.hidePanelOverlays();
            this.syncVoiceOnlyInputSurface();
            return;
        }
        if (this.restoreOverlayAfterCapture && this.overlayView != null && !this.isBallMode) {
            this.overlayView.setVisibility(View.VISIBLE);
        }
        if (this.restoreHighlightAfterCapture && this.highlightRenderer != null && this.session.hasLastHighlight()) {
            this.highlightRenderer.restoreVisible();
        }
        if (this.restoreBallAfterCapture && this.ballView != null && this.isBallMode) {
            this.ballView.setVisibility(View.VISIBLE);
        }
        if (this.restoreChatAfterCapture && this.chatView != null && this.isChatOpen) {
            this.chatView.setVisibility(View.VISIBLE);
        }
        if (this.isCloseTargetVisible && this.closeTargetView != null) {
            this.closeTargetView.setVisibility(View.VISIBLE);
        }
    }

    private void showError(String message) {
        this.handler.post(() -> {
            this.uiState = OverlayUiState.ERROR;
            this.switchToInputMode();
            if (this.txtErrorMessage != null) {
                this.txtErrorMessage.setText((CharSequence)message);
            }
            if (this.cardError != null) {
                this.cardError.setVisibility(View.VISIBLE);
            }
            if (this.cardGuidance != null) {
                this.cardGuidance.setVisibility(View.GONE);
            }
            if (this.cardInputBar != null) {
                this.cardInputBar.setVisibility(View.GONE);
            }
            if (VOICE_ONLY_OVERLAY) {
                this.hidePanelOverlays();
                this.speakLocalPrompt(message);
                return;
            }
            this.updateQuickActionsVisibility();
        });
    }

    private void hideError() {
        if (this.cardError != null) {
            this.cardError.setVisibility(View.GONE);
        }
        // cardInputBar removed ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“ floating mic overlay handles input
        if (this.txtHint != null && !this.isListening) {
            this.txtHint.setVisibility(View.VISIBLE);
        }
        this.updateQuickActionsVisibility();
    }

    private void updateStepProgress() {
        this.handler.post(() -> {
            if (this.txtStepProgress != null) {
                this.txtStepProgress.setVisibility(View.GONE);
            }
            this.updateBallStepViews();
        });
    }

    private void showMicStatus(String text, boolean autoClear) {
        this.handler.post(() -> {
            if (this.clearMicStatusRunnable != null) {
                this.handler.removeCallbacks(this.clearMicStatusRunnable);
                this.clearMicStatusRunnable = null;
            }
            if (this.txtListening != null) {
                this.txtListening.setText((CharSequence)text);
                this.txtListening.setVisibility(View.VISIBLE);
            }
            if (this.txtHint != null) {
                this.txtHint.setVisibility(View.GONE);
            }
            if (this.hideMicHintRunnable != null) {
                this.handler.removeCallbacks(this.hideMicHintRunnable);
                this.hideMicHintRunnable = null;
            }
            if (this.txtMicHint != null) {
                this.txtMicHint.animate().cancel();
                this.txtMicHint.setText((CharSequence)text);
                this.txtMicHint.setAlpha(1.0f);
                this.txtMicHint.setVisibility(View.VISIBLE);
            }
            if (autoClear) {
                this.clearMicStatusRunnable = this::hideMicStatus;
                this.handler.postDelayed(this.clearMicStatusRunnable, 1800L);
            }
        });
    }

    private void showMicTranscript(String transcript, boolean finalTranscript) {
        String trimmed = transcript == null ? "" : transcript.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        String prefix;
        if (finalTranscript) {
            prefix = this.isChineseUi() ? "\u6211\u542c\u5230\uff1a" : "Heard: ";
        } else {
            prefix = this.isChineseUi() ? "\u6b63\u5728\u8bc6\u522b\uff1a" : "Hearing: ";
        }
        String labelled = prefix + trimmed;
        this.showMicStatus(labelled, false);
        this.showMicUserTranscriptOverlay(labelled, finalTranscript);
    }

    private void hideMicStatus() {
        this.handler.post(() -> {
            if (this.clearMicStatusRunnable != null) {
                this.handler.removeCallbacks(this.clearMicStatusRunnable);
                this.clearMicStatusRunnable = null;
            }
            if (this.txtListening != null && !this.isListening) {
                this.txtListening.setVisibility(View.GONE);
            }
            if (this.txtHint != null && !this.isListening) {
                this.txtHint.setVisibility(View.VISIBLE);
            }
            if (this.txtMicHint != null && !this.isListening) {
                this.txtMicHint.setText((CharSequence)this.getMicFloatHintText());
                this.hideTemporaryMicHint();
            }
        });
    }

    private void startMicGlow() {
        this.handler.post(() -> {
            if (this.micGlowView == null) {
                return;
            }
            this.micGlowView.setVisibility(View.VISIBLE);
            this.micGlowView.setAlpha(0.45f);
            this.micGlowView.setScaleX(0.92f);
            this.micGlowView.setScaleY(0.92f);
            if (this.micGlowAnimator != null) {
                this.micGlowAnimator.cancel();
            }
            this.micGlowAnimator = ValueAnimator.ofFloat((float[])new float[]{0.0f, 1.0f});
            this.micGlowAnimator.setDuration(900L);
            this.micGlowAnimator.setRepeatCount(-1);
            this.micGlowAnimator.setRepeatMode(ValueAnimator.REVERSE);
            this.micGlowAnimator.addUpdateListener(animation -> {
                float t = ((Float)animation.getAnimatedValue()).floatValue();
                float scale = 0.92f + 0.2f * t;
                float alpha = 0.3f + 0.35f * t;
                this.micGlowView.setScaleX(scale);
                this.micGlowView.setScaleY(scale);
                this.micGlowView.setAlpha(alpha);
            });
            this.micGlowAnimator.start();
            if (this.btnMicrophone instanceof FloatingActionButton) {
                ((FloatingActionButton)this.btnMicrophone).setImageTintList(ColorStateList.valueOf((int)-1096636));
            }
            if (this.voiceWaveView != null) {
                this.voiceWaveView.setVisibility(View.VISIBLE);
                this.voiceWaveView.startAnimation();
            }
        });
    }

    private void stopMicGlow() {
        this.handler.post(() -> {
            if (this.micGlowAnimator != null) {
                this.micGlowAnimator.cancel();
                this.micGlowAnimator = null;
            }
            if (this.micGlowView != null) {
                this.micGlowView.setVisibility(View.GONE);
                this.micGlowView.setAlpha(1.0f);
                this.micGlowView.setScaleX(1.0f);
                this.micGlowView.setScaleY(1.0f);
            }
            if (this.btnMicrophone instanceof FloatingActionButton) {
                ((FloatingActionButton)this.btnMicrophone).setImageTintList(ColorStateList.valueOf((int)-1));
            }
            if (this.voiceWaveView != null) {
                this.voiceWaveView.stopAnimation();
                this.voiceWaveView.setVisibility(View.GONE);
            }
        });
    }

    private void initLocalTts() {
        this.localTts = new TextToSpeech((Context)this, status -> {
            boolean bl = this.localTtsReady = status == 0;
            if (!this.localTtsReady) {
                Log.w((String)TAG, (String)"Local TTS unavailable");
                return;
            }
            this.configureLocalTtsLanguage();
            if (this.pendingLocalSpeech != null && !this.pendingLocalSpeech.isEmpty()) {
                String queued = this.pendingLocalSpeech;
                this.pendingLocalSpeech = null;
                this.speakLocalPrompt(queued);
            }
        });
    }

    private void configureLocalTtsLanguage() {
        if (this.localTts == null || !this.localTtsReady) {
            return;
        }
        Locale locale = this.isChineseUi() ? Locale.SIMPLIFIED_CHINESE : Locale.US;
        int result = this.localTts.setLanguage(locale);
        if (result == -1 || result == -2) {
            Log.w((String)TAG, (String)("Local TTS language not fully supported: " + locale));
        }
        this.localTts.setSpeechRate(0.85f);
        this.localTts.setPitch(1.05f);
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                Set<Voice> voices = this.localTts.getVoices();
                if (voices != null && !voices.isEmpty()) {
                    Voice bestVoice = null;
                    String langTag = this.isChineseUi() ? "zh-CN" : "en-US";
                    for (Voice v : voices) {
                        if (v.getQuality() <= 300 || v.getName() == null || !v.getName().toLowerCase(Locale.ROOT).contains("network") || bestVoice != null && v.getQuality() <= bestVoice.getQuality()) continue;
                        bestVoice = v;
                    }
                    if (bestVoice == null) {
                        for (Voice v : voices) {
                            if (v.getQuality() <= 300 || v.getName() == null || !v.getName().toLowerCase(Locale.ROOT).contains("enhanced") && !v.getName().toLowerCase(Locale.ROOT).contains("high") || bestVoice != null && v.getQuality() <= bestVoice.getQuality()) continue;
                            bestVoice = v;
                        }
                    }
                    if (bestVoice != null) {
                        this.localTts.setVoice(bestVoice);
                        Log.d((String)TAG, (String)("TTS voice selected: " + bestVoice.getName() + " quality=" + bestVoice.getQuality()));
                    }
                }
            }
            catch (Exception e) {
                Log.w((String)TAG, (String)("TTS voice selection failed: " + e.getMessage()));
            }
        }
    }

    private void speakLocalPrompt(String text) {
        String message;
        String string = message = text == null ? "" : text.trim();
        if (message.isEmpty()) {
            return;
        }
        if (this.localTts == null || !this.localTtsReady) {
            this.pendingLocalSpeech = message;
            return;
        }
        this.stopAiVoicePlayback();
        this.configureLocalTtsLanguage();
        this.localTts.speak((CharSequence)message, 0, null, "smarthelp-fixed-" + System.currentTimeMillis());
    }

    private void speakNaturalPrompt(String text) {
        String message;
        String string = message = text == null ? "" : text.trim();
        if (message.isEmpty()) {
            return;
        }
        if (this.serverConnection != null) {
            this.serverConnection.requestSpeech(message);
            return;
        }
        this.speakLocalPrompt(message);
    }

    private void playAiVoice(String base64Audio, String sourceText) {
        block13: {
            String fallbackText;
            String audio = base64Audio == null ? "" : base64Audio.trim();
            String string = fallbackText = sourceText == null || sourceText.trim().isEmpty() ? this.currentVoiceText : sourceText.trim();
            if (fallbackText != null && this.currentVoiceText != null) {
                String incomingText = this.sanitizeGuidanceText(fallbackText);
                String activeText = this.currentVoiceText.trim();
                if (!incomingText.isEmpty() && !incomingText.equals(activeText)) {
                    Log.d((String)TAG, (String)"Skipping stale AI voice for previous guidance");
                    return;
                }
                String string2 = fallbackText = incomingText.isEmpty() ? fallbackText : incomingText;
            }
            if (audio.isEmpty()) {
                if (fallbackText != null && !fallbackText.trim().isEmpty()) {
                    this.speakLocalPrompt(fallbackText);
                }
                return;
            }
            String fallbackSpeechText = fallbackText;
            try {
                MediaPlayer player;
                this.stopAiVoicePlayback();
                byte[] wavBytes = Base64.decode((String)audio, (int)0);
                File voiceFile = new File(this.getCacheDir(), "smarthelp_ai_voice.wav");
                try (FileOutputStream output = new FileOutputStream(voiceFile);){
                    output.write(wavBytes);
                }
                if (this.localTts != null) {
                    this.localTts.stop();
                }
                this.aiVoicePlayer = player = new MediaPlayer();
                this.lastAiVoiceAudioData = audio;
                this.lastAiVoiceText = fallbackText == null ? null : fallbackText.trim();
                player.setDataSource(voiceFile.getAbsolutePath());
                player.setOnPreparedListener(mp -> {
                    mp.start();
                    // Start typewriter animation when audio starts playing
                    if (fallbackSpeechText != null && !fallbackSpeechText.isEmpty()) {
                        this.startTypewriterAnimation(fallbackSpeechText, mp.getDuration());
                    }
                });
                player.setOnCompletionListener(this::releaseAiVoicePlayer);
                player.setOnErrorListener((mp, what, extra) -> {
                    this.releaseAiVoicePlayer(mp);
                    if (fallbackSpeechText != null && !fallbackSpeechText.trim().isEmpty()) {
                        this.speakLocalPrompt(fallbackSpeechText);
                    }
                    return true;
                });
                player.prepareAsync();
            }
            catch (IOException | IllegalArgumentException e) {
                Log.w((String)TAG, (String)("AI voice playback failed: " + e.getMessage()));
                if (fallbackText == null || fallbackText.trim().isEmpty()) break block13;
                this.speakLocalPrompt(fallbackText);
            }
        }
    }

    private void stopAiVoicePlayback() {
        this.stopTypewriterAnimation();
        MediaPlayer player = this.aiVoicePlayer;
        if (player == null) {
            return;
        }
        this.aiVoicePlayer = null;
        try {
            if (player.isPlaying()) {
                player.stop();
            }
        }
        catch (IllegalStateException illegalStateException) {
            // empty catch block
        }
        this.releaseAiVoicePlayer(player);
    }

    private void releaseAiVoicePlayer(MediaPlayer player) {
        if (player == null) {
            return;
        }
        if (this.aiVoicePlayer == player) {
            this.aiVoicePlayer = null;
        }
        try {
            player.reset();
            player.release();
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    private Runnable typewriterRunnable;
    private int typewriterIndex = 0;

    private void startTypewriterAnimation(String fullText, int durationMs) {
        this.runOnMainThread(() -> {
            // Cancel any existing animation
            if (this.typewriterRunnable != null) {
                this.handler.removeCallbacks(this.typewriterRunnable);
            }

            String text = fullText == null ? "" : fullText.trim();
            if (text.isEmpty()) return;

            // Calculate delay per character (spread across 80% of audio duration)
            int animDuration = (int)(durationMs * 0.8);
            int charDelay = Math.max(30, animDuration / text.length());

            this.typewriterIndex = 0;
            this.typewriterRunnable = new Runnable() {
                @Override
                public void run() {
                    if (typewriterIndex <= text.length()) {
                        String partial = text.substring(0, typewriterIndex);
                        updateGuidanceText(partial);
                        typewriterIndex++;
                        handler.postDelayed(this, charDelay);
                    }
                }
            };
            // Start with empty text
            updateGuidanceText("");
            this.handler.postDelayed(this.typewriterRunnable, 200); // Small initial delay
        });
    }

    private void stopTypewriterAnimation() {
        if (this.typewriterRunnable != null) {
            this.handler.removeCallbacks(this.typewriterRunnable);
            this.typewriterRunnable = null;
        }
    }

    private String getWelcomePrompt() {
        return this.isChineseUi() ? "\u6709\u4ec0\u4e48\u53ef\u4ee5\u5e2e\u5230\u60a8\uff1f" : "How can I help you?";
    }

    private String getTapMicHint() {
        return this.isChineseUi() ? "\u70b9\u4e00\u4e0b\u9ea6\u514b\u98ce\uff0c\u7136\u540e\u8bf4\u8bdd" : "Tap the mic, then speak";
    }

    private String getMicFloatHintText() {
        return this.isChineseUi() ? "\u6309\u4f4f\u8bf4\u8bdd\uff0c\u6216\u70b9\u952e\u76d8\u8f93\u5165" : "Hold to speak, or tap keyboard";
    }

    private String getCompletionFollowupPrompt() {
        return this.isChineseUi() ? "\u5b8c\u6210\u4e86\u3002\u8fd8\u9700\u8981\u5176\u4ed6\u5e2e\u52a9\u5417\uff1f" : "All done. Do you need anything else?";
    }

    private boolean isNoMoreHelpIntent(String text) {
        String value;
        String string = value = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return false;
        }
        if (value.matches(".*\\b(no|nope|no thanks|nothing|that's all|that is all|not now|no need|i'm done|im done|close|stop)\\b.*")) {
            return true;
        }
        return value.contains("\u4e0d\u9700\u8981") || value.contains("\u4e0d\u7528") || value.contains("\u4e0d\u8981\u4e86") || value.contains("\u6ca1\u6709") || value.contains("\u6c92\u6709") || value.contains("\u591f\u4e86") || value.contains("tak perlu") || value.contains("tidak perlu") || value.contains("tak nak") || value.contains("sudah");
    }

    private void initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable((Context)this)) {
            Log.w((String)TAG, (String)"SpeechRecognizer not available on this device");
            return;
        }
        this.speechRecognizer = SpeechRecognizer.createSpeechRecognizer((Context)this);
        this.speechRecognizer.setRecognitionListener(new RecognitionListener(){

            public void onReadyForSpeech(Bundle params) {
                OverlayService.this.showMicStatus(OverlayService.this.getListeningText(), false);
            }

            public void onBeginningOfSpeech() {
            }

            public void onRmsChanged(float rmsdB) {
            }

            public void onBufferReceived(byte[] buffer) {
            }

            public void onEndOfSpeech() {
                if (OverlayService.this.latestLiveTranscript != null && !OverlayService.this.latestLiveTranscript.trim().isEmpty()) {
                    OverlayService.this.showMicTranscript(OverlayService.this.latestLiveTranscript.trim(), true);
                } else {
                    OverlayService.this.showMicStatus(OverlayService.this.getHeardYouText(), false);
                }
            }

            public void onError(int error) {
                String transcript = OverlayService.this.latestLiveTranscript == null ? "" : OverlayService.this.latestLiveTranscript.trim();
                boolean stopRequested = OverlayService.this.speechStopRequested;
                OverlayService.this.speechStopRequested = false;
                OverlayService.this.isListening = false;
                OverlayService.this.stopMicGlow();
                if (stopRequested || error == 5) {
                    if (!transcript.isEmpty()) {
                        OverlayService.this.submitRecognizedSpeech(transcript);
                    }
                    return;
                }
                if (error == 7) {
                    if (!transcript.isEmpty()) {
                        OverlayService.this.submitRecognizedSpeech(transcript);
                    } else {
                        boolean zh = OverlayService.this.isChineseUi();
                        OverlayService.this.showMicStatus(OverlayService.this.getDidNotCatchRetryText(), false);
                        OverlayService.this.handler.postDelayed(() -> {
                            if (!OverlayService.this.isListening && !OverlayService.this.isBallMode) {
                                OverlayService.this.startListening(true);
                            }
                        }, 600L);
                    }
                    return;
                }
                switch (error) {
                    case 6: {
                        boolean zh2 = OverlayService.this.isChineseUi();
                        OverlayService.this.showMicStatus(OverlayService.this.getDidNotCatchRetryText(), false);
                        OverlayService.this.handler.postDelayed(() -> {
                            if (!OverlayService.this.isListening && !OverlayService.this.isBallMode) {
                                OverlayService.this.startListening(true);
                            }
                        }, 600L);
                        break;
                    }
                    case 12: 
                    case 13: {
                        OverlayService.this.showMicStatus(OverlayService.this.getLanguageUnavailableText(), true);
                        break;
                    }
                    case 9: {
                        OverlayService.this.handleSpeechPermissionIssue("SpeechRecognizer onError(ERROR_INSUFFICIENT_PERMISSIONS)");
                        break;
                    }
                    default: {
                        OverlayService.this.showMicStatus(OverlayService.this.getVoiceUnavailableText(), true);
                        Log.w((String)OverlayService.TAG, (String)("SpeechRecognizer error: " + error));
                    }
                }
            }

            public void onResults(Bundle results) {
                OverlayService.this.speechStopRequested = false;
                OverlayService.this.isListening = false;
                OverlayService.this.cancelSilenceTimer();
                OverlayService.this.stopMicGlow();
                String transcript = OverlayService.this.extractSpeechResult(results);
                if (transcript.isEmpty()) {
                    transcript = OverlayService.this.latestLiveTranscript == null ? "" : OverlayService.this.latestLiveTranscript.trim();
                }
                OverlayService.this.submitRecognizedSpeech(transcript);
            }

            public void onPartialResults(Bundle partialResults) {
                String transcript = OverlayService.this.extractSpeechResult(partialResults);
                if (transcript.isEmpty()) {
                    return;
                }
                OverlayService.this.latestLiveTranscript = transcript;
                if (OverlayService.this.txtHint != null) {
                    OverlayService.this.txtHint.setVisibility(View.GONE);
                }
                OverlayService.this.showMicTranscript(transcript, false);
            }

            public void onEvent(int eventType, Bundle params) {
            }
        });
        this.refreshSpeechRecognizerIntent();
    }

    private void refreshSpeechRecognizerIntent() {
        Intent intent = new Intent("android.speech.action.RECOGNIZE_SPEECH");
        String languageTag = this.isChineseUi() ? "zh-CN" : "en-US";
        intent.putExtra("android.speech.extra.LANGUAGE_MODEL", "free_form");
        intent.putExtra("android.speech.extra.PARTIAL_RESULTS", true);
        intent.putExtra("android.speech.extra.MAX_RESULTS", 3);
        intent.putExtra("android.speech.extra.LANGUAGE", languageTag);
        intent.putExtra("android.speech.extra.LANGUAGE_PREFERENCE", languageTag);
        intent.putExtra("calling_package", this.getPackageName());
        intent.putExtra("android.speech.extra.PREFER_OFFLINE", false);
        intent.putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 800L);
        intent.putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 500L);
        intent.putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 0L);
        this.speechRecognizerIntent = intent;
    }

    private String extractSpeechResult(Bundle results) {
        if (results == null) {
            return "";
        }
        ArrayList matches = results.getStringArrayList("results_recognition");
        if (matches == null || matches.isEmpty()) {
            return "";
        }
        String best = (String)matches.get(0);
        return best == null ? "" : best.trim();
    }

    private void startLocalSpeechRecognition() {
        if (this.serverConnection == null) {
            this.showMicStatus(this.getVoiceUnavailableText(), true);
            return;
        }
        this.speechStopRequested = false;
        this.latestLiveTranscript = null;
        this.cancelSilenceTimer();
        
        // Use LiveKit for all microphone input
        try {
            this.serverConnection.startMicrophone();
            // Show "Preparing..." during the LiveKit mic warmup window so the user
            // doesn't start speaking before the track is actually publishing.
            this.showMicStatus(this.getPreparingMicText(), false);
            this.handler.postDelayed(() -> {
                if (this.isListening) {
                    this.showMicStatus(this.getListeningText(), false);
                }
            }, MIC_WARMUP_MS);
            this.scheduleSilenceTimer();
        }
        catch (Exception e) {
            Log.e((String)TAG, (String)("Failed to start microphone: " + e.getMessage()), (Throwable)e);
            this.showMicStatus(this.getVoiceUnavailableText(), true);
            this.isListening = false;
        }
    }

    private void stopLocalSpeechRecognition() {
        this.speechStopRequested = true;
        // Stop LiveKit microphone (sends audio_end to trigger STT)
        if (this.serverConnection != null) {
            try {
                this.serverConnection.stopMicrophone();
            }
            catch (Exception e) {
                Log.w((String)TAG, (String)("Failed to stop microphone: " + e.getMessage()));
            }
        }
    }

    private void submitRecognizedSpeech(String transcript) {
        String finalTranscript;
        String string = finalTranscript = transcript == null ? "" : transcript.trim();
        if (this.pendingTranscriptSubmitRunnable != null) {
            this.handler.removeCallbacks(this.pendingTranscriptSubmitRunnable);
        }
        this.pendingTranscriptSubmitRunnable = () -> {
            this.pendingTranscriptSubmitRunnable = null;
            if (finalTranscript.isEmpty()) {
                this.showMicStatus(this.getDidNotCatchText(), true);
                return;
            }
            this.latestLiveTranscript = finalTranscript;
            this.showMicTranscript(finalTranscript, true);
            this.handleUserQuery(finalTranscript);
        };
        this.handler.postDelayed(this.pendingTranscriptSubmitRunnable, 700L);
    }

    private void createGuidanceOverlay() {
        ContextThemeWrapper contextThemeWrapper = new ContextThemeWrapper((Context)this, R.style.Theme_SmartHelp);
        LayoutInflater inflater = (LayoutInflater)contextThemeWrapper.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        this.overlayView = inflater.inflate(R.layout.overlay_guidance, null);
        this.txtGuidance = (TextView)this.overlayView.findViewById(R.id.txtGuidance);
        this.txtUserQuery = (TextView)this.overlayView.findViewById(R.id.txtUserQuery);
        this.txtListening = (TextView)this.overlayView.findViewById(R.id.txtListening);
        this.txtHint = (TextView)this.overlayView.findViewById(R.id.txtHint);
        this.txtQuickActionsTitle = (TextView)this.overlayView.findViewById(R.id.txtQuickActionsTitle);
        this.txtQuickActionsSubtitle = (TextView)this.overlayView.findViewById(R.id.txtQuickActionsSubtitle);
        this.txtWelcomeLabel = (TextView)this.overlayView.findViewById(R.id.txtWelcomeLabel);
        this.txtWelcomeSubtitle = (TextView)this.overlayView.findViewById(R.id.txtWelcomeSubtitle);
        this.txtInputLabel = (TextView)this.overlayView.findViewById(R.id.txtInputLabel);
        this.layoutThinking = this.overlayView.findViewById(R.id.layoutThinking);
        this.txtThinkingDots = (TextView)this.overlayView.findViewById(R.id.txtThinkingDots);
        this.btnMicrophone = this.overlayView.findViewById(R.id.btnMicrophone);
        this.btnMinimize = this.overlayView.findViewById(R.id.btnMinimize);
        this.btnChat = this.overlayView.findViewById(R.id.btnChat);
        this.micGlowView = this.overlayView.findViewById(R.id.viewMicGlow);
        this.voiceWaveView = (VoiceWaveView)this.overlayView.findViewById(R.id.voiceWaveView);
        this.cardGuidance = this.overlayView.findViewById(R.id.cardGuidance);
        this.cardUserQuery = this.overlayView.findViewById(R.id.cardUserQuery);
        this.cardInputBar = this.overlayView.findViewById(R.id.cardInputBar);
        // Hide the card input bar ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“ the standalone floating mic overlay replaces it
        if (this.cardInputBar != null) {
            this.cardInputBar.setVisibility(View.GONE);
        }
        this.layoutQuickActionsSection = this.overlayView.findViewById(R.id.layoutQuickActionsSection);
        this.btnDoneStep = (MaterialButton)this.overlayView.findViewById(R.id.btnDoneStep);
        this.btnShowAgain = (MaterialButton)this.overlayView.findViewById(R.id.btnShowAgain);
        MaterialButton btnStopTask = (MaterialButton)this.overlayView.findViewById(R.id.btnStopTask);
        if (btnStopTask != null) {
            btnStopTask.setOnClickListener(v -> this.stopCurrentTask());
        }
        this.layoutDoneButtons = this.overlayView.findViewById(R.id.layoutDoneButtons);
        this.cardError = this.overlayView.findViewById(R.id.cardError);
        this.txtErrorMessage = (TextView)this.overlayView.findViewById(R.id.txtErrorMessage);
        this.btnErrorRetry = this.overlayView.findViewById(R.id.btnErrorRetry);
        this.txtStepProgress = (TextView)this.overlayView.findViewById(R.id.txtStepProgress);
        this.btnMoreActions = (TextView)this.overlayView.findViewById(R.id.btnMoreActions);
        this.etQueryInput = null;
        this.layoutQuickActions = (LinearLayout)this.overlayView.findViewById(R.id.layoutQuickActions);
        if (this.btnErrorRetry != null) {
            this.btnErrorRetry.setOnClickListener(v -> {
                this.hideError();
                boolean zh = this.isChineseUi();
                this.updateGuidanceText(this.getReconnectingText());
                if (this.serverConnection != null) {
                    this.serverConnection.connect();
                }
            });
        }
        this.ensureOverlayHelpButton();
        this.refreshLocalizedUi();
        this.applyTextSizePreferences();
        this.populateQuickActionChips();
        this.updateQuickActionsVisibility();
        this.btnMicrophone.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case 0: {
                    this.micHoldArmed = true;
                    if (this.micHoldStartRunnable != null) {
                        this.handler.removeCallbacks(this.micHoldStartRunnable);
                    }
                    this.micHoldStartRunnable = () -> {
                        if (this.micHoldArmed && !this.isListening) {
                            Log.d((String)TAG, (String)"Mic hold started");
                            this.startListening(true);
                        }
                    };
                    this.handler.postDelayed(this.micHoldStartRunnable, 180L);
                    return true;
                }
                case 1: 
                case 3: {
                    this.micHoldArmed = false;
                    if (this.micHoldStartRunnable != null) {
                        this.handler.removeCallbacks(this.micHoldStartRunnable);
                        this.micHoldStartRunnable = null;
                    }
                    if (this.isListening) {
                        Log.d((String)TAG, (String)"Mic hold released");
                        this.stopListening();
                    } else {
                        v.performClick();
                    }
                    return true;
                }
            }
            return false;
        });
        // NOTE: Do NOT call setOnTouchListener(null) here ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â it would erase the hold-to-talk listener above.
        if (this.btnMinimize != null) {
            this.btnMinimize.setOnClickListener(v -> {
                if (this.isListening) {
                    this.stopListening();
                }
                this.switchToBallMode();
            });
        }
        if (this.btnChat != null) {
            this.btnChat.setOnClickListener(v -> this.toggleChatPanel());
        }
        if (this.btnMoreActions != null) {
            boolean zh = this.isChineseUi();
            this.btnMoreActions.setText((CharSequence)(zh ? "\u66f4\u591a\u529f\u80fd ->" : "More features ->"));
            this.btnMoreActions.setOnClickListener(v -> {
                Intent i = new Intent((Context)this, SettingsActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                this.startActivity(i);
            });
        }
        this.btnDoneStep.setOnClickListener(v -> {
            this.cancelAutoAnalysis();
            this.hideDoneButton();
            this.analyzeCurrentScreen(true);
        });
        this.btnShowAgain.setOnClickListener(v -> {
            if (this.session.hasLastHighlight()) {
                this.showLastHighlight();
            }
            this.replayCurrentGuidanceAudio();
        });
        if (this.btnNeedHelp != null) {
            this.btnNeedHelp.setOnClickListener(v -> this.requestExtraHelp());
        }
        int layoutFlag = Build.VERSION.SDK_INT >= 26 ? 2038 : 2002;
        this.params = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, layoutFlag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN, -3);
        this.params.gravity = Gravity.FILL_HORIZONTAL | Gravity.CENTER_VERTICAL;
        this.params.y = 0;
        this.setupDragListener();
        this.windowManager.addView(this.overlayView, (ViewGroup.LayoutParams)this.params);
        this.overlayView.setVisibility(View.GONE);
    }

    private void createHighlightOverlay() {
        this.highlightRenderer = new HighlightRenderer((Context)this);
    }

    private void setupDragListener() {
        this.overlayView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case 0: {
                    this.initialX = this.params.x;
                    this.initialY = this.params.y;
                    this.initialTouchX = event.getRawX();
                    this.initialTouchY = event.getRawY();
                    this.isDragging = false;
                    return true;
                }
                case 2: {
                    float deltaX = event.getRawX() - this.initialTouchX;
                    float deltaY = event.getRawY() - this.initialTouchY;
                    if (Math.abs(deltaX) > 10.0f || Math.abs(deltaY) > 10.0f) {
                        this.isDragging = true;
                        this.params.x = (int)(this.initialX + deltaX);
                        this.params.y = (int)(this.initialY + deltaY);
                        this.windowManager.updateViewLayout(this.overlayView, (ViewGroup.LayoutParams)this.params);
                    }
                    return true;
                }
                case 1: {
                    return this.isDragging;
                }
                case 4: {
                    if (this.etQueryInput == null || this.etQueryInput.getVisibility() != View.VISIBLE) break;
                    this.closeKeyboardMode();
                    return true;
                }
            }
            return false;
        });
    }

    private void openKeyboardMode() {
        if (this.etQueryInput.getVisibility() == View.VISIBLE) {
            return;
        }
        if (this.isListening) {
            this.stopListening();
        }
        this.etQueryInput.setVisibility(View.VISIBLE);
        if (this.txtHint != null) {
            this.txtHint.setVisibility(View.GONE);
        }
        if (this.txtListening != null) {
            this.txtListening.setVisibility(View.GONE);
        }
        this.params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        this.windowManager.updateViewLayout(this.overlayView, (ViewGroup.LayoutParams)this.params);
        this.etQueryInput.requestFocus();
        InputMethodManager imm = (InputMethodManager)this.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput((View)this.etQueryInput, 1);
        }
    }

    private void closeKeyboardMode() {
        if (this.etQueryInput.getVisibility() != View.VISIBLE) {
            return;
        }
        InputMethodManager imm = (InputMethodManager)this.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(this.etQueryInput.getWindowToken(), 0);
        }
        this.etQueryInput.setVisibility(View.GONE);
        this.etQueryInput.setText((CharSequence)"");
        if (this.btnMicrophone != null) {
            this.btnMicrophone.setVisibility(View.VISIBLE);
        }
        if (this.txtHint != null) {
            this.txtHint.setVisibility(View.VISIBLE);
        }
        this.params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        this.windowManager.updateViewLayout(this.overlayView, (ViewGroup.LayoutParams)this.params);
    }

    private void sendTextQuery() {
        String text = this.etQueryInput.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        this.closeKeyboardMode();
        this.handleUserQuery(text);
    }

    private void toggleMinimize() {
        boolean bl = this.isMinimized = !this.isMinimized;
        if (this.isMinimized) {
            if (this.cardGuidance != null) {
                this.cardGuidance.setVisibility(View.GONE);
            }
            if (this.cardUserQuery != null) {
                this.cardUserQuery.setVisibility(View.GONE);
            }
            this.hideHighlight();
        } else if (this.cardGuidance != null && !VOICE_ONLY_OVERLAY) {
            this.cardGuidance.setVisibility(View.VISIBLE);
        }
    }

    private void toggleSpeechRecognition() {
        if (this.isListening) {
            this.stopListening();
        } else {
            this.startListening();
        }
    }

    private void startListening() {
        this.startListening(false);
    }

    private void requestVoiceInputFromOverlay() {
        if (this.isListening) {
            this.stopListening();
            return;
        }
        if (this.useInlineOverlayVoiceCapture()) {
            this.startListening(true);
            return;
        }
        if (this.serverConnection == null) {
            return;
        }
        if (!this.hasMicPermission()) {
            this.promptForMicPermission();
            return;
        }
        if (!this.serverConnection.isConnected()) {
            boolean zh = "zh".equals(AppPrefs.getLanguage((Context)this));
            this.uiState = OverlayUiState.ANALYZING;
            this.switchToInputMode();
            if (this.txtHint != null) {
                this.txtHint.setText((CharSequence)this.getReconnectingText());
                this.txtHint.setVisibility(View.VISIBLE);
            }
            this.updateGuidanceText(this.getReconnectingWaitText());
            this.serverConnection.connect();
            return;
        }
        this.latestLiveTranscript = null;
        if (this.pendingTranscriptSubmitRunnable != null) {
            this.handler.removeCallbacks(this.pendingTranscriptSubmitRunnable);
            this.pendingTranscriptSubmitRunnable = null;
        }
        this.stopMicGlow();
        if (this.btnMicrophone != null) {
            this.btnMicrophone.setAlpha(1.0f);
            this.btnMicrophone.setScaleX(1.0f);
            this.btnMicrophone.setScaleY(1.0f);
        }
        Intent hideIntent = new Intent("com.smarthelp.HIDE_OVERLAY");
        hideIntent.setPackage(this.getPackageName());
        this.sendBroadcast(hideIntent);
        this.handler.postDelayed(() -> {
            try {
                Intent intent = new Intent((Context)this, OverlayVoiceCaptureActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                this.startActivity(intent);
            }
            catch (Exception e) {
                Log.e((String)TAG, (String)("Failed to launch foreground voice capture: " + e.getMessage()), (Throwable)e);
                Intent showIntent = new Intent("com.smarthelp.SHOW_OVERLAY");
                showIntent.setPackage(this.getPackageName());
                this.sendBroadcast(showIntent);
                this.showMicStatus(this.getVoiceUnavailableText(), true);
            }
        }, 220L);
    }

    private boolean useInlineOverlayVoiceCapture() {
        return true;
    }

    private void startListening(boolean stayInInputBar) {
        if (this.serverConnection == null) {
            return;
        }
        if (this.isListening) {
            Log.d((String)TAG, (String)"startListening: already listening, ignoring");
            return;
        }
        if (!this.hasMicPermission()) {
            this.promptForMicPermission();
            return;
        }
        // Hide AI response when user starts speaking
        this.hideMicAiResponse();
        if (this.pendingTranscriptSubmitRunnable != null) {
            this.handler.removeCallbacks(this.pendingTranscriptSubmitRunnable);
            this.pendingTranscriptSubmitRunnable = null;
        }
        this.latestLiveTranscript = null;
        this.lastFinalTranscriptAddedToChat = null;
        Log.d((String)TAG, (String)("startListening: connected=" + this.serverConnection.isConnected() + " stayInInputBar=" + stayInInputBar));
        if (skipServerConnectionForTests) {
            this.uiState = OverlayUiState.LISTENING;
            this.switchToInputMode();
            this.isListening = true;
            this.updateQuickActionsVisibility();
            this.startMicGlow();
            if (this.btnMicrophone != null) {
                this.btnMicrophone.setAlpha(0.88f);
                this.btnMicrophone.setScaleX(0.96f);
                this.btnMicrophone.setScaleY(0.96f);
            }
            if (this.txtHint != null) {
                this.txtHint.setText((CharSequence)this.getTapMicHint());
                this.txtHint.setVisibility(View.GONE);
            }
            this.showMicStatus(this.getListeningText(), false);
            return;
        }
        if (!this.serverConnection.isConnected()) {
            boolean zh = "zh".equals(AppPrefs.getLanguage((Context)this));
            this.uiState = OverlayUiState.ANALYZING;
            this.switchToInputMode();
            if (this.txtHint != null) {
                this.txtHint.setText((CharSequence)this.getReconnectingText());
                this.txtHint.setVisibility(View.VISIBLE);
            }
            this.updateGuidanceText(this.getReconnectingWaitText());
            this.serverConnection.connect();
            return;
        }
        this.uiState = OverlayUiState.LISTENING;
        this.switchToInputMode();
        this.isListening = true;
        this.updateQuickActionsVisibility();
        this.startMicGlow();
        if (this.btnMicrophone != null) {
            this.btnMicrophone.setAlpha(0.88f);
            this.btnMicrophone.setScaleX(0.96f);
            this.btnMicrophone.setScaleY(0.96f);
        }
        boolean zh = "zh".equals(AppPrefs.getLanguage((Context)this));
        if (this.txtHint != null) {
            this.txtHint.setText((CharSequence)this.getTapMicHint());
            this.txtHint.setVisibility(View.GONE);
        }
        this.showMicStatus(this.getListeningText(), false);
        if (this.isBallMode) {
            this.showInstructionBubble(this.getListeningText());
        }
        // Always use AudioRecord via the foreground service (startLocalSpeechRecognition).
        // OverlayVoiceCaptureActivity used SpeechRecognizer which is blocked on Xiaomi/MIUI
        // and any device where the app is only granted "Allow only when in use" ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â because
        // the overlay is considered background when another app is in front.
        // AudioRecord from OverlayService (foreground service type = microphone) is
        // allowed under "Allow only when in use" since the service holds a foreground
        // notification and has FOREGROUND_SERVICE_MICROPHONE permission declared.
        this.startLocalSpeechRecognition();
    }

    private void stopListening() {
        this.isListening = false;
        this.cancelSilenceTimer();
        this.stopMicGlow();
        if (this.btnMicrophone != null) {
            this.btnMicrophone.setAlpha(1.0f);
            this.btnMicrophone.setScaleX(1.0f);
            this.btnMicrophone.setScaleY(1.0f);
        }
        this.stopLocalSpeechRecognition();
        
        // Show "Processing..." and wait for server STT response
        this.uiState = OverlayUiState.ANALYZING;
        this.showMicStatus(this.isChineseUi() ? "å¤„ç†ä¸­..." : "Processing...", false);
        this.updateQuickActionsVisibility();
    }

    private void scheduleSilenceTimer() {
        this.cancelSilenceTimer();
        this.silenceRunnable = () -> {
            if (this.isListening) {
                this.stopListening();
            }
        };
        this.handler.postDelayed(this.silenceRunnable, SILENCE_AUTO_STOP_MS);
    }

    private void cancelSilenceTimer() {
        if (this.silenceRunnable != null) {
            this.handler.removeCallbacks(this.silenceRunnable);
            this.silenceRunnable = null;
        }
    }

    private void onAudioAmplitude(float rms) {
    }

    private void scheduleTranscriptSubmission() {
        if (this.pendingTranscriptSubmitRunnable != null) {
            this.handler.removeCallbacks(this.pendingTranscriptSubmitRunnable);
        }
        this.pendingTranscriptSubmitRunnable = () -> {
            String transcript;
            this.pendingTranscriptSubmitRunnable = null;
            String string = transcript = this.latestLiveTranscript == null ? "" : this.latestLiveTranscript.trim();
            if (transcript.isEmpty()) {
                boolean zh = this.isChineseUi();
                this.showMicStatus(this.getDidNotCatchText(), true);
                return;
            }
            this.showMicTranscript(transcript, true);
            this.handleUserQuery(transcript);
        };
        this.handler.postDelayed(this.pendingTranscriptSubmitRunnable, 700L);
    }

    private void addFinalTranscriptToChat(String transcript) {
        String finalTranscript;
        String string = finalTranscript = transcript == null ? "" : transcript.trim();
        if (finalTranscript.isEmpty()) {
            return;
        }
        if (finalTranscript.equals(this.lastFinalTranscriptAddedToChat)) {
            return;
        }
        this.lastFinalTranscriptAddedToChat = finalTranscript;
        this.addChatMessage(true, finalTranscript);
    }

    private void handleUserQuery(String query) {
        String trimmedQuery;
        String string = trimmedQuery = query == null ? "" : query.trim();
        Log.d((String)TAG, (String)("handleUserQuery called: " + trimmedQuery.substring(0, Math.min(50, trimmedQuery.length()))));
        if (trimmedQuery.isEmpty()) {
            return;
        }
        if (this.isNoMoreHelpIntent(trimmedQuery)) {
            this.addChatMessage(true, trimmedQuery);
            this.stopService();
            return;
        }
        if (this.awaitingCompletionFollowup) {
            this.awaitingCompletionFollowup = false;
            this.removeTaskCompleteDialog();
        }
        if (this.awaitingServerReply) {
            this.sendServerReply(trimmedQuery);
            return;
        }
        this.uiState = OverlayUiState.ANALYZING;
        this.session.beginTask(trimmedQuery);
        this.currentVoiceText = null;
        this.latestLiveTranscript = trimmedQuery;
        this.micControlsMinimized = true;
        if (this.cardGuidanceBall != null) {
            this.cardGuidanceBall.setVisibility(View.GONE);
        }
        this.updateQuickActionsVisibility();
        this.updateStepProgress();
        this.addChatMessage(true, trimmedQuery);
        if (this.txtUserQuery != null) {
            this.txtUserQuery.setText((CharSequence)trimmedQuery);
        }
        if (this.cardUserQuery != null && !VOICE_ONLY_OVERLAY) {
            this.cardUserQuery.setVisibility(View.VISIBLE);
        }
        String analyzingMsg = this.isChineseUi() ? "\u5206\u6790\u4e2d..." : "Analyzing...";
        this.currentVoiceText = analyzingMsg;
        this.updateGuidanceText(analyzingMsg);
        this.showInstructionBubble(analyzingMsg);
        this.showThinking();
        this.switchToBallMode();
        this.speak("Okay, let me help you with that.");
        int captureDelayMs = this.prepareTargetAppForDemo(trimmedQuery);
        if (captureDelayMs < 0) {
            return;
        }
        this.handler.postDelayed(() -> this.requestScreenAnalysis(trimmedQuery), (long)captureDelayMs);
        Log.d((String)TAG, (String)("Scheduled analyze request with query: " + trimmedQuery + " delayMs=" + captureDelayMs));
    }

    private void sendServerReply(String reply) {
        this.awaitingServerReply = false;
        this.uiState = OverlayUiState.ANALYZING;
        this.latestLiveTranscript = reply;
        this.micControlsMinimized = true;
        if (this.cardGuidanceBall != null) {
            this.cardGuidanceBall.setVisibility(View.GONE);
        }
        this.updateQuickActionsVisibility();
        this.addChatMessage(true, reply);
        String analyzingMsg = this.isChineseUi() ? "\u5206\u6790\u4e2d..." : "Analyzing...";
        this.currentVoiceText = analyzingMsg;
        this.updateGuidanceText(analyzingMsg);
        this.showInstructionBubble(analyzingMsg);
        this.showThinking();
        this.switchToBallMode();
        if (this.serverConnection != null && this.serverConnection.isConnected()) {
            this.serverConnection.sendText(reply);
        } else {
            this.showMicStatus(this.getReconnectingText(), true);
        }
    }

    private void requestScreenAnalysis(String query) {
        Intent intent = new Intent("com.smarthelp.ANALYZE_REQUEST");
        intent.setPackage(this.getPackageName());
        intent.putExtra("userQuery", query);
        this.sendBroadcast(intent);
    }

    private int prepareTargetAppForDemo(String query) {
        return 0;
    }

    private boolean launchWhatsAppForDemo() {
        return this.launchPackage("com.whatsapp") || this.launchPackage("com.whatsapp.w4b");
    }

    static boolean shouldAutoLaunchWhatsAppForDemo(String query) {
        String normalized = OverlayService.normalizeDemoQuery(query);
        return OverlayService.isWhatsAppDemoTask(normalized) && !OverlayService.containsFamilyRelationshipPlaceholder(normalized);
    }

    static boolean shouldDeferWhatsAppLaunchForClarification(String query) {
        String normalized = OverlayService.normalizeDemoQuery(query);
        return OverlayService.isWhatsAppDemoTask(normalized) && OverlayService.containsFamilyRelationshipPlaceholder(normalized);
    }

    static boolean shouldShowTaskActionButtons(boolean completed, boolean hasActiveTask) {
        return hasActiveTask && !completed;
    }

    private static String normalizeDemoQuery(String query) {
        return query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean isWhatsAppDemoTask(String normalized) {
        return normalized.contains("whatsapp") && !normalized.contains("not whatsapp") && !normalized.contains("not use whatsapp");
    }

    private static boolean containsFamilyRelationshipPlaceholder(String normalized) {
        for (String relation : DEMO_FAMILY_RELATIONS) {
            if (!normalized.matches(".*\\bmy\\s+" + relation + "\\b.*")) continue;
            return true;
        }
        return false;
    }

    private boolean looksLikeServerQuestion(String text) {
        String message;
        String string = message = text == null ? "" : text.trim();
        if (message.isEmpty()) {
            return false;
        }
        return message.endsWith("?") || message.endsWith("\uff1f") || message.toLowerCase(Locale.ROOT).startsWith("what ") || message.toLowerCase(Locale.ROOT).startsWith("who ");
    }

    private boolean launchPackage(String packageName) {
        try {
            Intent launchIntent = this.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                return false;
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            this.startActivity(launchIntent);
            Log.d((String)TAG, (String)("Launched target package for demo: " + packageName));
            return true;
        }
        catch (Exception e) {
            Log.w((String)TAG, (String)("Unable to launch " + packageName + ": " + e.getMessage()));
            return false;
        }
    }

    private void registerGuidanceReceiver() {
        this.guidanceReceiver = new BroadcastReceiver(){

            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if ("com.smarthelp.GUIDANCE_UPDATE".equals(action)) {
                    String instruction = intent.getStringExtra("instruction");
                    int highlightX = intent.getIntExtra("highlightX", -1);
                    int highlightY = intent.getIntExtra("highlightY", -1);
                    boolean completed = intent.getBooleanExtra("completed", false);
                    Log.d((String)OverlayService.TAG, (String)"=== GUIDANCE_UPDATE RECEIVED ===");
                    Log.d((String)OverlayService.TAG, (String)("Instruction: " + instruction));
                    Log.d((String)OverlayService.TAG, (String)("Highlight: x=" + highlightX + ", y=" + highlightY));
                    Log.d((String)OverlayService.TAG, (String)("Completed: " + completed));
                    if (instruction != null) {
                        OverlayService.this.handler.post(() -> OverlayService.this.updateGuidance(instruction, highlightX, highlightY, completed));
                    } else {
                        Log.e((String)OverlayService.TAG, (String)"Instruction is null!");
                    }
                } else if ("com.smarthelp.HIDE_OVERLAY".equals(action)) {
                    Log.d((String)OverlayService.TAG, (String)"Received HIDE_OVERLAY");
                    OverlayService.this.handler.post(() -> OverlayService.this.hideForScreenCapture());
                } else if ("com.smarthelp.SHOW_OVERLAY".equals(action)) {
                    Log.d((String)OverlayService.TAG, (String)"Received SHOW_OVERLAY");
                    OverlayService.this.handler.post(() -> {
                        OverlayService.this.restoreAfterScreenCapture();
                        if (OverlayService.this.isListening) {
                            OverlayService.this.isListening = false;
                            OverlayService.this.stopMicGlow();
                            OverlayService.this.hideMicStatus();
                        }
                    });
                } else if ("com.smarthelp.SEND_QUERY".equals(action)) {
                    String query = intent.getStringExtra("query");
                    Log.d((String)OverlayService.TAG, (String)("Received SEND_QUERY: " + query));
                    if (query != null) {
                        OverlayService.this.handler.post(() -> OverlayService.this.handleUserQuery(query));
                    }
                } else if ("com.smarthelp.CHANGE_LANGUAGE".equals(action)) {
                    String lang = intent.getStringExtra("lang");
                    if (lang != null) {
                        OverlayService.this.switchLanguage(lang);
                    }
                } else if ("com.smarthelp.SETTINGS_UPDATED".equals(action)) {
                    OverlayService.this.handler.post(() -> {
                        OverlayService.this.refreshLocalizedUi();
                        OverlayService.this.applyTextSizePreferences();
                        OverlayService.this.populateQuickActionChips();
                    });
                } else if (OverlayService.ACTION_USER_ACTION_DETECTED.equals(action)) {
                    int eventType = intent.getIntExtra("eventType", -1);
                    String sourcePackage = intent.getStringExtra("sourcePackage");
                    OverlayService.this.handler.post(() -> OverlayService.this.handleUserActionDetected(eventType, sourcePackage));
                } else if (OverlayService.ACTION_SENSITIVE_SCREEN_DETECTED.equals(action)) {
                    String sourcePackage = intent.getStringExtra("sourcePackage");
                    OverlayService.this.handler.post(() -> OverlayService.this.handleSensitiveScreenDetected(sourcePackage));
                } else if ("com.smarthelp.SCREENSHOT_READY".equals(action)) {
                    Log.d((String)OverlayService.TAG, (String)("Received SCREENSHOT_READY verify=" + OverlayService.this.session.isPendingVerify() + " refHash=" + OverlayService.this.session.getReferenceScreenshotHash() + " newHash=" + ScreenCaptureService.latestScreenshotHash));
                    if (OverlayService.this.serverConnection != null && ScreenCaptureService.latestScreenshotBase64 != null) {
                        if (OverlayService.this.session.isPendingVerify() && OverlayService.this.session.getReferenceScreenshotHash() != 0L && ScreenCaptureService.latestScreenshotHash == OverlayService.this.session.getReferenceScreenshotHash()) {
                            OverlayService.this.session.setPendingVerify(false);
                            ScreenCaptureService.latestScreenshotBase64 = null;
                            if (OverlayService.this.noTapReminderCount < 2) {
                                Log.d((String)OverlayService.TAG, (String)("Screen unchanged - user did not tap. Reminding. (attempt " + (OverlayService.this.noTapReminderCount + 1) + "/" + 2 + ")"));
                                OverlayService.this.noTapReminderCount++;
                                OverlayService.this.handler.post(() -> OverlayService.this.onScreenUnchangedReminder());
                            } else {
                                Log.d((String)OverlayService.TAG, (String)"Screen still unchanged after reminders; keeping highlight without re-analyzing");
                                OverlayService.this.handler.post(() -> OverlayService.this.onScreenStillUnchanged());
                            }
                            return;
                        }
                        OverlayService.this.serverConnection.sendScreenshot(ScreenCaptureService.latestScreenshotBase64, OverlayService.this.session.getCurrentQuery(), OverlayService.this.session.isPendingVerify(), ScreenCaptureService.latestScreenshotWidth, ScreenCaptureService.latestScreenshotHeight);
                        OverlayService.this.session.setPendingVerify(false);
                        ScreenCaptureService.latestScreenshotBase64 = null;
                        // Screenshot is on the wire — transition the bubble
                        // from "Capturing screen..." to "Analyzing..." while
                        // we wait for the server's guidance response.
                        OverlayService.this.handler.post(() -> {
                            String analyzingMsg = OverlayService.this.getAnalyzingText();
                            OverlayService.this.currentVoiceText = analyzingMsg;
                            OverlayService.this.updateGuidanceText(analyzingMsg);
                            if (OverlayService.this.isBallMode) {
                                OverlayService.this.showInstructionBubble(analyzingMsg);
                            }
                        });
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.smarthelp.GUIDANCE_UPDATE");
        filter.addAction("com.smarthelp.HIDE_OVERLAY");
        filter.addAction("com.smarthelp.SHOW_OVERLAY");
        filter.addAction("com.smarthelp.SCREENSHOT_READY");
        filter.addAction("com.smarthelp.SEND_QUERY");
        filter.addAction("com.smarthelp.CHANGE_LANGUAGE");
        filter.addAction("com.smarthelp.SETTINGS_UPDATED");
        filter.addAction(ACTION_USER_ACTION_DETECTED);
        filter.addAction(ACTION_SENSITIVE_SCREEN_DETECTED);
        ContextCompat.registerReceiver((Context)this, (BroadcastReceiver)this.guidanceReceiver, (IntentFilter)filter, (int)4);
    }

    private void updateGuidance(String instruction, int highlightX, int highlightY, boolean completed) {
        Log.d((String)TAG, (String)"=== updateGuidance called ===");
        Log.d((String)TAG, (String)("Setting instruction: " + instruction));
        Log.d((String)TAG, (String)("Task completed: " + completed));
        this.currentVoiceText = instruction = this.sanitizeGuidanceText(instruction);
        this.addChatMessage(false, instruction);
        boolean actionableInstruction = this.isActionableInstruction(instruction, highlightX, highlightY);
        if (actionableInstruction && !completed) {
            this.uiState = OverlayUiState.GUIDING;
        } else if (this.uiState != OverlayUiState.COMPLETED) {
            this.uiState = OverlayUiState.ANALYZING;
        }
        if (!completed && this.session.hasActiveTask()) {
            this.micControlsMinimized = true;
        }
        this.switchToBallMode();
        if (OverlayService.shouldShowTaskActionButtons(completed, this.session.hasActiveTask())) {
            this.showDoneButton();
        } else {
            this.hideDoneButton();
        }
        if (this.isMinimized) {
            this.toggleMinimize();
        }
        if (!this.isBallMode && this.overlayView != null) {
            this.overlayView.setVisibility(View.VISIBLE);
        }
        this.hideThinking();
        if (this.txtGuidance != null) {
            this.txtGuidance.setText((CharSequence)(instruction != null ? instruction : "No response"));
            Log.d((String)TAG, (String)"txtGuidance updated");
        } else {
            Log.e((String)TAG, (String)"txtGuidance is null!");
        }
        if (this.cardGuidance != null && !VOICE_ONLY_OVERLAY) {
            this.cardGuidance.setVisibility(View.VISIBLE);
        } else if (this.cardGuidance != null) {
            this.cardGuidance.setVisibility(View.GONE);
        }
        if (VOICE_ONLY_OVERLAY && this.cardGuidanceBall != null && this.cardGuidanceBall.getVisibility() == View.VISIBLE) {
            this.showDetailPopup();
        }
        if (this.isBallMode && instruction != null) {
            this.showInstructionBubble(instruction);
        }
        if (highlightX >= 0 && highlightY >= 0) {
            Log.d((String)TAG, (String)("Showing highlight at: " + highlightX + ", " + highlightY));
            this.showHighlightPercent(highlightX, highlightY);
        } else {
            this.hideHighlight();
        }
        if (instruction != null && !instruction.isEmpty()) {
            this.speak(instruction);
            if (!completed) {
                int speakDurationMs = Math.max(2500, instruction.length() * 75);
                this.handler.postDelayed(() -> {
                    if (!this.isListening && this.session.hasActiveTask() && !this.isBallMode) {
                        Log.d((String)TAG, (String)"Auto-restarting STT after guidance");
                        this.startListening();
                    }
                }, (long)speakDurationMs);
            }
        }
    }

    private void showHighlight(String position) {
        Log.d((String)TAG, (String)("Showing highlight at: " + position));
        if (this.highlightRenderer != null) {
            this.highlightRenderer.show(position);
        }
    }

    private void showHighlightPercent(int x, int y) {
        this.showHighlightPercent(x, y, -1, -1, -1, -1);
    }

    private void showHighlightPercent(int x, int y, int x1, int y1, int x2, int y2) {
        Log.d((String)TAG, (String)("Showing highlight at percent: x=" + x + ", y=" + y));
        this.session.setLastHighlight(x, y, x1, y1, x2, y2);
        if (this.highlightRenderer != null) {
            this.highlightRenderer.showPercent(x, y, x1, y1, x2, y2);
        }
    }

    private void showHighlight(MessageProtocol.ServerMessage message) {
        if (message.hasHighlightBounds()
                && SmartHelpAccessibilityService.shouldPreferVisionBounds(message.getTarget(), message.getMatchHints())) {
            Log.d((String)TAG, (String)("Using vision highlight bounds for target=" + message.getTarget()));
            this.showHighlightPercent(message.getHighlightX(), message.getHighlightY(), message.getHighlightX1(), message.getHighlightY1(), message.getHighlightX2(), message.getHighlightY2());
            return;
        }
        Rect accessibilityBounds = SmartHelpAccessibilityService.findTargetBounds(message.getTarget(), message.getMatchHints());
        if (accessibilityBounds != null && this.highlightRenderer != null) {
            Log.d((String)TAG, (String)("Showing accessibility highlight for target=" + message.getTarget() + " bounds=" + accessibilityBounds.toShortString()));
            this.session.setLastHighlight(message.getHighlightX(), message.getHighlightY(), message.getHighlightX1(), message.getHighlightY1(), message.getHighlightX2(), message.getHighlightY2());
            this.highlightRenderer.showBounds(accessibilityBounds);
            return;
        }
        this.showHighlightPercent(message.getHighlightX(), message.getHighlightY(), message.getHighlightX1(), message.getHighlightY1(), message.getHighlightX2(), message.getHighlightY2());
    }

    private void showLastHighlight() {
        if (!this.session.hasLastHighlight()) {
            return;
        }
        this.showHighlightPercent(this.session.getLastHighlightX(), this.session.getLastHighlightY(), this.session.getLastHighlightX1(), this.session.getLastHighlightY1(), this.session.getLastHighlightX2(), this.session.getLastHighlightY2());
    }

    private void hideHighlight() {
        if (this.highlightRenderer != null) {
            this.highlightRenderer.hide();
        }
    }

    private void showScrollDirection(String direction) {
        Log.d((String)TAG, (String)("Showing scroll direction: " + direction));
        if (this.highlightRenderer != null) {
            this.highlightRenderer.showScrollDirection(direction);
        }
    }

    private void showThinking() {
        this.handler.post(() -> {
            if (this.txtGuidance != null) {
                this.txtGuidance.setVisibility(View.INVISIBLE);
            }
            if (this.layoutThinking != null) {
                this.layoutThinking.setVisibility(View.VISIBLE);
            }
            this.thinkingDotCount = 0;
            this.scheduleThinkingTick();
        });
    }

    private void hideThinking() {
        this.handler.post(() -> {
            if (this.thinkingAnimRunnable != null) {
                this.handler.removeCallbacks(this.thinkingAnimRunnable);
                this.thinkingAnimRunnable = null;
            }
            if (this.layoutThinking != null) {
                this.layoutThinking.setVisibility(View.GONE);
            }
            if (this.txtGuidance != null) {
                this.txtGuidance.setVisibility(View.VISIBLE);
            }
        });
    }

    private void scheduleThinkingTick() {
        this.thinkingAnimRunnable = () -> {
            if (this.layoutThinking == null || this.layoutThinking.getVisibility() != View.VISIBLE) {
                return;
            }
            this.thinkingDotCount = (this.thinkingDotCount + 1) % 4;
            String dots = this.thinkingDotCount == 0 ? "Thinking" : (this.thinkingDotCount == 1 ? "Thinking." : (this.thinkingDotCount == 2 ? "Thinking.." : "Thinking..."));
            if (this.txtThinkingDots != null) {
                this.txtThinkingDots.setText((CharSequence)dots);
            }
            this.scheduleThinkingTick();
        };
        this.handler.postDelayed(this.thinkingAnimRunnable, 500L);
    }

    private void createBallOverlay() {
        ContextThemeWrapper ctx = new ContextThemeWrapper((Context)this, R.style.Theme_SmartHelp);
        LayoutInflater inflater = (LayoutInflater)ctx.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        this.ballView = inflater.inflate(R.layout.overlay_ball, null);
        // Wire up all ball views from overlay_ball.xml
        this.imgBall = (ImageView)this.ballView.findViewById(R.id.imgBall);
        this.txtBallStepBadge = (TextView)this.ballView.findViewById(R.id.txtBallStepBadge);
        this.txtGuidanceBall = (TextView)this.ballView.findViewById(R.id.txtGuidanceBall);
        this.txtGuidanceBallStep = (TextView)this.ballView.findViewById(R.id.txtGuidanceBallStep);
        this.txtGuidanceBallMeta = (TextView)this.ballView.findViewById(R.id.txtGuidanceBallMeta);
        this.cardGuidanceBall = this.ballView.findViewById(R.id.cardGuidanceBall);
        this.layoutDoneBallButtons = this.ballView.findViewById(R.id.layoutDoneBallButtons);
        this.btnDoneBall = (MaterialButton)this.ballView.findViewById(R.id.btnDoneBall);
        this.btnShowAgainBall = (MaterialButton)this.ballView.findViewById(R.id.btnShowAgainBall);
        this.btnExpandPanel = (MaterialButton)this.ballView.findViewById(R.id.btnExpandPanel);
        this.btnNeedHelpBall = null;
        this.cardInstructionBubble = this.ballView.findViewById(R.id.cardInstructionBubble);
        this.txtInstructionBubble = (TextView)this.ballView.findViewById(R.id.txtInstructionBubble);
        this.txtInstructionBubbleStep = (TextView)this.ballView.findViewById(R.id.txtInstructionBubbleStep);
        this.imgBubbleTail = this.ballView.findViewById(R.id.imgBubbleTail);
        // Wire collapse button in detail card
        View btnCollapse = this.ballView.findViewById(R.id.btnCollapseBall);
        if (btnCollapse != null) {
            btnCollapse.setOnClickListener(v -> {
                if (this.cardGuidanceBall != null) this.cardGuidanceBall.setVisibility(View.GONE);
            });
        }
        // Wire ball action buttons
        // ÃƒÂ°Ã…Â¸Ã…Â½Ã‚Â¤ button: start listening directly (stays in ball mode on Android < 30,
        // or launches OverlayVoiceCaptureActivity on Android >= 30)
        if (this.btnExpandPanel != null) {
            this.btnExpandPanel.setOnClickListener(v -> {
                if (this.isListening) {
                    this.stopListening();
                } else {
                    this.startListening(true);
                }
            });
        }
        if (this.btnDoneBall != null) {
            this.btnDoneBall.setOnClickListener(v -> {
                this.cancelAutoAnalysis();
                this.hideDoneButton();
                this.analyzeCurrentScreen(true);
            });
        }
        if (this.btnShowAgainBall != null) {
            this.btnShowAgainBall.setOnClickListener(v -> {
                if (this.session.hasLastHighlight()) this.showLastHighlight();
                this.replayCurrentGuidanceAudio();
            });
        }
        MaterialButton btnStopBall = (MaterialButton)this.ballView.findViewById(R.id.btnStopTaskBall);
        if (btnStopBall != null) {
            btnStopBall.setOnClickListener(v -> this.stopCurrentTask());
        }
        this.ensureBallHelpButton();
        if (this.btnNeedHelpBall != null) {
            this.btnNeedHelpBall.setOnClickListener(v -> this.requestExtraHelp());
        }
        this.refreshLocalizedUi();
        this.applyTextSizePreferences();
        this.updateBallStepViews();
        // Tap the ball image to toggle detail card
        if (this.imgBall != null) {
            this.imgBall.setOnClickListener(v -> this.handleFloatingBallTap());
        }
        int layoutFlag = Build.VERSION.SDK_INT >= 26 ? 2038 : 2002;
        DisplayMetrics dm = this.getResources().getDisplayMetrics();
        this.ballParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, layoutFlag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, -3);
        this.ballParams.gravity = Gravity.TOP | Gravity.START;
        this.ballParams.x = dm.widthPixels - this.dpToPx(80);
        this.ballParams.y = dm.heightPixels / 3;
        this.setupBallDragListener(dm);
        this.windowManager.addView(this.ballView, (ViewGroup.LayoutParams)this.ballParams);
        if (VOICE_ONLY_OVERLAY) {
            this.ballView.setVisibility(View.VISIBLE);
            this.isBallMode = true;
        } else {
            this.ballView.setVisibility(View.GONE);
            this.isBallMode = false;
        }
    }

    private void handleFloatingBallTap() {
        if (VOICE_ONLY_OVERLAY) {
            if (this.micControlsMinimized && this.shouldFloatingBallShowGuidance()) {
                // Task in progress ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â toggle the guidance detail card
                boolean isVisible = this.cardGuidanceBall != null && this.cardGuidanceBall.getVisibility() == View.VISIBLE;
                if (isVisible) {
                    this.cardGuidanceBall.setVisibility(View.GONE);
                } else {
                    this.showDetailPopup();
                }
            } else {
                // No task in progress: tap ball to toggle mic listening
                if (this.isListening) { this.stopListening(); } else { this.startListening(true); }
            }
            return;
        }
        if (!this.shouldFloatingBallShowGuidance() && !this.awaitingCompletionFollowup) {
            this.switchToInputMode();
            return;
        }
        if (this.cardGuidanceBall != null) {
            boolean isVisible = this.cardGuidanceBall.getVisibility() == View.VISIBLE;
            if (isVisible) {
                this.cardGuidanceBall.setVisibility(View.GONE);
            } else {
                this.showDetailPopup();
            }
        } else {
            this.switchToInputMode();
        }
    }

    private void createCloseTargetOverlay() {
        ContextThemeWrapper ctx = new ContextThemeWrapper((Context)this, R.style.Theme_SmartHelp);
        LayoutInflater inflater = (LayoutInflater)ctx.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        this.closeTargetView = inflater.inflate(R.layout.overlay_close_target, null);
        this.closeTargetCircle = this.closeTargetView.findViewById(R.id.closeTargetCircle);
        this.txtCloseTargetLabel = (TextView)this.closeTargetView.findViewById(R.id.txtCloseTargetLabel);
        int layoutFlag = Build.VERSION.SDK_INT >= 26 ? 2038 : 2002;
        this.closeTargetParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, layoutFlag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, -3);
        this.closeTargetParams.gravity = Gravity.LEFT | Gravity.TOP;
        this.windowManager.addView(this.closeTargetView, (ViewGroup.LayoutParams)this.closeTargetParams);
        this.closeTargetView.setVisibility(View.GONE);
    }

    private void setupBallDragListener(DisplayMetrics dm) {
        int touchSlop = ViewConfiguration.get((Context)this).getScaledTouchSlop();
        float[] startRaw = new float[]{0.0f, 0.0f};
        int[] startParams = new int[]{0, 0};
        boolean[] didDrag = new boolean[]{false};
        View dragTarget = this.imgBall != null ? this.imgBall : this.ballView;
        dragTarget.setClickable(true);
        dragTarget.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case 0: {
                    startRaw[0] = event.getRawX();
                    startRaw[1] = event.getRawY();
                    startParams[0] = this.ballParams.x;
                    startParams[1] = this.ballParams.y;
                    didDrag[0] = false;
                    this.ballCloseDragMode = false;
                    // Start long-press timer for close target
                    this.ballCloseLongPressRunnable = () -> {
                        this.ballCloseDragMode = true;
                        this.showCloseTarget();
                    };
                    this.handler.postDelayed(this.ballCloseLongPressRunnable, (long)BALL_CLOSE_LONG_PRESS_MS);
                    return true;
                }
                case 2: {
                    float dx = event.getRawX() - startRaw[0];
                    float dy = event.getRawY() - startRaw[1];
                    if (Math.abs(dx) > (float)touchSlop || Math.abs(dy) > (float)touchSlop) {
                        didDrag[0] = true;
                        this.cancelBallCloseLongPress();
                        if (this.cardGuidanceBall != null) {
                            this.cardGuidanceBall.setVisibility(View.GONE);
                        }
                        if (!this.ballCloseDragMode) {
                            this.ballCloseDragMode = true;
                            this.showCloseTarget();
                        }
                        this.ballParams.x = Math.max(0, Math.min(startParams[0] + (int)dx, dm.widthPixels - this.getBallDragWidth()));
                        this.ballParams.y = Math.max(0, Math.min(startParams[1] + (int)dy, dm.heightPixels - this.getBallDragHeight()));
                        if (this.ballView != null && this.ballView.isAttachedToWindow()) {
                            this.windowManager.updateViewLayout(this.ballView, (ViewGroup.LayoutParams)this.ballParams);
                        }
                        this.updateCloseTargetHoverState();
                    }
                    return true;
                }
                case 1: {
                    this.cancelBallCloseLongPress();
                    if (didDrag[0]) {
                        if (this.isBallOverCloseTarget()) {
                            this.hideCloseTarget();
                            this.stopService();
                            return true;
                        }
                        this.hideCloseTarget();
                        this.ballCloseDragMode = false;
                        this.snapBallToNearestEdge(dm);
                        return true;
                    }
                    this.hideCloseTarget();
                    this.ballCloseDragMode = false;
                    v.performClick();
                    return true;
                }
                case 3: {
                    this.cancelBallCloseLongPress();
                    this.hideCloseTarget();
                    this.ballCloseDragMode = false;
                    if (didDrag[0]) {
                        this.snapBallToNearestEdge(dm);
                        return true;
                    }
                    return true;
                }
            }
            return false;
        });
    }

    private void cancelBallCloseLongPress() {
        if (this.ballCloseLongPressRunnable != null) {
            this.handler.removeCallbacks(this.ballCloseLongPressRunnable);
            this.ballCloseLongPressRunnable = null;
        }
    }

    private void showCloseTarget() {
        if (this.closeTargetView == null) {
            return;
        }
        this.isCloseTargetVisible = true;
        this.closeTargetView.setVisibility(View.VISIBLE);
        this.setCloseTargetHovering(false);
    }

    private void hideCloseTarget() {
        this.isCloseTargetVisible = false;
        this.isCloseTargetHovering = false;
        if (this.closeTargetCircle != null) {
            this.closeTargetCircle.setBackgroundResource(R.drawable.bg_overlay_close_target);
            this.closeTargetCircle.setScaleX(1.0f);
            this.closeTargetCircle.setScaleY(1.0f);
        }
        if (this.closeTargetView != null) {
            this.closeTargetView.setVisibility(View.GONE);
        }
    }

    private void updateCloseTargetHoverState() {
        this.setCloseTargetHovering(this.isBallOverCloseTarget());
    }

    private void setCloseTargetHovering(boolean hovering) {
        if (this.isCloseTargetHovering == hovering) {
            return;
        }
        this.isCloseTargetHovering = hovering;
        if (this.closeTargetCircle != null) {
            this.closeTargetCircle.setBackgroundResource(hovering ? R.drawable.bg_overlay_close_target_active : R.drawable.bg_overlay_close_target);
            this.closeTargetCircle.animate().scaleX(hovering ? 1.12f : 1.0f).scaleY(hovering ? 1.12f : 1.0f).setDuration(140L).start();
        }
        if (this.txtCloseTargetLabel != null) {
            this.txtCloseTargetLabel.setAlpha(hovering ? 1.0f : 0.9f);
        }
    }

    private boolean isBallOverCloseTarget() {
        if (this.imgBall == null || this.closeTargetCircle == null || this.closeTargetView == null || this.closeTargetView.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] ballLocation = new int[2];
        int[] targetLocation = new int[2];
        this.imgBall.getLocationOnScreen(ballLocation);
        this.closeTargetCircle.getLocationOnScreen(targetLocation);
        int ballCenterX = ballLocation[0] + this.imgBall.getWidth() / 2;
        int ballCenterY = ballLocation[1] + this.imgBall.getHeight() / 2;
        Rect targetRect = new Rect(targetLocation[0] - this.dpToPx(12), targetLocation[1] - this.dpToPx(12), targetLocation[0] + this.closeTargetCircle.getWidth() + this.dpToPx(12), targetLocation[1] + this.closeTargetCircle.getHeight() + this.dpToPx(12));
        return targetRect.contains(ballCenterX, ballCenterY);
    }

    private void animateBallToEdge(final int targetX, final int targetY) {
        final int startX = this.ballParams.x;
        final int startY = this.ballParams.y;
        final long startTime = System.currentTimeMillis();
        long duration = 220L;
        Runnable anim = new Runnable(){

            @Override
            public void run() {
                float t = Math.min(1.0f, (float)(System.currentTimeMillis() - startTime) / 220.0f);
                float ease = 1.0f - (1.0f - t) * (1.0f - t);
                ((OverlayService)OverlayService.this).ballParams.x = (int)((float)startX + (float)(targetX - startX) * ease);
                ((OverlayService)OverlayService.this).ballParams.y = (int)((float)startY + (float)(targetY - startY) * ease);
                if (OverlayService.this.ballView != null && OverlayService.this.ballView.isAttachedToWindow()) {
                    OverlayService.this.windowManager.updateViewLayout(OverlayService.this.ballView, (ViewGroup.LayoutParams)OverlayService.this.ballParams);
                }
                if (t < 1.0f) {
                    OverlayService.this.handler.postDelayed((Runnable)this, 16L);
                }
            }
        };
        this.handler.post(anim);
    }

    private void snapBallToNearestEdge(DisplayMetrics dm) {
        int maxX = Math.max(0, dm.widthPixels - this.getBallDragWidth());
        int targetX = this.ballParams.x < maxX / 2 ? 0 : maxX;
        this.animateBallToEdge(targetX, this.ballParams.y);
    }

    private boolean hasMicPermission() {
        return ContextCompat.checkSelfPermission((Context)this, (String)"android.permission.RECORD_AUDIO") == 0;
    }

    private void promptForMicPermission() {
        boolean zh = this.isChineseUi();
        this.showMicStatus(this.getAllowMicPermissionText(), true);
        this.isListening = false;
        this.stopMicGlow();
        try {
            Intent intent = new Intent((Context)this, MicPermissionActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            this.startActivity(intent);
        }
        catch (Exception e) {
            Log.e((String)TAG, (String)("Failed to launch mic permission activity: " + e.getMessage()), (Throwable)e);
            try {
                Intent settings = new Intent("android.settings.APPLICATION_DETAILS_SETTINGS", Uri.parse((String)("package:" + this.getPackageName())));
                settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                this.startActivity(settings);
            }
            catch (Exception ignored) {
                Toast.makeText((Context)this, (CharSequence)this.getEnableMicPermissionText(), (int)1).show();
            }
        }
    }

    private void handleSpeechPermissionIssue(String source) {
        boolean hasPermission = this.hasMicPermission();
        boolean zh = this.isChineseUi();
        this.isListening = false;
        this.stopMicGlow();
        if (this.btnMicrophone != null) {
            this.btnMicrophone.setAlpha(1.0f);
            this.btnMicrophone.setScaleX(1.0f);
            this.btnMicrophone.setScaleY(1.0f);
        }
        if (!hasPermission) {
            Log.w((String)TAG, (String)(source + ": RECORD_AUDIO not granted"));
            this.promptForMicPermission();
            return;
        }
        Log.w((String)TAG, (String)(source + ": RECORD_AUDIO granted, but Android blocked in-service speech capture"));
        this.showMicStatus(this.getMicPermissionBlockedText(), true);
    }

    private int getBallDragWidth() {
        if (this.ballView != null && this.ballView.getWidth() > 0) {
            return this.ballView.getWidth();
        }
        return this.dpToPx(96);
    }

    private int getBallDragHeight() {
        if (this.ballView != null && this.ballView.getHeight() > 0) {
            return this.ballView.getHeight();
        }
        return this.dpToPx(96);
    }

    private int dpToPx(int dp) {
        return (int)((float)dp * this.getResources().getDisplayMetrics().density);
    }

    private void createChatOverlay() {
        ContextThemeWrapper ctx = new ContextThemeWrapper((Context)this, R.style.Theme_SmartHelp);
        LayoutInflater inflater = (LayoutInflater)ctx.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        this.chatView = inflater.inflate(R.layout.overlay_chat, null);
        this.chatMessageContainer = (LinearLayout)this.chatView.findViewById(R.id.chatMessageContainer);
        this.chatScrollView = (ScrollView)this.chatView.findViewById(R.id.chatScrollView);
        this.layoutChatEmpty = this.chatView.findViewById(R.id.layoutChatEmpty);
        View scrim = this.chatView.findViewById(R.id.chatScrim);
        View closeBtn = this.chatView.findViewById(R.id.btnCloseChat);
        if (scrim != null) {
            scrim.setOnClickListener(v -> this.toggleChatPanel());
        }
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> this.toggleChatPanel());
        }
        int layoutFlag = Build.VERSION.SDK_INT >= 26 ? 2038 : 2002;
        this.chatParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, layoutFlag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, -3);
        this.chatParams.gravity = Gravity.BOTTOM;
        DisplayMetrics dm = this.getResources().getDisplayMetrics();
        View panel = this.chatView.findViewById(R.id.chatPanel);
        if (panel != null) {
            panel.post(() -> {
                ViewGroup.LayoutParams lp = panel.getLayoutParams();
                lp.height = (int)((float)dm.heightPixels * 0.62f);
                panel.setLayoutParams(lp);
            });
        }
        this.windowManager.addView(this.chatView, (ViewGroup.LayoutParams)this.chatParams);
        this.chatView.setVisibility(View.GONE);
    }

    private void createMicFloatOverlay() {
        ContextThemeWrapper ctx = new ContextThemeWrapper((Context)this, R.style.Theme_SmartHelp);
        LayoutInflater inflater = (LayoutInflater)ctx.getSystemService("layout_inflater");
        this.micFloatView = inflater.inflate(R.layout.overlay_mic_float, null);
        this.micRing1 = this.micFloatView.findViewById(R.id.micRing1);
        this.micRing2 = this.micFloatView.findViewById(R.id.micRing2);
        this.micRing3 = this.micFloatView.findViewById(R.id.micRing3);
        this.micIdleHalo = this.micFloatView.findViewById(R.id.micIdleHalo);
        this.layoutMicControls = this.micFloatView.findViewById(R.id.layoutMicControls);
        this.txtMicHint = (TextView)this.micFloatView.findViewById(R.id.txtMicHint);
        this.layoutMicTextInput = this.micFloatView.findViewById(R.id.layoutMicTextInput);
        this.etMicTextInput = (EditText)this.micFloatView.findViewById(R.id.etMicTextInput);
        this.btnMicTextSend = this.micFloatView.findViewById(R.id.btnMicTextSend);
        if (this.txtMicHint != null) {
            this.txtMicHint.setText((CharSequence)this.getMicFloatHintText());
        }
        View btnMicKeyboardInput = this.micFloatView.findViewById(R.id.btnMicKeyboardInput);
        if (btnMicKeyboardInput != null) {
            btnMicKeyboardInput.setOnClickListener(v -> this.toggleFloatingTextInput());
        }
        View btnMicTextClose = this.micFloatView.findViewById(R.id.btnMicTextClose);
        if (btnMicTextClose != null) {
            btnMicTextClose.setOnClickListener(v -> this.closeFloatingTextInput(false));
        }
        if (this.btnMicTextSend != null) {
            this.btnMicTextSend.setOnClickListener(v -> this.sendFloatingTextInput());
        }
        if (this.etMicTextInput != null) {
            this.etMicTextInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    this.sendFloatingTextInput();
                    return true;
                }
                return false;
            });
        }
        View btnMicChatHistory = this.micFloatView.findViewById(R.id.btnMicChatHistory);
        if (btnMicChatHistory != null) {
            btnMicChatHistory.setOnClickListener(v -> {
                if (this.isListening) {
                    this.stopListening();
                    this.stopMicRipple();
                    this.startMicIdleHalo();
                }
                this.closeFloatingTextInput(false);
                this.hideTemporaryMicHint();
                this.toggleChatPanel();
            });
        }
        this.imgMicFloat = (ImageView)this.micFloatView.findViewById(R.id.imgMicFloat);
        // Hold-to-talk touch listener
        if (this.imgMicFloat != null) {
            this.imgMicFloat.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case 0: { // ACTION_DOWN ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“ start recording
                        if (!this.isListening) {
                            this.closeFloatingTextInput(false);
                            this.hideTemporaryMicHint();
                            this.stopMicIdleHalo();
                            this.startListening(true);
                            this.startMicRipple();
                            // Slight press-in scale feedback
                            this.imgMicFloat.animate().scaleX(0.88f).scaleY(0.88f).setDuration(100).start();
                        }
                        return true;
                    }
                    case 1: // ACTION_UP
                    case 3: { // ACTION_CANCEL ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“ stop recording
                        this.stopListening();
                        this.stopMicRipple();
                        this.startMicIdleHalo();
                        this.imgMicFloat.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        return true;
                    }
                }
                return false;
            });
        }
        int layoutFlag = Build.VERSION.SDK_INT >= 26 ? 2038 : 2002;
        DisplayMetrics dm = this.getResources().getDisplayMetrics();
        this.micFloatParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            -3);
        this.micFloatParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        this.micFloatParams.x = 0;
        this.micFloatParams.y = this.dpToPx(MIC_FLOAT_BOTTOM_MARGIN_DP);
        this.windowManager.addView(this.micFloatView, (ViewGroup.LayoutParams)this.micFloatParams);
        this.micFloatView.setVisibility(View.GONE);
    }

    private void createMicAiResponseOverlay() {
        ContextThemeWrapper ctx = new ContextThemeWrapper((Context)this, R.style.Theme_SmartHelp);
        LayoutInflater inflater = (LayoutInflater)ctx.getSystemService("layout_inflater");
        this.micAiResponseView = inflater.inflate(R.layout.overlay_mic_ai_response, null);
        this.txtMicAiResponse = (TextView)this.micAiResponseView.findViewById(R.id.txtMicAiResponse);
        int layoutFlag = Build.VERSION.SDK_INT >= 26 ? 2038 : 2002;
        this.micAiResponseParams = new WindowManager.LayoutParams(
            this.dpToPx(304),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            -3);
        this.micAiResponseParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        this.micAiResponseParams.x = 0;
        this.micAiResponseParams.y = this.dpToPx(MIC_FLOAT_BOTTOM_MARGIN_DP + MIC_AI_RESPONSE_BOTTOM_OFFSET_DP);
        this.windowManager.addView(this.micAiResponseView, (ViewGroup.LayoutParams)this.micAiResponseParams);
        this.micAiResponseView.setVisibility(View.GONE);
    }

    private void createMicUserTranscriptOverlay() {
        ContextThemeWrapper ctx = new ContextThemeWrapper((Context)this, R.style.Theme_SmartHelp);
        LayoutInflater inflater = (LayoutInflater)ctx.getSystemService("layout_inflater");
        this.micUserTranscriptView = inflater.inflate(R.layout.overlay_mic_user_transcript, null);
        this.txtMicUserTranscript = (TextView)this.micUserTranscriptView.findViewById(R.id.txtMicUserTranscript);
        int layoutFlag = Build.VERSION.SDK_INT >= 26 ? 2038 : 2002;
        this.micUserTranscriptParams = new WindowManager.LayoutParams(
            this.dpToPx(304),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            -3);
        this.micUserTranscriptParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        this.micUserTranscriptParams.x = 0;
        this.micUserTranscriptParams.y = this.dpToPx(MIC_FLOAT_BOTTOM_MARGIN_DP + MIC_USER_TRANSCRIPT_BOTTOM_OFFSET_DP);
        this.windowManager.addView(this.micUserTranscriptView, (ViewGroup.LayoutParams)this.micUserTranscriptParams);
        this.micUserTranscriptView.setVisibility(View.GONE);
    }

    private void showMicUserTranscriptOverlay(String text, boolean isFinal) {
        this.runOnMainThread(() -> {
            if (this.txtMicUserTranscript == null || text == null || text.trim().isEmpty()) {
                return;
            }
            if (this.layoutMicTextInput != null && this.layoutMicTextInput.getVisibility() == View.VISIBLE) {
                this.txtMicUserTranscript.setVisibility(View.GONE);
                if (this.micUserTranscriptView != null) {
                    this.micUserTranscriptView.setVisibility(View.GONE);
                }
                return;
            }
            if (this.hideMicUserTranscriptRunnable != null) {
                this.handler.removeCallbacks(this.hideMicUserTranscriptRunnable);
                this.hideMicUserTranscriptRunnable = null;
            }
            this.txtMicUserTranscript.setText((CharSequence)text.trim());
            if (this.micUserTranscriptView != null) {
                this.micUserTranscriptView.setVisibility(View.VISIBLE);
            }
            this.txtMicUserTranscript.setVisibility(View.VISIBLE);
            if (isFinal) {
                this.hideMicUserTranscriptRunnable = this::hideMicUserTranscriptOverlay;
                this.handler.postDelayed(this.hideMicUserTranscriptRunnable, 6000L);
            }
        });
    }

    private void hideMicUserTranscriptOverlay() {
        this.runOnMainThread(() -> {
            if (this.hideMicUserTranscriptRunnable != null) {
                this.handler.removeCallbacks(this.hideMicUserTranscriptRunnable);
                this.hideMicUserTranscriptRunnable = null;
            }
            if (this.txtMicUserTranscript != null) {
                this.txtMicUserTranscript.setVisibility(View.GONE);
            }
            if (this.micUserTranscriptView != null) {
                this.micUserTranscriptView.setVisibility(View.GONE);
            }
        });
    }

    private void showMicFloatOverlay() {
        this.runOnMainThread(() -> {
            if (this.micFloatView != null) {
                this.micFloatView.setVisibility(View.VISIBLE);
                this.startMicIdleHalo();
                this.showTemporaryMicHint();
            }
        });
    }

    private void hideMicFloatOverlay() {
        this.runOnMainThread(() -> {
            this.closeFloatingTextInput(false);
            this.stopMicRipple();
            this.stopMicIdleHalo();
            this.hideTemporaryMicHint();
            this.hideMicAiResponse();
            this.hideMicUserTranscriptOverlay();
            if (this.micFloatView != null) {
                this.micFloatView.setVisibility(View.GONE);
            }
        });
    }

    private void showMicAiResponse(String text) {
        this.runOnMainThread(() -> {
            if (this.txtMicAiResponse == null || text == null || text.trim().isEmpty()) {
                Log.d((String)TAG, (String)("showMicAiResponse skipped - view=" + (this.txtMicAiResponse != null) + " text=" + (text != null ? text.substring(0, Math.min(30, text.length())) : "null")));
                return;
            }
            if (this.layoutMicTextInput != null && this.layoutMicTextInput.getVisibility() == View.VISIBLE) {
                this.txtMicAiResponse.setVisibility(View.GONE);
                Log.d((String)TAG, (String)"showMicAiResponse skipped - text input is open");
                return;
            }
            Log.d((String)TAG, (String)("showMicAiResponse: " + text.substring(0, Math.min(50, text.length()))));
            this.txtMicAiResponse.setText((CharSequence)this.sanitizeGuidanceText(text));
            if (this.micAiResponseView != null) {
                this.micAiResponseView.setVisibility(View.VISIBLE);
            }
            this.txtMicAiResponse.setVisibility(View.VISIBLE);
            // Auto-hide after 8 seconds
            this.handler.postDelayed(() -> {
                if (this.txtMicAiResponse != null) {
                    this.txtMicAiResponse.setVisibility(View.GONE);
                }
                if (this.micAiResponseView != null) {
                    this.micAiResponseView.setVisibility(View.GONE);
                }
            }, 8000L);
        });
    }

    private void hideMicAiResponse() {
        this.runOnMainThread(() -> {
            if (this.txtMicAiResponse != null) {
                this.txtMicAiResponse.setVisibility(View.GONE);
            }
            if (this.micAiResponseView != null) {
                this.micAiResponseView.setVisibility(View.GONE);
            }
        });
    }

    private void toggleFloatingTextInput() {
        if (this.layoutMicTextInput != null && this.layoutMicTextInput.getVisibility() == View.VISIBLE) {
            this.closeFloatingTextInput(false);
        } else {
            this.openFloatingTextInput();
        }
    }

    private void openFloatingTextInput() {
        this.runOnMainThread(() -> {
            if (this.layoutMicTextInput == null || this.etMicTextInput == null || this.micFloatParams == null) {
                return;
            }
            if (this.isListening) {
                this.stopListening();
                this.stopMicRipple();
                this.startMicIdleHalo();
            }
            this.hideTemporaryMicHint();
            if (this.txtMicAiResponse != null) {
                this.txtMicAiResponse.setVisibility(View.GONE);
            }
            if (this.micUserTranscriptView != null) {
                this.micUserTranscriptView.setVisibility(View.GONE);
            }
            if (this.txtMicUserTranscript != null) {
                this.txtMicUserTranscript.setVisibility(View.GONE);
            }
            if (this.txtMicHint != null) {
                this.txtMicHint.setVisibility(View.GONE);
            }
            this.stopMicRipple();
            this.stopMicIdleHalo();
            if (this.layoutMicControls != null) {
                this.layoutMicControls.setVisibility(View.GONE);
            }
            this.layoutMicTextInput.setVisibility(View.VISIBLE);
            this.micFloatParams.y = this.dpToPx(MIC_FLOAT_BOTTOM_MARGIN_DP);
            this.micFloatParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            this.micFloatParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            if (this.micFloatView != null && this.micFloatView.isAttachedToWindow()) {
                this.windowManager.updateViewLayout(this.micFloatView, (ViewGroup.LayoutParams)this.micFloatParams);
            }
            this.etMicTextInput.requestFocus();
            InputMethodManager imm = (InputMethodManager)this.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput((View)this.etMicTextInput, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void closeFloatingTextInput(boolean clearText) {
        if (this.layoutMicTextInput == null || this.etMicTextInput == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager)this.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(this.etMicTextInput.getWindowToken(), 0);
        }
        if (clearText) {
            this.etMicTextInput.setText((CharSequence)"");
        }
        this.layoutMicTextInput.setVisibility(View.GONE);
        this.etMicTextInput.clearFocus();
        if (this.layoutMicControls != null) {
            this.layoutMicControls.setVisibility(View.VISIBLE);
        }
        if (this.micFloatParams != null) {
            this.micFloatParams.y = this.dpToPx(MIC_FLOAT_BOTTOM_MARGIN_DP);
            this.micFloatParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            this.micFloatParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            if (this.micFloatView != null && this.micFloatView.isAttachedToWindow()) {
                this.windowManager.updateViewLayout(this.micFloatView, (ViewGroup.LayoutParams)this.micFloatParams);
            }
        }
    }

    private void sendFloatingTextInput() {
        if (this.etMicTextInput == null) {
            return;
        }
        String text = this.etMicTextInput.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        this.closeFloatingTextInput(true);
        this.handleUserQuery(text);
    }

    private void minimizeMicControlsToBall() {
        this.runOnMainThread(() -> {
            this.micControlsMinimized = true;
            this.isBallMode = true;
            this.hideMicFloatOverlay();
            if (this.ballView != null) {
                this.ballView.setVisibility(View.VISIBLE);
                this.bringBallToFront();
            }
            this.updateBallStepViews();
            if (this.currentVoiceText != null && !this.currentVoiceText.trim().isEmpty()) {
                this.showInstructionBubble(this.currentVoiceText);
            }
        });
    }

    private void restoreMicControlsFromBall() {
        this.runOnMainThread(() -> {
            this.micControlsMinimized = false;
            this.isBallMode = false;
            this.hideCloseTarget();
            this.ballCloseDragMode = false;
            if (VOICE_ONLY_OVERLAY) {
                if (this.ballView != null) {
                    this.ballView.setVisibility(View.VISIBLE);
                }
            } else {
                if (this.ballView != null) {
                    this.ballView.setVisibility(View.GONE);
                }
                this.showMicFloatOverlay();
            }
        });
    }

    private boolean shouldFloatingBallShowGuidance() {
        return this.session != null && this.session.hasActiveTask();
    }

    private void syncVoiceOnlyInputSurface() {
        this.runOnMainThread(() -> {
            if (VOICE_ONLY_OVERLAY || this.micControlsMinimized) {
                if (this.ballView != null) {
                    this.ballView.setVisibility(View.VISIBLE);
                    this.bringBallToFront();
                }
                this.hideMicFloatOverlay();
                this.isBallMode = true;
            } else {
                if (this.ballView != null) {
                    this.ballView.setVisibility(View.GONE);
                }
                this.showMicFloatOverlay();
                this.isBallMode = false;
            }
        });
    }

    private String getFloatingBallGuidanceText() {
        if (this.currentVoiceText != null && !this.currentVoiceText.trim().isEmpty()) {
            return this.currentVoiceText;
        }
        if (this.uiState == OverlayUiState.ANALYZING) {
            return this.isChineseUi() ? "\u5206\u6790\u4e2d..." : "Analyzing...";
        }
        return this.isChineseUi() ? "\u6b63\u5728\u5904\u7406..." : "Working on it...";
    }

    private void bringBallToFront() {
        if (this.ballView == null || this.windowManager == null || this.ballParams == null || !this.ballView.isAttachedToWindow()) {
            return;
        }
        try {
            this.windowManager.updateViewLayout(this.ballView, (ViewGroup.LayoutParams)this.ballParams);
        }
        catch (Exception e) {
            Log.w((String)TAG, (String)("Unable to update floating ball: " + e.getMessage()));
        }
    }

    private void startMicRipple() {
        this.runOnMainThread(() -> {
            this.stopMicIdleHalo();
            this.hideTemporaryMicHint();
            this.stopMicRipple(); // cancel any existing
            this.micRippleAnim1 = this.buildRippleAnimator(this.micRing1, 0);
            this.micRippleAnim2 = this.buildRippleAnimator(this.micRing2, 350);
            this.micRippleAnim3 = this.buildRippleAnimator(this.micRing3, 700);
        });
    }

    private ValueAnimator buildRippleAnimator(View ring, long startDelayMs) {
        if (ring == null) return null;
        ring.setAlpha(0f);
        ring.setScaleX(1f);
        ring.setScaleY(1f);
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1050);
        anim.setStartDelay(startDelayMs);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setRepeatMode(ValueAnimator.RESTART);
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float scale = 1f + t * 0.85f;  // keep the ripple inside the transparent mic window
            float alpha = 0.55f * (1f - t); // fades from 0.55 to 0
            ring.setScaleX(scale);
            ring.setScaleY(scale);
            ring.setAlpha(alpha);
        });
        anim.start();
        return anim;
    }

    private void stopMicRipple() {
        for (ValueAnimator anim : new ValueAnimator[]{this.micRippleAnim1, this.micRippleAnim2, this.micRippleAnim3}) {
            if (anim != null) anim.cancel();
        }
        this.micRippleAnim1 = null;
        this.micRippleAnim2 = null;
        this.micRippleAnim3 = null;
        for (View ring : new View[]{this.micRing1, this.micRing2, this.micRing3}) {
            if (ring != null) {
                ring.setAlpha(0f);
                ring.setScaleX(1f);
                ring.setScaleY(1f);
            }
        }
    }

    private void startMicIdleHalo() {
        this.runOnMainThread(() -> {
            if (this.micIdleHalo == null || this.micFloatView == null || this.micFloatView.getVisibility() != View.VISIBLE) {
                return;
            }
            if (this.micIdleHaloAnimator != null && this.micIdleHaloAnimator.isRunning()) {
                return;
            }
            this.micIdleHalo.setVisibility(View.VISIBLE);
            this.micIdleHalo.setAlpha(0.36f);
            this.micIdleHalo.setScaleX(0.92f);
            this.micIdleHalo.setScaleY(0.92f);
            this.micIdleHaloAnimator = ValueAnimator.ofFloat(0f, 1f);
            this.micIdleHaloAnimator.setDuration(1800L);
            this.micIdleHaloAnimator.setRepeatCount(ValueAnimator.INFINITE);
            this.micIdleHaloAnimator.setRepeatMode(ValueAnimator.REVERSE);
            this.micIdleHaloAnimator.addUpdateListener(a -> {
                float t = (float)a.getAnimatedValue();
                float scale = 0.92f + 0.16f * t;
                float alpha = 0.28f + 0.22f * t;
                this.micIdleHalo.setScaleX(scale);
                this.micIdleHalo.setScaleY(scale);
                this.micIdleHalo.setAlpha(alpha);
            });
            this.micIdleHaloAnimator.start();
        });
    }

    private void stopMicIdleHalo() {
        if (this.micIdleHaloAnimator != null) {
            this.micIdleHaloAnimator.cancel();
            this.micIdleHaloAnimator = null;
        }
        if (this.micIdleHalo != null) {
            this.micIdleHalo.setAlpha(0f);
            this.micIdleHalo.setScaleX(1f);
            this.micIdleHalo.setScaleY(1f);
        }
    }

    private void showTemporaryMicHint() {
        if (this.txtMicHint == null || this.micHintShownOnce) {
            return;
        }
        this.micHintShownOnce = true;
        this.txtMicHint.setText((CharSequence)this.getMicFloatHintText());
        this.txtMicHint.setVisibility(View.VISIBLE);
        this.txtMicHint.animate().cancel();
        this.txtMicHint.setAlpha(0f);
        this.txtMicHint.animate().alpha(1f).setDuration(180L).start();
        this.hideMicHintRunnable = this::hideTemporaryMicHint;
        this.handler.postDelayed(this.hideMicHintRunnable, 3200L);
    }

    private void hideTemporaryMicHint() {
        if (this.hideMicHintRunnable != null) {
            this.handler.removeCallbacks(this.hideMicHintRunnable);
            this.hideMicHintRunnable = null;
        }
        if (this.txtMicHint != null) {
            this.txtMicHint.animate().cancel();
            this.txtMicHint.setAlpha(0f);
            this.txtMicHint.setVisibility(View.GONE);
        }
    }


    private void toggleChatPanel() {
        this.handler.post(() -> {
            if (this.chatView == null) {
                return;
            }
            if (this.isChatOpen) {
                this.chatView.setVisibility(View.GONE);
                this.isChatOpen = false;
                this.chatParams.flags |= (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                this.windowManager.updateViewLayout(this.chatView, (ViewGroup.LayoutParams)this.chatParams);
            } else {
                boolean hasMessages;
                this.chatParams.flags &= ~(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                this.windowManager.updateViewLayout(this.chatView, (ViewGroup.LayoutParams)this.chatParams);
                this.chatView.setVisibility(View.VISIBLE);
                this.isChatOpen = true;
                boolean bl = hasMessages = this.chatMessageContainer != null && this.chatMessageContainer.getChildCount() > 0;
                if (this.layoutChatEmpty != null) {
                    TextView emptyMsg;
                    if (hasMessages) {
                        this.layoutChatEmpty.setVisibility(View.GONE);
                    } else {
                        this.layoutChatEmpty.setVisibility(View.VISIBLE);
                    }
                    if (!hasMessages && (emptyMsg = (TextView)this.chatView.findViewById(R.id.txtChatEmptyMessage)) != null) {
                        emptyMsg.setText((CharSequence)this.getNoConversationsText());
                    }
                }
                if (this.chatScrollView != null) {
                    this.chatScrollView.post(() -> this.chatScrollView.fullScroll(View.FOCUS_DOWN));
                }
            }
        });
    }

    private void addChatMessage(boolean isUser, String text) {
        if (this.chatMessageContainer == null || text == null || text.trim().isEmpty()) {
            Log.d((String)TAG, (String)("addChatMessage SKIPPED - container=" + (this.chatMessageContainer != null) + " text=" + (text != null ? text.substring(0, Math.min(30, text.length())) : "null")));
            return;
        }
        Log.d((String)TAG, (String)("addChatMessage: isUser=" + isUser + " text=" + text.substring(0, Math.min(50, text.length())) + " containerChildCount=" + this.chatMessageContainer.getChildCount()));
        this.handler.post(() -> {
            if (this.chatMessageContainer.getChildCount() >= 50) {
                this.chatMessageContainer.removeViewAt(0);
            }
            if (this.layoutChatEmpty != null) {
                this.layoutChatEmpty.setVisibility(View.GONE);
            }
            TextView bubble = new TextView((Context)new ContextThemeWrapper((Context)this, R.style.Theme_SmartHelp));
            bubble.setText((CharSequence)text.trim());
            bubble.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15.0f * this.getOverlayTextScale());
            bubble.setLineSpacing(0.0f, 1.4f);
            int padH = this.dpToPx(12);
            int padV = this.dpToPx(10);
            bubble.setPadding(padH, padV, padH, padV);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = this.dpToPx(8);
            int maxWidth = (int)((float)this.getResources().getDisplayMetrics().widthPixels * 0.75f);
            if (isUser) {
                bubble.setTextColor(0xFFFFFFFF);  // White text
                bubble.setBackgroundColor(0xFF1565C0);  // Blue background
                bubble.setMaxWidth(maxWidth);
                lp.gravity = Gravity.END;
            } else {
                bubble.setTextColor(0xFF0A2342);  // Dark text
                bubble.setBackground(this.getDrawable(R.drawable.bg_chat_ai_card));
                bubble.setMaxWidth((int)((float)this.getResources().getDisplayMetrics().widthPixels * 0.8f));
                lp.gravity = Gravity.START;
            }
            bubble.setLayoutParams((ViewGroup.LayoutParams)lp);
            this.chatMessageContainer.addView((View)bubble);
            Log.d((String)TAG, (String)("addChatMessage ADDED - isUser=" + isUser + " newChildCount=" + this.chatMessageContainer.getChildCount()));
            if (this.chatScrollView != null) {
                this.chatScrollView.post(() -> this.chatScrollView.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void showTaskCompleteDialog(String goal, boolean zh) {
        // Compact Yes/No follow-up overlay shown at the bottom of the screen
        // after the AI says "All done. Do you need anything else?". Replaces
        // the older full-screen "All done" sheet with check icon and goal
        // quote \u2014 the AI's TTS already covers all of that information, so a
        // big modal was redundant and intrusive for elderly users.
        try {
            Button btnGoHome;
            View sheetView;
            this.removeTaskCompleteDialog();
            ContextThemeWrapper ctx = new ContextThemeWrapper((Context)this, R.style.Theme_SmartHelp);
            LayoutInflater inflater = (LayoutInflater)ctx.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            this.taskCompleteDialogView = sheetView = inflater.inflate(R.layout.dialog_task_complete, null);
            int layoutFlag = Build.VERSION.SDK_INT >= 26 ? 2038 : 2002;
            WindowManager.LayoutParams dialogParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,  // no FLAG_DIM_BEHIND \u2014 no dimmed background
                -3);
            dialogParams.gravity = Gravity.BOTTOM;
            // Lift the bar well above the system nav bar / gesture area so the
            // two pills float over the user's app instead of hugging the edge.
            dialogParams.y = this.dpToPx(140);
            // No dim \u2014 keep the user's app fully visible behind the small bar.
            dialogParams.dimAmount = 0.0f;
            this.windowManager.addView(sheetView, (ViewGroup.LayoutParams)dialogParams);
            Button btnAskAnother = (Button)sheetView.findViewById(R.id.btnAskAnother);
            if (btnAskAnother != null) {
                btnAskAnother.setText((CharSequence)"Yes");
                btnAskAnother.setOnClickListener(v -> {
                    this.removeTaskCompleteDialog();
                    this.awaitingCompletionFollowup = true;
                    this.switchToInputMode();
                    this.handler.postDelayed(this::requestVoiceInputFromOverlay, 300L);
                });
            }
            if ((btnGoHome = (Button)sheetView.findViewById(R.id.btnGoHome)) != null) {
                btnGoHome.setText((CharSequence)"No");
                btnGoHome.setOnClickListener(v -> {
                    this.awaitingCompletionFollowup = false;
                    this.removeTaskCompleteDialog();
                    this.stopService();
                });
            }
        }
        catch (Exception e) {
            Log.e((String)TAG, (String)("showTaskCompleteDialog failed: " + e.getMessage()));
        }
    }

    private void removeTaskCompleteDialog() {
        if (this.taskCompleteDialogView == null) {
            return;
        }
        this.removeViewSafely(this.taskCompleteDialogView);
        this.taskCompleteDialogView = null;
    }

    private void switchToBallMode() {
        this.runOnMainThread(() -> {
            this.closeFloatingTextInput(false);
            this.hideCloseTarget();
            this.ballCloseDragMode = false;
            this.isBallMode = true;
            this.micControlsMinimized = true;
            this.updateQuickActionsVisibility();
            if (this.overlayView != null) {
                this.overlayView.setVisibility(View.GONE);
            }
            this.hideMicFloatOverlay();
            if (this.ballView != null) {
                this.ballView.setVisibility(View.VISIBLE);
                this.bringBallToFront();
            }
            this.updateBallStepViews();
            // Show latest guidance as speech bubble
            if (this.currentVoiceText != null && !this.currentVoiceText.isEmpty()) {
                this.showInstructionBubble(this.currentVoiceText);
            }
        });
    }

    private void stopCurrentTask() {
        this.runOnMainThread(() -> {
            this.session.clearTask();
            this.cancelAutoAnalysis();
            this.hideHighlight();
            this.hideInstructionBubble();
            this.hideDoneButton();
            this.micControlsMinimized = false;
            this.uiState = OverlayUiState.IDLE;
            String cancelMsg = this.getTaskCancelledText();
            this.updateGuidanceText(cancelMsg);
            this.addChatMessage(false, cancelMsg);
            this.currentVoiceText = null;
            this.updateStepProgress();
            if (this.serverConnection != null && this.serverConnection.isConnected()) {
                this.serverConnection.sendText("User cancelled the task.");
            }
            this.switchToInputMode();
        });
    }

    private void switchToInputMode() {
        // Always stay in ball mode ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â the big bottom panel is removed.
        this.restoreMicControlsFromBall();
    }

    private void runOnMainThread(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            this.handler.post(action);
        }
    }

    private boolean isActionableInstruction(String instruction, int highlightX, int highlightY) {
        String[] actionHints;
        String[] statusHints;
        if (instruction == null) {
            return false;
        }
        if (highlightX >= 0 && highlightY >= 0) {
            return true;
        }
        String normalized = instruction.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return false;
        }
        for (String hint : statusHints = new String[]{"analyzing", "capturing", "capturing screen", "reconnecting", "connecting", "working on it", "listening", "i heard you", "please wait", "what do you want", "task complete", "let me explain", "assistant unavailable", "\u5206\u6790\u4e2d", "\u622a\u56fe\u4e2d", "\u91cd\u65b0\u8fde\u63a5", "\u8fde\u63a5\u4e2d", "\u6b63\u5728\u5904\u7406", "\u6b63\u5728\u542c", "\u6211\u542c\u5230", "\u8bf7\u7a0d\u5019", "\u4efb\u52a1\u5b8c\u6210", "\u6211\u518d\u89e3\u91ca"}) {
            if (!normalized.contains(hint)) continue;
            return false;
        }
        for (String hint : actionHints = new String[]{"tap", "click", "press", "open", "select", "choose", "swipe", "scroll", "find", "enter", "type", "go to", "\u70b9\u51fb", "\u70b9\u5f00", "\u6309\u4f4f", "\u6309", "\u6253\u5f00", "\u9009\u62e9", "\u6ed1\u52a8", "\u6eda\u52a8", "\u627e\u5230", "\u8f93\u5165", "\u524d\u5f80"}) {
            if (!normalized.contains(hint)) continue;
            return true;
        }
        return false;
    }

    private boolean isTapActionText(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return false;
        }
        String[] tapHints = new String[]{"tap", "click", "press", "select", "choose", "open", "\u70b9", "\u70b9\u51fb", "\u9009\u62e9", "\u6253\u5f00"};
        for (String hint : tapHints) {
            if (normalized.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private String getBallStepMetaText() {
        boolean zh = this.isChineseUi();
        if (this.uiState == OverlayUiState.COMPLETED) {
            return zh ? "\u8fd9\u4e2a\u4efb\u52a1\u5df2\u7ecf\u5b8c\u6210\u3002\u60a8\u53ef\u4ee5\u5f00\u59cb\u65b0\u7684\u8bf7\u6c42\u3002" : "This task is complete. You can start a new request.";
        }
        if (this.session.getStepCount() > 0) {
            return zh ? "\u5b8c\u6210\u8fd9\u4e00\u6b65\u540e\uff0c\u6211\u4f1a\u68c0\u67e5\u4e0b\u4e00\u6b65\u3002" : "Finish this step and I will check the next one.";
        }
        return zh ? "\u6536\u5230\u65b0\u6b65\u9aa4\u540e\uff0c\u4f1a\u663e\u793a\u5728\u8fd9\u91cc\u3002" : "New guidance will appear here.";
    }

    private void updateBallStepViews() {
        // (Analyzing / Connecting / 分析中 ...) — user wants the status text only.
        if (this.txtBallStepBadge != null) {
            this.txtBallStepBadge.setVisibility(View.GONE);
        }
        if (this.txtInstructionBubbleStep != null) {
            this.txtInstructionBubbleStep.setVisibility(View.GONE);
        }
        if (this.txtGuidanceBallStep != null) {
            this.txtGuidanceBallStep.setVisibility(View.GONE);
        }
        if (this.txtGuidanceBallMeta != null) {
            this.txtGuidanceBallMeta.setText((CharSequence)this.getBallStepMetaText());
            if (this.currentVoiceText != null && !this.currentVoiceText.isEmpty()) {
                this.txtGuidanceBallMeta.setVisibility(View.VISIBLE);
            } else {
                this.txtGuidanceBallMeta.setVisibility(View.GONE);
            }
        }
    }

    private void showInstructionBubble(String text) {
        if (VOICE_ONLY_OVERLAY) {
            this.hideInstructionBubble();
            return;
        }
        this.updateBallStepViews();
        if (this.txtInstructionBubble != null) {
            this.txtInstructionBubble.setText((CharSequence)text);
        }
        if (this.cardInstructionBubble != null) {
            this.cardInstructionBubble.setVisibility(View.VISIBLE);
        }
        if (this.imgBubbleTail != null) {
            this.imgBubbleTail.setVisibility(View.VISIBLE);
        }
    }

    private void hideInstructionBubble() {
        if (this.cardInstructionBubble != null) {
            this.cardInstructionBubble.setVisibility(View.GONE);
        }
        if (this.imgBubbleTail != null) {
            this.imgBubbleTail.setVisibility(View.GONE);
        }
    }

    private void showDetailPopup() {
        if (VOICE_ONLY_OVERLAY) {
            if (!this.shouldFloatingBallShowGuidance()) {
                this.hidePanelOverlays();
                return;
            }
            this.updateBallStepViews();
            if (this.txtGuidanceBall != null) {
                this.txtGuidanceBall.setText((CharSequence)this.sanitizeGuidanceText(this.getFloatingBallGuidanceText()));
            }
            if (this.ballView != null) {
                this.ballView.setVisibility(View.VISIBLE);
            }
            if (this.cardGuidanceBall != null) {
                this.cardGuidanceBall.setVisibility(View.VISIBLE);
            }
            return;
        }
        this.updateBallStepViews();
        if (this.txtGuidanceBall != null && this.currentVoiceText != null) {
            this.txtGuidanceBall.setText((CharSequence)this.currentVoiceText);
        }
        if (this.cardGuidanceBall != null) {
            this.cardGuidanceBall.setVisibility(View.VISIBLE);
        }
    }

    private void populateQuickActionChips() {
        if (this.layoutQuickActions == null) {
            return;
        }
        this.layoutQuickActions.removeAllViews();
        List<String[]> actions = AppPrefs.getQuickActions((Context)this);
        int gap = this.dpToPx(8);
        int minHeight = this.dpToPx(78);
        for (int i = 0; i < actions.size(); i += 2) {
            LinearLayout row = new LinearLayout((Context)this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                rowLp.topMargin = gap;
            }
            row.setLayoutParams((ViewGroup.LayoutParams)rowLp);
            row.addView(this.buildQuickActionCard(actions.get(i), minHeight, true));
            if (i + 1 < actions.size()) {
                row.addView(this.buildQuickActionCard(actions.get(i + 1), minHeight, false));
            } else {
                View spacer = new View((Context)this);
                spacer.setLayoutParams((ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, minHeight, 1.0f));
                row.addView(spacer);
            }
            this.layoutQuickActions.addView((View)row);
        }
    }

    private void updateQuickActionsVisibility() {
        if (this.layoutQuickActionsSection == null) {
            return;
        }
        boolean hasActiveTask = this.session.hasActiveTask();
        boolean visible = !this.isBallMode && !this.isListening && this.uiState != OverlayUiState.ERROR && !hasActiveTask;
        if (visible) {
            this.layoutQuickActionsSection.setVisibility(View.VISIBLE);
        } else {
            this.layoutQuickActionsSection.setVisibility(View.GONE);
        }
    }

    private float getOverlayTextScale() {
        return AppPrefs.getTextScaleMultiplier((Context)this);
    }

    private void setScaledTextSize(TextView view, float baseSp) {
        if (view == null) {
            return;
        }
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, baseSp * this.getOverlayTextScale());
    }

    private void applyTextSizePreferences() {
        this.setScaledTextSize(this.txtGuidance, 20.0f);
        this.setScaledTextSize(this.txtStepProgress, 12.0f);
        this.setScaledTextSize(this.txtUserQuery, 15.0f);
        this.setScaledTextSize(this.txtQuickActionsTitle, 14.0f);
        this.setScaledTextSize(this.txtQuickActionsSubtitle, 12.0f);
        this.setScaledTextSize(this.txtInputLabel, 15.0f);
        this.setScaledTextSize(this.txtListening, 12.0f);
        this.setScaledTextSize(this.txtHint, 12.0f);
        this.setScaledTextSize(this.txtThinkingDots, 12.0f);
        this.setScaledTextSize(this.txtErrorMessage, 14.0f);
        this.setScaledTextSize(this.txtGuidanceBall, 15.0f);
        this.setScaledTextSize(this.txtGuidanceBallStep, 11.0f);
        this.setScaledTextSize(this.txtGuidanceBallMeta, 12.0f);
        this.setScaledTextSize(this.txtInstructionBubble, 15.0f);
        this.setScaledTextSize(this.txtInstructionBubbleStep, 11.0f);
        this.setScaledTextSize(this.txtBallStepBadge, 11.0f);
        this.setScaledTextSize(this.txtCloseTargetLabel, 12.0f);
    }

    private View buildQuickActionCard(String[] action, int minHeight, boolean addRightMargin) {
        int[] colors = this.getQuickActionColors(action);
        LinearLayout card = new LinearLayout((Context)this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(this.dpToPx(12), this.dpToPx(12), this.dpToPx(12), this.dpToPx(12));
        card.setClickable(true);
        card.setFocusable(true);
        card.setBackground(this.createQuickActionBackground(colors[0], colors[1]));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        if (addRightMargin) {
            lp.setMarginEnd(this.dpToPx(8));
        }
        card.setLayoutParams((ViewGroup.LayoutParams)lp);
        TextView icon = new TextView((Context)this);
        icon.setText((CharSequence)this.getQuickActionIcon(action));
        icon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 28.0f * this.getOverlayTextScale());
        icon.setIncludeFontPadding(false);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iconLp.setMarginEnd(this.dpToPx(10));
        icon.setLayoutParams((ViewGroup.LayoutParams)iconLp);
        LinearLayout textCol = new LinearLayout((Context)this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams((ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        TextView title = new TextView((Context)this);
        title.setText((CharSequence)action[0]);
        title.setTextColor(-14405571);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18.0f * this.getOverlayTextScale());
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView subtitle = new TextView((Context)this);
        subtitle.setText((CharSequence)this.getQuickActionSubtitle(action));
        subtitle.setTextColor(-9669504);
        subtitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.0f * this.getOverlayTextScale());
        subtitle.setMaxLines(2);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleLp.topMargin = this.dpToPx(2);
        subtitle.setLayoutParams((ViewGroup.LayoutParams)subtitleLp);
        textCol.addView((View)title);
        textCol.addView((View)subtitle);
        card.addView((View)icon);
        card.addView((View)textCol);
        card.setOnClickListener(v -> this.handleUserQuery(action[1]));
        return card;
    }

    private String getQuickActionIcon(String[] action) {
        String query = action[1].toLowerCase();
        if (query.contains("call")) {
            return "\u260e";
        }
        if (query.contains("whatsapp")) {
            return "\u2709";
        }
        if (query.contains("camera")) {
            return "\u25c9";
        }
        if (query.contains("scam")) {
            return "!";
        }
        return "+";
    }

    private Drawable createQuickActionBackground(int fillColor, int strokeColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fillColor);
        bg.setCornerRadius((float)this.dpToPx(20));
        bg.setStroke(this.dpToPx(1), strokeColor);
        return bg;
    }

    private Drawable createQuickActionBadgeBackground(int fillColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fillColor);
        bg.setCornerRadius((float)this.dpToPx(999));
        return bg;
    }

    private String getQuickActionBadge(String[] action) {
        boolean zh = this.isChineseUi();
        String query = action[1].toLowerCase();
        if (query.contains("call")) {
            return zh ? "\u5bb6\u4eba\u8054\u7cfb" : "Family";
        }
        if (query.contains("whatsapp")) {
            return zh ? "\u5373\u65f6\u6d88\u606f" : "Message";
        }
        if (query.contains("camera")) {
            return zh ? "\u65e5\u5e38\u5de5\u5177" : "Tool";
        }
        if (query.contains("scam")) {
            return zh ? "\u5b89\u5168\u68c0\u67e5" : "Safety";
        }
        return zh ? "\u5f00\u59cb" : "Start";
    }

    private String getQuickActionSubtitle(String[] action) {
        boolean zh = this.isChineseUi();
        String query = action[1].toLowerCase();
        if (query.contains("call")) {
            return zh ? "\u4e00\u6b65\u4e00\u6b65\u6253\u5f00\u7535\u8bdd\u5e76\u62e8\u6253" : "Open the phone and call step by step";
        }
        if (query.contains("whatsapp")) {
            return zh ? "\u53d1\u9001\u8baf\u606f\u7ed9\u5bb6\u4eba\u6216\u670b\u53cb" : "Send a message to family or friends";
        }
        if (query.contains("camera")) {
            return zh ? "\u6253\u5f00\u76f8\u673a\u51c6\u5907\u62cd\u7167" : "Open the camera and get ready to take a photo";
        }
        if (query.contains("scam")) {
            return zh ? "\u5148\u68c0\u67e5\u94fe\u63a5\u3001\u8baf\u606f\u6216\u4e8c\u7ef4\u7801" : "Check a link, message, or QR code first";
        }
        return zh ? "\u70b9\u4e00\u4e0b\u5c31\u5f00\u59cb\u5f15\u5bfc" : "Tap once to begin guided help";
    }

    private int[] getQuickActionColors(String[] action) {
        String query = action[1].toLowerCase();
        if (query.contains("call")) {
            return new int[]{-985089, -2824197, -15836534, -2494721};
        }
        if (query.contains("whatsapp")) {
            return new int[]{-918539, -2953508, -15177157, -2230553};
        }
        if (query.contains("camera")) {
            return new int[]{-2068, -860219, -6137070, -5944};
        }
        if (query.contains("scam")) {
            return new int[]{-3345, -731444, -5288403, -7977};
        }
        return new int[]{-1, -1583668, -8639214, -596773};
    }

    private void showDoneButton() {
        this.handler.post(() -> {
            if (this.layoutDoneButtons != null) {
                this.layoutDoneButtons.setVisibility(View.VISIBLE);
            }
            if (this.layoutDoneBallButtons != null) {
                this.layoutDoneBallButtons.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideDoneButton() {
        this.handler.post(() -> {
            if (this.layoutDoneButtons != null) {
                this.layoutDoneButtons.setVisibility(View.GONE);
            }
            if (this.layoutDoneBallButtons != null) {
                this.layoutDoneBallButtons.setVisibility(View.GONE);
            }
        });
    }

    private void scheduleAutoAnalysis() {
        this.scheduleAutoAnalysis(AUTO_ANALYZE_INITIAL_INTERVAL_MS);
    }

    private void scheduleAutoAnalysis(long delayMs) {
        this.cancelAutoAnalysis();
        if (!this.session.hasActiveTask()) {
            return;
        }
        this.autoAnalyzeRunnable = () -> {
            this.autoAnalyzeRunnable = null;
            Log.d((String)TAG, (String)("Auto-analyzing screen (delay=" + delayMs + "ms)"));
            this.hideDoneButton();
            this.analyzeCurrentScreen(true);
        };
        this.handler.postDelayed(this.autoAnalyzeRunnable, delayMs);
        Log.d((String)TAG, (String)("Auto-analysis scheduled in " + delayMs + "ms"));
    }

    private void cancelAutoAnalysis() {
        if (this.autoAnalyzeRunnable != null) {
            this.handler.removeCallbacks(this.autoAnalyzeRunnable);
            this.autoAnalyzeRunnable = null;
            Log.d((String)TAG, (String)"Auto-analysis cancelled");
        }
    }

    private void handleUserActionDetected(int eventType, String sourcePackage) {
        if (this.session == null || !this.session.hasActiveTask()) {
            return;
        }
        if (!this.awaitingGuidedUserAction && this.autoAnalyzeRunnable == null && this.userActionAnalyzeRunnable == null) {
            Log.d((String)TAG, (String)("Accessibility action ignored; not awaiting guided action eventType=" + eventType + " source=" + sourcePackage));
            return;
        }

        this.cancelAutoAnalysis();
        this.hideDoneButton();
        this.hideHighlight();
        this.cancelUserActionAnalysis();

        this.userActionAnalyzeRunnable = () -> {
            this.userActionAnalyzeRunnable = null;
            if (this.session == null || !this.session.hasActiveTask()) {
                return;
            }
            this.awaitingGuidedUserAction = false;
            Log.d((String)TAG, (String)("Analyzing screen after accessibility action eventType=" + eventType + " source=" + sourcePackage));
            this.analyzeCurrentScreen(true);
        };
        this.handler.postDelayed(this.userActionAnalyzeRunnable, USER_ACTION_VERIFY_DELAY_MS);
        Log.d((String)TAG, (String)("Accessibility action detected; verify scheduled in " + USER_ACTION_VERIFY_DELAY_MS + "ms eventType=" + eventType + " source=" + sourcePackage));
    }

    private void handleSensitiveScreenDetected(String sourcePackage) {
        Log.d((String)TAG, (String)("Sensitive screen detected by accessibility: " + sourcePackage));
        this.cancelAutoAnalysis();
        this.cancelUserActionAnalysis();
        this.awaitingGuidedUserAction = false;
        this.hideDoneButton();
        this.hideHighlight();
        this.hideThinking();
        if (this.session != null) {
            this.session.pauseForSensitiveScreen();
        }

        String message = PrivacySafety.getSensitivePauseMessage((Context)this);
        this.currentVoiceText = message;
        this.updateGuidanceText(message);
        if (this.isBallMode) {
            this.showInstructionBubble(message);
        }
        this.showMicAiResponse(message);
        this.addChatMessage(false, message);
        this.speakLocalPrompt(message);

        if (this.serverConnection != null && this.serverConnection.isConnected()) {
            this.serverConnection.sendSensitiveScreen(sourcePackage);
        }
    }

    private void cancelUserActionAnalysis() {
        if (this.userActionAnalyzeRunnable != null) {
            this.handler.removeCallbacks(this.userActionAnalyzeRunnable);
            this.userActionAnalyzeRunnable = null;
        }
    }

    private void onScreenUnchangedReminder() {
        if (this.session.hasLastHighlight()) {
            Log.d((String)TAG, (String)"Reminding user with last highlight");
            this.showLastHighlight();
            this.showTapArrowGuidance();
        } else {
            String reminder = this.getNoClearTargetText();
            Log.d((String)TAG, (String)("Reminding user: " + reminder));
            this.updateGuidanceText(reminder);
            if (this.isBallMode) {
                this.showInstructionBubble(reminder);
            }
        }
        if (this.noTapReminderCount < 2) {
            this.scheduleAutoAnalysis();
        }
    }

    private void onScreenStillUnchanged() {
        if (this.session.hasLastHighlight()) {
            this.showLastHighlight();
            this.showTapArrowGuidance();
        } else {
            String reminder = this.getNoClearTargetText();
            this.updateGuidanceText(reminder);
            if (this.isBallMode) {
                this.showInstructionBubble(reminder);
            }
        }
        // Keep polling at a longer interval so the system can pick up a
        // delayed tap on its own, without waiting for the server-side 60s
        // timeout. Necessary on devices where accessibility events don't
        // reach us (MIUI, etc.).
        this.scheduleAutoAnalysis(AUTO_ANALYZE_LONG_INTERVAL_MS);
    }

    private void analyzeCurrentScreen() {
        this.analyzeCurrentScreen(false);
    }

    private void analyzeCurrentScreen(boolean verify) {
        this.cancelUserActionAnalysis();
        this.awaitingGuidedUserAction = false;
        if (verify) {
            this.hideHighlight();
        }
        boolean zh = "zh".equals(AppPrefs.getLanguage((Context)this));
        // Centralised: every screen-analyze run (server-requested, accessibility
        // verify, auto-analysis) replaces the stale instruction bubble so the
        // user does not see "Hold on, looking again" or the previous step's
        // text while we are mid-process. We first show "Capturing screen..."
        // (the broadcast below triggers ScreenCaptureService); once the
        // SCREENSHOT_READY handler fires and sends the image to the server,
        // the bubble switches to "Analyzing...".
        String capturingText = this.getCapturingText();
        this.currentVoiceText = capturingText;
        this.updateGuidanceText(capturingText);
        if (this.isBallMode) {
            this.showInstructionBubble(capturingText);
        }
        Intent intent = new Intent("com.smarthelp.ANALYZE_REQUEST");
        intent.setPackage(this.getPackageName());
        String query = this.session.hasActiveTask() ? this.session.getCurrentQuery().replaceAll("\\s*\\(.*\\)", "").trim() : null;
        if (query != null) {
            intent.putExtra("userQuery", query);
        }
        this.session.setPendingVerify(verify);
        Log.d((String)TAG, (String)("analyzeCurrentScreen verify=" + verify + " query=" + query));
        this.sendBroadcast(intent);
    }

    private void displayCurrentStep() {
        if (this.currentVoiceText != null && this.txtGuidance != null) {
            this.txtGuidance.setText((CharSequence)this.currentVoiceText);
        }
    }

    private void replayCurrentGuidanceAudio() {
        if (this.currentVoiceText != null && !this.currentVoiceText.trim().isEmpty()) {
            if (this.lastAiVoiceAudioData != null && this.lastAiVoiceText != null && this.lastAiVoiceText.equals(this.currentVoiceText.trim())) {
                this.playAiVoice(this.lastAiVoiceAudioData, this.lastAiVoiceText);
                return;
            }
            this.speakLocalPrompt(this.currentVoiceText);
            return;
        }
        Toast.makeText((Context)this, (CharSequence)this.getPreviousAudioUnavailableText(), (int)0).show();
    }

    private void speak(String text) {
        Log.d((String)TAG, (String)("Overlay Guidance Instruction Logged: " + text));
    }

    private void repeatCurrentStep() {
        if (this.currentVoiceText != null && !this.currentVoiceText.isEmpty()) {
            this.replayCurrentGuidanceAudio();
        }
    }

    private void nextStep() {
        this.analyzeCurrentScreen();
    }

    private void stopService() {
        this.shuttingDown = true;
        this.awaitingCompletionFollowup = false;
        this.removeTaskCompleteDialog();
        this.cancelAutoAnalysis();
        this.hideDoneButton();
        this.hideCloseTarget();
        this.ballCloseDragMode = false;
        Intent stopIntent = new Intent("com.smarthelp.STOP_SERVICE");
        stopIntent.setPackage(this.getPackageName());
        this.sendBroadcast(stopIntent);
        Intent captureStopIntent = new Intent((Context)this, ScreenCaptureService.class);
        captureStopIntent.setAction("com.smarthelp.STOP_SERVICE");
        try {
            this.startService(captureStopIntent);
        }
        catch (Exception e) {
            Log.w((String)TAG, (String)("Unable to send capture stop action: " + e.getMessage()));
        }
        this.stopService(new Intent((Context)this, ScreenCaptureService.class));
        Intent stoppedIntent = new Intent("com.smarthelp.SERVICE_STOPPED");
        stoppedIntent.setPackage(this.getPackageName());
        this.sendBroadcast(stoppedIntent);
        this.stopMicRipple();
        this.stopMicIdleHalo();
        this.hideTemporaryMicHint();
        this.removeViewSafely(this.micFloatView);
        this.micFloatView = null;
        this.stopSelf();
    }

    private boolean isChineseUi() {
        return "zh".equals(AppPrefs.getLanguage((Context)this));
    }

    private String getAssistantUnavailableText() {
        return this.isChineseUi() ? "\u52a9\u624b\u6682\u65f6\u65e0\u6cd5\u8fde\u63a5\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u3002" : "Assistant is unavailable. Please check your internet.";
    }

    private String getListeningText() {
        return this.isChineseUi() ? "\u6b63\u5728\u542c..." : "Listening...";
    }

    private String getPreparingMicText() {
        return this.isChineseUi() ? "\u51c6\u5907\u4e2d..." : "Get ready...";
    }

    private String getHeardYouText() {
        return this.isChineseUi() ? "\u6211\u542c\u5230\u4e86" : "I heard you";
    }

    private String getDidNotCatchText() {
        return this.isChineseUi() ? "\u6ca1\u542c\u6e05" : "Didn't catch that";
    }

    private String getDidNotCatchRetryText() {
        return this.isChineseUi() ? "\u6ca1\u542c\u6e05\uff0c\u518d\u8bf4\u4e00\u6b21..." : "Didn't catch that, try again...";
    }

    private String getLanguageUnavailableText() {
        return this.isChineseUi() ? "\u8bed\u8a00\u4e0d\u53ef\u7528" : "Language unavailable";
    }

    private String getVoiceUnavailableText() {
        return this.isChineseUi() ? "\u8bed\u97f3\u4e0d\u53ef\u7528" : "Voice unavailable";
    }

    private String getReconnectingText() {
        return this.isChineseUi() ? "\u91cd\u65b0\u8fde\u63a5\u4e2d..." : "Reconnecting...";
    }

    private String getReconnectingWaitText() {
        return this.isChineseUi() ? "\u6b63\u5728\u91cd\u65b0\u8fde\u63a5\uff0c\u8bf7\u7a0d\u5019..." : "Reconnecting, please wait...";
    }

    private String getAnalyzingText() {
        return this.isChineseUi() ? "\u5206\u6790\u4e2d..." : "Analyzing...";
    }

    private String getCapturingText() {
        // "\u622a\u56fe\u4e2d..." / "Capturing screen..." \u2014 shown while the screen is being
        // captured, before the screenshot is sent to the server for analysis.
        return this.isChineseUi() ? "\u622a\u56fe\u4e2d..." : "Capturing screen...";
    }

    private String getAllowMicPermissionText() {
        return this.isChineseUi() ? "\u8bf7\u5141\u8bb8\u9ea6\u514b\u98ce\u6743\u9650" : "Please allow mic permission";
    }

    private String getEnableMicPermissionText() {
        return this.isChineseUi() ? "\u8bf7\u5230\u7cfb\u7edf\u8bbe\u7f6e\u5f00\u542f\u9ea6\u514b\u98ce\u6743\u9650" : "Please enable mic permission in Settings";
    }

    private String getMicPermissionBlockedText() {
        return this.isChineseUi() ? "\u9ea6\u514b\u98ce\u5df2\u6388\u6743\uff0c\u4f46\u7cfb\u7edf\u963b\u6b62\u4e86\u60ac\u6d6e\u7a97\u8bed\u97f3\u3002\u8bf7\u56de\u5230 SmartHelp+ \u518d\u8bd5\u4e00\u6b21\u3002" : "Voice input needs a foreground screen. Tap the mic to continue.";
    }

    private String getNoConversationsText() {
        return this.isChineseUi() ? "\u6682\u65e0\u5bf9\u8bdd\u8bb0\u5f55" : "No conversations yet";
    }

    private String getTaskCancelledText() {
        return this.isChineseUi() ? "\u4efb\u52a1\u5df2\u53d6\u6d88" : "Task cancelled";
    }

    private String getTapArrowText() {
        return this.isChineseUi() ? "\u8bf7\u70b9\u51fb\u6a59\u8272\u5c0f\u70b9" : "Please tap the orange dot";
    }

    private String getNoClearTargetText() {
        return this.isChineseUi() ? "\u6211\u8fd8\u6ca1\u627e\u5230\u660e\u786e\u7684\u70b9\u51fb\u4f4d\u7f6e\uff0c\u8bf7\u7a0d\u7b49\u6216\u6362\u4e00\u79cd\u8bf4\u6cd5\u3002" : "I still cannot find a clear place to tap. Please wait or describe it another way.";
    }

    private String getPreviousAudioUnavailableText() {
        return this.isChineseUi() ? "\u6682\u65f6\u65e0\u6cd5\u91cd\u64ad\u4e0a\u4e00\u6bb5\u8bed\u97f3" : "Previous guidance audio is not available yet";
    }

    private String getExplainAgainText() {
        return this.isChineseUi() ? "\u6211\u518d\u89e3\u91ca\u4e00\u6b21..." : "Let me explain it more clearly...";
    }

    private void refreshLocalizedUiLegacy() {
        this.applyCleanLocalizedUi();
    }

    private boolean applyCleanLocalizedUi() {
        boolean zh = this.isChineseUi();
        if (this.btnShowAgain != null) {
            this.btnShowAgain.setText((CharSequence)(zh ? "\u518d\u663e\u793a" : "Show Again"));
        }
        if (this.btnNeedHelp != null) {
            this.btnNeedHelp.setText((CharSequence)(zh ? "\u627e\u4e0d\u5230" : "Need Help"));
        }
        if (this.btnDoneStep != null) {
            this.btnDoneStep.setText((CharSequence)(zh ? "\u5b8c\u6210\u4e86" : "Done"));
        }
        if (this.btnShowAgainBall != null) {
            this.btnShowAgainBall.setText((CharSequence)(zh ? "\u518d\u663e\u793a" : "Show Again"));
        }
        if (this.btnNeedHelpBall != null) {
            this.btnNeedHelpBall.setText((CharSequence)(zh ? "\u627e\u4e0d\u5230" : "Need Help"));
        }
        if (this.btnDoneBall != null) {
            this.btnDoneBall.setText((CharSequence)(zh ? "\u5b8c\u6210\u4e86" : "Done"));
        }
        if (this.txtWelcomeLabel != null) {
            this.txtWelcomeLabel.setText((CharSequence)"SmartHelp+");
        }
        if (this.txtWelcomeSubtitle != null) {
            this.txtWelcomeSubtitle.setText((CharSequence)(zh ? "\u6211\u4f1a\u4e00\u6b65\u4e00\u6b65\u5e26\u60a8\u5b8c\u6210\u3002" : "I will guide you one step at a time."));
        }
        if (this.txtQuickActionsTitle != null) {
            this.txtQuickActionsTitle.setText((CharSequence)(zh ? "\u4ece\u8fd9\u91cc\u5f00\u59cb" : "Start here"));
        }
        if (this.txtQuickActionsSubtitle != null) {
            this.txtQuickActionsSubtitle.setText((CharSequence)(zh ? "\u9009\u4e00\u4e2a\u7b80\u5355\u4efb\u52a1" : "Pick one easy task"));
        }
        if (this.btnMoreActions != null) {
            this.btnMoreActions.setText((CharSequence)(zh ? "\u66f4\u591a\u529f\u80fd ->" : "More features ->"));
        }
        if (this.txtInputLabel != null) {
            this.txtInputLabel.setText((CharSequence)(zh ? "\u544a\u8bc9\u6211\u60a8\u9700\u8981\u4ec0\u4e48" : "Tell me what you need"));
        }
        if (this.txtHint != null && !this.isListening) {
            this.txtHint.setText((CharSequence)this.getTapMicHint());
        }
        if (this.txtCloseTargetLabel != null) {
            this.txtCloseTargetLabel.setText((CharSequence)(zh ? "\u62d6\u5230\u8fd9\u91cc\u5173\u95ed" : "Drag here to close"));
        }
        if (this.txtGuidance != null && this.currentVoiceText == null) {
            this.txtGuidance.setText((CharSequence)this.getWelcomePrompt());
        }
        this.applyTextSizePreferences();
        this.updateBallStepViews();
        this.updateQuickActionsVisibility();
        return true;
    }

    private void refreshLocalizedUi() {
        this.applyCleanLocalizedUi();
    }

    private void ensureOverlayHelpButton() {
        if (!(this.layoutDoneButtons instanceof LinearLayout) || this.btnNeedHelp != null) {
            return;
        }
        this.btnNeedHelp = this.buildActionButton(52, 14, -1086464);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, this.dpToPx(52), 1.0f);
        lp.setMarginEnd(this.dpToPx(6));
        this.btnNeedHelp.setLayoutParams((ViewGroup.LayoutParams)lp);
        ((LinearLayout)this.layoutDoneButtons).addView((View)this.btnNeedHelp, 1);
    }

    private void ensureBallHelpButton() {
        if (!(this.layoutDoneBallButtons instanceof LinearLayout) || this.btnNeedHelpBall != null) {
            return;
        }
        this.btnNeedHelpBall = this.buildActionButton(44, 13, -1086464);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, this.dpToPx(44), 1.0f);
        lp.setMarginEnd(this.dpToPx(6));
        this.btnNeedHelpBall.setLayoutParams((ViewGroup.LayoutParams)lp);
        ((LinearLayout)this.layoutDoneBallButtons).addView((View)this.btnNeedHelpBall, 1);
    }

    private MaterialButton buildActionButton(int heightDp, int textSizeSp, int strokeColor) {
        MaterialButton button = new MaterialButton((Context)new ContextThemeWrapper((Context)this, R.style.Theme_SmartHelp), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setAllCaps(false);
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, (float)textSizeSp);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setCornerRadius(this.dpToPx(heightDp / 2));
        button.setStrokeWidth(Math.max(1, this.dpToPx(1)));
        button.setStrokeColor(ColorStateList.valueOf((int)strokeColor));
        button.setTextColor(strokeColor);
        return button;
    }

    private void requestExtraHelp() {
        if (!this.session.hasActiveTask()) {
            return;
        }
        boolean zh = this.isChineseUi();
        String status = this.getExplainAgainText();
        this.updateGuidanceText(status);
        if (this.isBallMode) {
            this.showInstructionBubble(status);
            if (this.cardGuidanceBall != null) {
                this.cardGuidanceBall.setVisibility(View.GONE);
            }
        }
        if (this.serverConnection != null && this.serverConnection.isConnected()) {
            this.serverConnection.sendText("__NEED_HELP_FIND_TARGET__");
        } else {
            Toast.makeText((Context)this, (CharSequence)this.getReconnectingWaitText(), (int)0).show();
        }
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        this.startOverlayForegroundIfNeeded();
        return Service.START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, (CharSequence)"SmartHelp overlay", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps SmartHelp+ guidance active on screen");
        NotificationManager manager = (NotificationManager)this.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent intent = new Intent((Context)this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity((Context)this, (int)0, (Intent)intent, (int)flags);
        return new NotificationCompat.Builder((Context)this, CHANNEL_ID).setContentTitle((CharSequence)"SmartHelp+ is active").setContentText((CharSequence)"Voice guidance overlay is running").setSmallIcon(android.R.drawable.ic_menu_info_details).setContentIntent(pendingIntent).setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build();
    }

    private void startOverlayForegroundIfNeeded() {
        if (this.foregroundStarted) {
            return;
        }
        this.startForeground(1002, this.createNotification());
        this.foregroundStarted = true;
    }

    private void removeViewSafely(View view) {
        if (view == null || this.windowManager == null) {
            return;
        }
        if (!view.isAttachedToWindow()) {
            return;
        }
        try {
            this.windowManager.removeView(view);
        }
        catch (IllegalArgumentException e) {
            Log.w((String)TAG, (String)("removeView skipped: " + e.getMessage()));
        }
    }

    public void onDestroy() {
        Log.d((String)TAG, (String)"OverlayService destroyed");
        this.shuttingDown = true;
        running = false;
        this.publishConnectionStatus(false);
        this.cancelAutoAnalysis();
        this.cancelUserActionAnalysis();
        this.cancelSilenceTimer();
        this.cancelBallCloseLongPress();
        this.stopMicRipple();
        this.stopMicIdleHalo();
        this.hideTemporaryMicHint();
        if (this.thinkingAnimRunnable != null) {
            this.handler.removeCallbacks(this.thinkingAnimRunnable);
        }
        this.handler.removeCallbacksAndMessages(null);
        if (this.serverConnection != null) {
            this.serverConnection.disconnect();
            this.serverConnection = null;
        }
        if (this.pendingTranscriptSubmitRunnable != null) {
            this.handler.removeCallbacks(this.pendingTranscriptSubmitRunnable);
            this.pendingTranscriptSubmitRunnable = null;
        }
        if (this.speechRecognizer != null) {
            try {
                this.speechRecognizer.cancel();
                this.speechRecognizer.destroy();
            }
            catch (Exception exception) {
                // empty catch block
            }
            this.speechRecognizer = null;
        }
        this.stopAiVoicePlayback();
        if (this.localTts != null) {
            try {
                this.localTts.stop();
                this.localTts.shutdown();
            }
            catch (Exception exception) {
                // empty catch block
            }
            this.localTts = null;
            this.localTtsReady = false;
        }
        if (this.guidanceReceiver != null) {
            this.unregisterReceiver(this.guidanceReceiver);
        }
        this.removeTaskCompleteDialog();
        this.removeViewSafely(this.overlayView);
        this.removeViewSafely(this.ballView);
        this.removeViewSafely(this.closeTargetView);
        if (this.highlightRenderer != null) {
            this.highlightRenderer.release();
        }
        this.removeViewSafely(this.chatView);
        this.removeViewSafely(this.micFloatView);
        this.removeViewSafely(this.micAiResponseView);
        this.removeViewSafely(this.micUserTranscriptView);
        super.onDestroy();
    }

    public IBinder onBind(Intent intent) {
        return null;
    }

    private static enum OverlayUiState {
        IDLE,
        LISTENING,
        ANALYZING,
        GUIDING,
        ERROR,
        COMPLETED;

    }
}








