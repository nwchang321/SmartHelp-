package com.smarthelp.app;

import android.speech.SpeechRecognizer;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Pure JVM unit tests for speech recognition state logic.
 * No device or emulator required — run with ./gradlew test
 *
 * These tests verify the state transitions described in OverlayService:
 *   idle → listening → (results / error) → idle
 */
public class SpeechStateTest {

    // ──────────────────────────────────────────────────────────────────────────
    // State machine constants (mirrors OverlayService values)
    // ──────────────────────────────────────────────────────────────────────────
    static final int ERROR_CLIENT          = SpeechRecognizer.ERROR_CLIENT;          // 5
    static final int ERROR_NO_MATCH        = SpeechRecognizer.ERROR_NO_MATCH;         // 7
    static final int ERROR_SPEECH_TIMEOUT  = SpeechRecognizer.ERROR_SPEECH_TIMEOUT;  // 6
    static final int ERROR_AUDIO           = SpeechRecognizer.ERROR_AUDIO;            // 3

    // ──────────────────────────────────────────────────────────────────────────
    // Minimal state model (mirrors the relevant fields of OverlayService)
    // ──────────────────────────────────────────────────────────────────────────
    static class SpeechState {
        boolean isListening       = false;
        boolean stopRequested     = false;
        String  latestTranscript  = null;
        String  lastStatus        = null;
        String  submittedQuery    = null;
        boolean autoRestartPending = false;

        /** Mirrors startListening(true) */
        void startListening() {
            if (isListening) return;
            isListening = true;
            latestTranscript = null;
        }

        /** Mirrors stopLocalSpeechRecognition() */
        void requestStop() {
            stopRequested = true;
            // In real code this calls speechRecognizer.stopListening()
            // which fires onError(ERROR_CLIENT) or onResults()
        }

        /** Mirrors stopListening() public method */
        void stopListening() {
            isListening = false;
            requestStop();
        }

        /** Mirrors onPartialResults */
        void onPartialResults(String partial) {
            if (partial == null || partial.isEmpty()) return;
            latestTranscript = partial;
            lastStatus = partial;
        }

        /** Mirrors onResults */
        void onResults(String result) {
            stopRequested = false;
            isListening = false;
            String finalResult = (result == null || result.isEmpty()) ? latestTranscript : result;
            if (finalResult != null && !finalResult.isEmpty()) {
                submittedQuery = finalResult;
                lastStatus = finalResult;
            }
        }

        /** Mirrors the refactored onError */
        void onError(int error) {
            String transcript = latestTranscript == null ? "" : latestTranscript.trim();
            boolean wasStopRequested = stopRequested;
            stopRequested = false;

            // Always reset
            isListening = false;

            if (wasStopRequested || error == ERROR_CLIENT) {
                if (!transcript.isEmpty()) {
                    submittedQuery = transcript;
                }
                return;
            }

            if (error == ERROR_NO_MATCH) {
                if (!transcript.isEmpty()) {
                    submittedQuery = transcript;
                } else {
                    lastStatus = "没听清，再说一次...";
                    autoRestartPending = true;
                }
                return;
            }

            if (error == ERROR_SPEECH_TIMEOUT) {
                lastStatus = "没听清，再说一次...";
                autoRestartPending = true;
                return;
            }

            // Other errors
            lastStatus = "语音不可用";
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    public void startListening_setsListeningTrue() {
        SpeechState state = new SpeechState();
        state.startListening();
        assertTrue(state.isListening);
    }

    @Test
    public void startListening_idempotent_whenAlreadyListening() {
        SpeechState state = new SpeechState();
        state.startListening();
        state.latestTranscript = "partial";
        state.startListening(); // second call should no-op
        assertEquals("partial", state.latestTranscript); // transcript not cleared again
    }

    @Test
    public void onResults_submitsTranscript_andResetsListening() {
        SpeechState state = new SpeechState();
        state.startListening();
        state.onResults("send message");
        assertFalse(state.isListening);
        assertEquals("send message", state.submittedQuery);
    }

    @Test
    public void onResults_fallsBackToPartial_whenResultEmpty() {
        SpeechState state = new SpeechState();
        state.startListening();
        state.onPartialResults("open settings");
        state.onResults(""); // STT returned empty — use partial
        assertEquals("open settings", state.submittedQuery);
    }

    @Test
    public void onError_CLIENT_withPartial_submitsPartial() {
        SpeechState state = new SpeechState();
        state.startListening();
        state.onPartialResults("call mom");
        state.requestStop();
        state.onError(ERROR_CLIENT);
        assertFalse(state.isListening);
        assertEquals("call mom", state.submittedQuery);
    }

    @Test
    public void onError_CLIENT_withEmptyPartial_doesNotSubmit() {
        SpeechState state = new SpeechState();
        state.startListening();
        // No partial results — user released mic immediately
        state.requestStop();
        state.onError(ERROR_CLIENT);
        assertFalse(state.isListening);
        assertNull("Nothing should be submitted for empty quick-release", state.submittedQuery);
        // Status should NOT say "语音不可用"
        assertNotEquals("语音不可用", state.lastStatus);
    }

    @Test
    public void onError_NO_MATCH_withPartial_submitsPartial() {
        SpeechState state = new SpeechState();
        state.startListening();
        state.onPartialResults("open camera");
        state.onError(ERROR_NO_MATCH);
        assertFalse(state.isListening);
        assertEquals("open camera", state.submittedQuery);
        assertFalse(state.autoRestartPending);
    }

    @Test
    public void onError_NO_MATCH_withNoPartial_schedulesAutoRestart() {
        SpeechState state = new SpeechState();
        state.startListening();
        state.onError(ERROR_NO_MATCH);
        assertFalse(state.isListening);
        assertNull(state.submittedQuery);
        assertTrue("Should auto-restart after no-match with no partial", state.autoRestartPending);
    }

    @Test
    public void onError_SPEECH_TIMEOUT_schedulesAutoRestart() {
        SpeechState state = new SpeechState();
        state.startListening();
        state.onError(ERROR_SPEECH_TIMEOUT);
        assertFalse(state.isListening);
        assertTrue(state.autoRestartPending);
    }

    @Test
    public void onError_AUDIO_showsVoiceUnavailable() {
        SpeechState state = new SpeechState();
        state.startListening();
        state.onError(ERROR_AUDIO);
        assertFalse(state.isListening);
        assertEquals("语音不可用", state.lastStatus);
        assertFalse(state.autoRestartPending);
    }

    @Test
    public void stopListening_resetsState_beforeSTTCallbacks() {
        SpeechState state = new SpeechState();
        state.startListening();
        state.onPartialResults("set alarm");
        state.stopListening();
        assertFalse("isListening must be false immediately after stopListening()", state.isListening);
        assertTrue("stopRequested must be true until STT callback clears it", state.stopRequested);
    }

    @Test
    public void fullHappyPath_tapToStart_speakQuery_resultsReceived() {
        SpeechState state = new SpeechState();

        // User taps mic
        state.startListening();
        assertTrue(state.isListening);

        // Partial result arrives
        state.onPartialResults("open wh");
        state.onPartialResults("open WhatsApp");

        // STT finalizes
        state.onResults("open WhatsApp");

        assertFalse(state.isListening);
        assertEquals("open WhatsApp", state.submittedQuery);
    }
}
