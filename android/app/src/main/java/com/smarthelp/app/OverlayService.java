package com.smarthelp.app;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Locale;

public class OverlayService extends Service implements TextToSpeech.OnInitListener {

    private static final String TAG = "SmartHelp.Overlay";

    private WindowManager windowManager;

    // Guidance panel overlay
    private View overlayView;
    private WindowManager.LayoutParams params;

    // Highlight overlay (arrow + box)
    private HighlightOverlayView highlightView;
    private WindowManager.LayoutParams highlightParams;

    // Views
    private TextView txtStepCounter;
    private TextView txtGuidance;
    private TextView txtUserQuery;
    private TextView txtListening;
    private TextView txtHint;
    private View layoutListening;
    private View btnRepeat;
    private View btnNext;
    private View btnStop;
    private View btnMicrophone;
    private View btnMinimize;
    private View btnKeyboardToggle;
    private View btnSendText;
    private VoiceWaveView voiceWaveView;
    private View cardGuidance;
    private View cardUserQuery;

    // Text Input Fallback
    private View layoutTextInput;
    private android.widget.EditText etQueryInput;

    // TTS Engine
    private TextToSpeech textToSpeech;
    private boolean isTtsReady = false;

    // Speech Recognition
    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;

    // Current guidance state
    private String[] currentSteps;
    private int currentStepIndex = 0;
    private String currentVoiceText;
    private String currentHighlightPosition;
    private String currentUserQuery;

    // Broadcast receiver for guidance updates
    private BroadcastReceiver guidanceReceiver;

    // For dragging the overlay
    private float initialX, initialY;
    private float initialTouchX, initialTouchY;
    private boolean isDragging = false;

    private Handler handler = new Handler(Looper.getMainLooper());

    // Minimized state
    private boolean isMinimized = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "OverlayService created");

        // Initialize TTS
        textToSpeech = new TextToSpeech(this, this);

        // Initialize Speech Recognizer
        try {
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
                speechRecognizer.setRecognitionListener(new SpeechRecognitionListener());
                Log.d(TAG, "Speech recognizer initialized");
            } else {
                Log.e(TAG, "Speech recognition not available on this device");
                speechRecognizer = null;
                // Will show text input when user taps microphone
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to init SpeechRecognizer", e);
            speechRecognizer = null;
        }

        // Initialize window manager
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Setup overlays
        createGuidanceOverlay();
        createHighlightOverlay();

        // Register broadcast receiver
        registerGuidanceReceiver();

        // Initial greeting
        handler.postDelayed(() -> {
            if (speechRecognizer != null) {
                speak("Hello! I am SmartHelp Plus. Tell me what you want to do.", "greeting");
            } else {
                // Voice not available - show text input and speak alternative message
                speak("Hello! I am SmartHelp Plus. Please type what you want to do.");
                handler.postDelayed(() -> {
                    if (layoutTextInput != null) {
                        layoutTextInput.setVisibility(View.VISIBLE);
                        params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                        windowManager.updateViewLayout(overlayView, params);
                    }
                    updateGuidanceText("Voice not available on this device.\nPlease type your question below.");
                }, 500);
            }
        }, 1000);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = textToSpeech.setLanguage(Locale.US);
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true;
                textToSpeech.setSpeechRate(0.85f);
                textToSpeech.setPitch(1.0f);
                Log.d(TAG, "TTS initialized successfully");

                // Set up utterance listener to detect when speaking finishes
                textToSpeech.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        // Speaking started
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        // If the initial greeting finished, start listening automatically
                        if ("greeting".equals(utteranceId)) {
                            handler.post(() -> startListening());
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        Log.e(TAG, "TTS Error on: " + utteranceId);
                    }
                });

            } else {
                Log.e(TAG, "TTS language not supported");
            }
        } else {
            Log.e(TAG, "TTS initialization failed");
        }
    }

    private void updateGuidanceText(String text) {
        handler.post(() -> {
            if (txtGuidance != null) {
                txtGuidance.setText(text);
            }
        });
    }

    private void createGuidanceOverlay() {
        // Use ContextThemeWrapper to provide a theme, preventing crash on attribute resolution
        ContextThemeWrapper contextThemeWrapper = new ContextThemeWrapper(this, R.style.Theme_SmartHelp);
        LayoutInflater inflater = (LayoutInflater) contextThemeWrapper.getSystemService(LAYOUT_INFLATER_SERVICE);
        overlayView = inflater.inflate(R.layout.overlay_guidance, null);

        // Initialize views
        txtStepCounter = overlayView.findViewById(R.id.txtStepCounter);
        txtGuidance = overlayView.findViewById(R.id.txtGuidance);
        txtUserQuery = overlayView.findViewById(R.id.txtUserQuery);
        txtListening = overlayView.findViewById(R.id.txtListening);
        txtHint = overlayView.findViewById(R.id.txtHint);
        layoutListening = overlayView.findViewById(R.id.layoutListening);
        btnRepeat = overlayView.findViewById(R.id.btnRepeat);
        btnNext = overlayView.findViewById(R.id.btnNext);
        btnStop = overlayView.findViewById(R.id.btnStop);
        btnMicrophone = overlayView.findViewById(R.id.btnMicrophone);
        btnMinimize = overlayView.findViewById(R.id.btnMinimize);
        btnKeyboardToggle = overlayView.findViewById(R.id.btnKeyboardToggle);
        voiceWaveView = overlayView.findViewById(R.id.voiceWaveView);
        cardGuidance = overlayView.findViewById(R.id.cardGuidance);
        cardUserQuery = overlayView.findViewById(R.id.cardUserQuery);

        // Text Input Views
        layoutTextInput = overlayView.findViewById(R.id.layoutTextInput);
        etQueryInput = overlayView.findViewById(R.id.etQueryInput);
        btnSendText = overlayView.findViewById(R.id.btnSendText);

        // Set up button listeners
        btnRepeat.setOnClickListener(v -> repeatCurrentStep());
        btnNext.setOnClickListener(v -> nextStep());
        btnStop.setOnClickListener(v -> stopService());
        btnMicrophone.setOnClickListener(v -> toggleSpeechRecognition());
        btnMinimize.setOnClickListener(v -> toggleMinimize());

        btnKeyboardToggle.setOnClickListener(v -> {
            if (etQueryInput.getVisibility() == View.VISIBLE) {
                // Switch back to voice mode
                etQueryInput.setVisibility(View.GONE);
                txtHint.setVisibility(View.VISIBLE);
                btnMicrophone.setVisibility(View.VISIBLE);
                btnSendText.setVisibility(View.GONE);
            } else {
                // Switch to text mode
                etQueryInput.setVisibility(View.VISIBLE);
                txtHint.setVisibility(View.GONE);
                btnMicrophone.setVisibility(View.GONE);
                btnSendText.setVisibility(View.VISIBLE);
                // Ensure overlay can accept focus for keyboard
                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                windowManager.updateViewLayout(overlayView, params);
                etQueryInput.requestFocus();
            }
        });

        btnSendText.setOnClickListener(v -> {
            String text = etQueryInput.getText().toString().trim();
            if (!text.isEmpty()) {
                handleUserQuery(text);
                etQueryInput.setText("");
                // Switch back to voice mode after sending
                etQueryInput.setVisibility(View.GONE);
                txtHint.setVisibility(View.VISIBLE);
                btnMicrophone.setVisibility(View.VISIBLE);
                btnSendText.setVisibility(View.GONE);
            }
        });

        // Set up overlay parameters
        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, // Removed FLAG_NOT_FOCUSABLE to allow keyboard
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = 50;

        setupDragListener();
        windowManager.addView(overlayView, params);
        overlayView.setVisibility(View.VISIBLE);
    }

    private void createHighlightOverlay() {
        highlightView = new HighlightOverlayView(this);

        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        highlightParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        windowManager.addView(highlightView, highlightParams);
        highlightView.setVisibility(View.GONE);

        // When user taps the highlight, hide it and analyze again after a delay
        highlightView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                hideHighlight();
                // Wait for user to complete their tap, then analyze again
                if (currentUserQuery != null && !currentUserQuery.isEmpty()) {
                    handler.postDelayed(() -> {
                        Log.d(TAG, "Auto-analyzing after user tap");
                        analyzeCurrentScreen();
                    }, 2000); // 2 second delay to let user complete the action
                }
                return true;
            }
            return false;
        });
    }

    private void setupDragListener() {
        overlayView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    isDragging = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float deltaX = event.getRawX() - initialTouchX;
                    float deltaY = event.getRawY() - initialTouchY;
                    if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                        isDragging = true;
                        params.x = (int) (initialX + deltaX);
                        params.y = (int) (initialY + deltaY);
                        windowManager.updateViewLayout(overlayView, params);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    return isDragging;
            }
            return false;
        });
    }

    private void toggleMinimize() {
        isMinimized = !isMinimized;

        if (isMinimized) {
            // Hide the guidance card but keep input bar
            if (cardGuidance != null) cardGuidance.setVisibility(View.GONE);
            if (cardUserQuery != null) cardUserQuery.setVisibility(View.GONE);
            hideHighlight();
        } else {
            // Show content
            if (cardGuidance != null) cardGuidance.setVisibility(View.VISIBLE);
        }
    }

    // ========== Speech Recognition ==========

    private void toggleSpeechRecognition() {
        if (isListening) {
            stopListening();
        } else {
            startListening();
        }
    }

    private void startListening() {
        if (speechRecognizer == null) {
            Log.e(TAG, "Speech recognition is not available.");
            // Auto-show keyboard input as fallback
            handler.post(() -> {
                layoutTextInput.setVisibility(View.VISIBLE);
                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                windowManager.updateViewLayout(overlayView, params);
                etQueryInput.requestFocus();
                updateGuidanceText("Voice not available. Please type your question below.");
            });
            return;
        }

        // Stop TTS if speaking
        if (textToSpeech != null) {
            textToSpeech.stop();
        }

        isListening = true;
        layoutListening.setVisibility(View.VISIBLE);
        txtHint.setVisibility(View.GONE);
        txtListening.setText("Listening...");

        // Show and activate wave animation
        if (voiceWaveView != null) {
            voiceWaveView.setVisibility(View.VISIBLE);
            voiceWaveView.startAnimation();
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

        try {
            speechRecognizer.startListening(intent);
            Log.d(TAG, "Started listening");
        } catch (Exception e) {
            Log.e(TAG, "Error starting speech recognition", e);
            stopListening();
        }
    }

    private void stopListening() {
        isListening = false;
        layoutListening.setVisibility(View.GONE);
        txtHint.setVisibility(View.VISIBLE);

        // Stop and hide wave animation
        if (voiceWaveView != null) {
            voiceWaveView.stopAnimation();
            voiceWaveView.setVisibility(View.GONE);
        }

        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
        }
    }

    private class SpeechRecognitionListener implements RecognitionListener {
        @Override
        public void onReadyForSpeech(Bundle params) {
            Log.d(TAG, "Ready for speech");
        }

        @Override
        public void onBeginningOfSpeech() {
            Log.d(TAG, "Beginning of speech");
            txtListening.setText("I hear you...");
        }

        @Override
        public void onRmsChanged(float rmsdB) {}

        @Override
        public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {
            Log.d(TAG, "End of speech");
            txtListening.setText("Processing...");
        }

        @Override
        public void onError(int error) {
            String errorMessage = getErrorMessage(error);
            Log.e(TAG, "Speech recognition error: " + errorMessage);
            stopListening();

            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                speak("Sorry, I didn't hear that. Please try again.");
            }
        }

        @Override
        public void onResults(Bundle results) {
            stopListening();

            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
                String userSaid = matches.get(0);
                Log.d(TAG, "User said: " + userSaid);
                handleUserQuery(userSaid);
            }
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
                txtListening.setText("\"" + matches.get(0) + "...\"");
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {}

        private String getErrorMessage(int error) {
            switch (error) {
                case SpeechRecognizer.ERROR_AUDIO: return "Audio error";
                case SpeechRecognizer.ERROR_CLIENT: return "Client error";
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Need microphone permission";
                case SpeechRecognizer.ERROR_NETWORK: return "Network error";
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "Network timeout";
                case SpeechRecognizer.ERROR_NO_MATCH: return "No speech detected";
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "Recognizer busy";
                case SpeechRecognizer.ERROR_SERVER: return "Server error";
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "No speech input";
                default: return "Unknown error";
            }
        }
    }

    private void handleUserQuery(String query) {
        currentUserQuery = query;

        // Show what user asked in the query card
        if (txtUserQuery != null) {
            txtUserQuery.setText(query);
        }
        if (cardUserQuery != null) {
            cardUserQuery.setVisibility(View.VISIBLE);
        }

        // Update guidance text
        if (txtGuidance != null) {
            txtGuidance.setText("Analyzing your screen...");
        }
        speak("Okay, let me help you with that.");

        // Send request to capture service to analyze with query
        Intent intent = new Intent("com.smarthelp.ANALYZE_REQUEST");
        intent.setPackage(getPackageName());
        intent.putExtra("userQuery", query);
        sendBroadcast(intent);

        Log.d(TAG, "Sent analyze request with query: " + query);
    }

    // ========== Broadcast Receiver ==========

    private void registerGuidanceReceiver() {
        guidanceReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();

                if ("com.smarthelp.GUIDANCE_UPDATE".equals(action)) {
                    String instruction = intent.getStringExtra("instruction");
                    int highlightX = intent.getIntExtra("highlightX", -1);
                    int highlightY = intent.getIntExtra("highlightY", -1);
                    boolean completed = intent.getBooleanExtra("completed", false);

                    Log.d(TAG, "=== GUIDANCE_UPDATE RECEIVED ===");
                    Log.d(TAG, "Instruction: " + instruction);
                    Log.d(TAG, "Highlight: x=" + highlightX + ", y=" + highlightY);
                    Log.d(TAG, "Completed: " + completed);

                    if (instruction != null) {
                        handler.post(() -> updateGuidance(instruction, highlightX, highlightY, completed));
                    } else {
                        Log.e(TAG, "Instruction is null!");
                    }
                } else if ("com.smarthelp.HIDE_OVERLAY".equals(action)) {
                    // Hide overlay for screenshot
                    Log.d(TAG, "Received HIDE_OVERLAY - hiding overlays");
                    handler.post(() -> {
                        if (overlayView != null) {
                            overlayView.setVisibility(View.INVISIBLE);
                            Log.d(TAG, "overlayView hidden");
                        }
                        if (highlightView != null) {
                            highlightView.setVisibility(View.INVISIBLE);
                            Log.d(TAG, "highlightView hidden");
                        }
                    });
                } else if ("com.smarthelp.SHOW_OVERLAY".equals(action)) {
                    // Show overlay after screenshot
                    Log.d(TAG, "Received SHOW_OVERLAY - showing overlays");
                    handler.post(() -> {
                        if (overlayView != null) {
                            overlayView.setVisibility(View.VISIBLE);
                            Log.d(TAG, "overlayView shown");
                        }
                        // highlightView visibility is managed by updateGuidance
                    });
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.smarthelp.GUIDANCE_UPDATE");
        filter.addAction("com.smarthelp.HIDE_OVERLAY");
        filter.addAction("com.smarthelp.SHOW_OVERLAY");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(guidanceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(guidanceReceiver, filter);
        }
    }

    private void updateGuidance(String instruction, int highlightX, int highlightY, boolean completed) {
        Log.d(TAG, "=== updateGuidance called ===");
        Log.d(TAG, "Setting instruction: " + instruction);
        Log.d(TAG, "Task completed: " + completed);

        currentVoiceText = instruction;

        // If task is completed, clear the query to stop auto-analysis
        if (completed) {
            Log.d(TAG, "Task completed! Stopping auto-analysis.");
            currentUserQuery = null;
        }

        // Ensure overlay is visible and not minimized
        if (isMinimized) {
            toggleMinimize();
        }
        if (overlayView != null) {
            overlayView.setVisibility(View.VISIBLE);
        }

        // Update the guidance text
        if (txtGuidance != null) {
            txtGuidance.setText(instruction != null ? instruction : "No response");
            Log.d(TAG, "txtGuidance updated");
        } else {
            Log.e(TAG, "txtGuidance is null!");
        }

        if (cardGuidance != null) {
            cardGuidance.setVisibility(View.VISIBLE);
        }

        // Show highlight at position (only if not completed)
        if (!completed && highlightX >= 0 && highlightY >= 0) {
            Log.d(TAG, "Showing highlight at: " + highlightX + ", " + highlightY);
            showHighlightPercent(highlightX, highlightY);
        } else {
            hideHighlight();
        }

        // Speak the instruction
        if (instruction != null && !instruction.isEmpty()) {
            speak(instruction);
        }
    }

    private void showHighlight(String position) {
        Log.d(TAG, "Showing highlight at: " + position);
        highlightView.setTargetPosition(position);
        highlightView.setVisibility(View.VISIBLE);
    }

    private void showHighlightPercent(int x, int y) {
        Log.d(TAG, "Showing highlight at percent: x=" + x + ", y=" + y);
        highlightView.setTargetPositionPercent(x, y);
        highlightView.setVisibility(View.VISIBLE);
    }

    private void hideHighlight() {
        highlightView.clearHighlight();
        highlightView.setVisibility(View.GONE);
    }

    // Directly analyze current screen without going through steps
    private void analyzeCurrentScreen() {
        txtGuidance.setText("Analyzing your screen...");
        speak("Let me see what's on your screen.");

        Intent intent = new Intent("com.smarthelp.ANALYZE_REQUEST");
        intent.setPackage(getPackageName());

        // Build query based on original user request
        String query;
        if (currentUserQuery != null && !currentUserQuery.isEmpty()) {
            // Remove any previous "I've done" suffix
            String baseQuery = currentUserQuery.replaceAll("\\s*\\(I've done.*\\)", "").trim();
            query = baseQuery + " (continue from current screen)";
        } else {
            query = "What should I do on this screen?";
        }

        intent.putExtra("userQuery", query);
        Log.d(TAG, "Analyzing current screen with query: " + query);
        sendBroadcast(intent);
    }

    private void displayCurrentStep() {
        // Simplified - just show the instruction
        if (currentVoiceText != null && txtGuidance != null) {
            txtGuidance.setText(currentVoiceText);
        }
    }

    private void speak(String text) {
        speak(text, "guidance");
    }

    private void speak(String text, String utteranceId) {
        if (isTtsReady && text != null && !text.isEmpty()) {
            textToSpeech.stop();
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        }
    }

    private void repeatCurrentStep() {
        // Repeat the current instruction
        if (currentVoiceText != null && !currentVoiceText.isEmpty()) {
            speak(currentVoiceText);
        }
    }

    private void nextStep() {
        // Analyze current screen for next instruction
        analyzeCurrentScreen();
    }

    private void stopService() {
        Intent stopIntent = new Intent("com.smarthelp.STOP_SERVICE");
        stopIntent.setPackage(getPackageName());
        sendBroadcast(stopIntent);
        stopSelf();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "OverlayService destroyed");

        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }

        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }

        if (guidanceReceiver != null) {
            unregisterReceiver(guidanceReceiver);
        }

        if (overlayView != null && windowManager != null) {
            windowManager.removeView(overlayView);
        }
        if (highlightView != null && windowManager != null) {
            windowManager.removeView(highlightView);
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
