package com.smarthelp.app.network;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.smarthelp.app.AppPrefs;
import com.smarthelp.app.ScreenCaptureService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.livekit.android.LiveKit;
import io.livekit.android.LiveKitOverrides;
import io.livekit.android.ConnectOptions;
import io.livekit.android.RoomOptions;
import io.livekit.android.events.EventListenableKt;
import io.livekit.android.room.Room;
import io.livekit.android.room.participant.LocalParticipant;
import io.livekit.android.room.track.DataPublishReliability;
import io.livekit.android.events.RoomEvent;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.CoroutineStart;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * ServerConnection — Rewritten for LiveKit WebRTC.
 * <p>
 * Replaces the old WebSocket-based GeminiLiveClient with LiveKit's Room API.
 * Communication with the Python agent happens via LiveKit Data Channel (reliable JSON messages).
 * Audio and video are handled as LiveKit Tracks.
 * <p>
 * The external interface (connect/disconnect/sendText/sendScreenshot etc.) is preserved
 * so that OverlayService requires minimal changes.
 */
public final class ServerConnection {

    private static final String TAG = "ServerConnection";
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long BASE_RECONNECT_DELAY_MS = 2000;
    private static final long MAX_RECONNECT_DELAY_MS = 30000;
    private static final int MAX_CHUNK_SIZE = 14 * 1024; // 14KB per chunk
    // Keep mic on for this long after stopMicrophone() so trailing speech is captured.
    private static final long AUDIO_END_TRAILING_CAPTURE_MS = 400;
    // After the mic is actually closed, wait this long so in-flight WebRTC frames flush before audio_end.
    private static final long AUDIO_END_FLUSH_MS = 500;
    private static final String EMULATOR_TOKEN_ENDPOINT = "http://10.0.2.2:8765/token";
    private static final String PC_WIFI_ENDPOINT = "http://192.168.0.154:8765/token";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient TOKEN_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build();

    public interface Listener {
        void onServerMessage(@NonNull MessageProtocol.ServerMessage message);
    }

    private final Context context;
    private final Listener listener;
    private Room room;
    private volatile boolean connected = false;
    private final CoroutineScope coroutineScope = CoroutineScopeKt.CoroutineScope(Dispatchers.getIO());
    private Job eventsJob;
    private Call tokenCall;
    private Call speechCall;
    private volatile boolean connectInProgress = false;
    private final Map<String, IncomingAudioChunks> pendingAudioChunks = new HashMap<>();

    // LiveKit credentials — loaded from AppPrefs
    private String livekitUrl;
    private String livekitToken;

    // Reconnect state
    private int reconnectAttempt = 0;
    private boolean intentionalDisconnect = false;
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private final Runnable reconnectRunnable = this::connect;
    private final Handler microphoneHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingAudioEndRunnable;
    private boolean microphoneActive = false;
    private int microphoneTurnId = 0;

    /**
     * Pre-warm: fetch the LiveKit token in the background so OverlayService connects faster.
     * Call from MainActivity.onCreate() or similar early entry point.
     */
    public static void preWarm(Context context) {
        new Thread(() -> {
            try {
                String endpoint = normalizeTokenEndpointStatic(AppPrefs.getTokenEndpointUrl(context));
                Log.d(TAG, "Pre-warming token from " + endpoint);
                Request request = new Request.Builder().url(endpoint).get().build();
                try (Response response = TOKEN_HTTP_CLIENT.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        LiveKitCredentials creds = parseTokenResponseStatic(body);
                        if (creds.url != null && creds.token != null) {
                            AppPrefs.setLiveKitUrl(context, creds.url);
                            AppPrefs.setLiveKitToken(context, creds.token);
                            Log.d(TAG, "Pre-warm: token cached successfully");
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Pre-warm failed (non-critical): " + e.getMessage());
            }
        }, "smarthelp-prewarm").start();
    }

    private static String normalizeTokenEndpointStatic(String endpoint) {
        if (endpoint == null) return AppPrefs.DEFAULT_TOKEN_ENDPOINT_URL;
        String trimmed = endpoint.trim();
        if (trimmed.isEmpty()) return AppPrefs.DEFAULT_TOKEN_ENDPOINT_URL;
        if (trimmed.contains("?") || trimmed.endsWith("/token")) return trimmed;
        return trimmed.endsWith("/") ? trimmed + "token" : trimmed + "/token";
    }

    private static LiveKitCredentials parseTokenResponseStatic(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String url = json.has("url") && !json.get("url").isJsonNull() ? json.get("url").getAsString() : "";
            String token = json.has("token") && !json.get("token").isJsonNull() ? json.get("token").getAsString() : "";
            return new LiveKitCredentials(url, token);
        } catch (Exception e) {
            return new LiveKitCredentials("", "");
        }
    }

    public ServerConnection(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /**
     * Connect to LiveKit Cloud room.
     * The Python agent will already be connected as a participant.
     */
    public void connect() {
        intentionalDisconnect = false;
        if (connected && room != null) {
            Log.w(TAG, "Already connected");
            return;
        }
        if (connectInProgress) {
            Log.d(TAG, "Connection already in progress");
            return;
        }

        connectInProgress = true;
        reconnectHandler.removeCallbacks(reconnectRunnable);
        dispatch(MessageProtocol.ServerMessage.connecting());
        requestLiveKitToken(buildTokenEndpointCandidates(), 0);
    }

    private void connectWithCredentials(String url, String token) {
        livekitUrl = url;
        livekitToken = token;
        Log.d(TAG, "Connecting to LiveKit: " + livekitUrl);

        try {
            LiveKit.INSTANCE.init(context);
            room = LiveKit.INSTANCE.create(context, new RoomOptions(), new LiveKitOverrides());

            // Listen for data channel messages from the Python agent
            collectRoomEvents(room);

            // Connect to the room
            launchSuspend("connect", (scope, continuation) -> {
                Object result = room.connect(livekitUrl, livekitToken, new ConnectOptions(), (Continuation) continuation);
                return toUnitOrSuspended(result);
            });

        } catch (Exception e) {
            connectInProgress = false;
            Log.e(TAG, "Error connecting to LiveKit", e);
            dispatch(MessageProtocol.ServerMessage.error("Connection error: " + e.getMessage()));
        }
    }

    private void requestLiveKitToken(List<String> endpoints, int index) {
        if (intentionalDisconnect) return;
        if (index >= endpoints.size()) {
            connectWithSavedTokenOrFail();
            return;
        }

        String endpoint = endpoints.get(index);
        Log.d(TAG, "Requesting LiveKit token from " + endpoint);
        Request request = new Request.Builder().url(endpoint).get().build();
        Call call = TOKEN_HTTP_CLIENT.newCall(request);
        tokenCall = call;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (call.isCanceled() || intentionalDisconnect) return;
                Log.w(TAG, "Token request failed for " + endpoint + ": " + e.getMessage());
                requestLiveKitToken(endpoints, index + 1);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (intentionalDisconnect) {
                    response.close();
                    return;
                }

                try (Response autoClose = response) {
                    String body = autoClose.body() == null ? "" : autoClose.body().string();
                    if (!autoClose.isSuccessful()) {
                        Log.w(TAG, "Token endpoint returned HTTP " + autoClose.code() + " from " + endpoint);
                        requestLiveKitToken(endpoints, index + 1);
                        return;
                    }

                    LiveKitCredentials credentials = parseTokenResponse(body);
                    AppPrefs.setLiveKitUrl(context, credentials.url);
                    AppPrefs.setLiveKitToken(context, credentials.token);
                    reconnectHandler.post(() -> connectWithCredentials(credentials.url, credentials.token));
                } catch (Exception e) {
                    Log.w(TAG, "Invalid token response from " + endpoint + ": " + e.getMessage());
                    requestLiveKitToken(endpoints, index + 1);
                }
            }
        });
    }

    private List<String> buildTokenEndpointCandidates() {
        Set<String> endpoints = new LinkedHashSet<>();
        endpoints.add(normalizeTokenEndpoint(AppPrefs.getTokenEndpointUrl(context)));
        endpoints.add(EMULATOR_TOKEN_ENDPOINT);
        endpoints.add(PC_WIFI_ENDPOINT);
        return new ArrayList<>(endpoints);
    }

    private String normalizeTokenEndpoint(String endpoint) {
        if (endpoint == null) return AppPrefs.DEFAULT_TOKEN_ENDPOINT_URL;
        String trimmed = endpoint.trim();
        if (trimmed.isEmpty()) return AppPrefs.DEFAULT_TOKEN_ENDPOINT_URL;
        if (trimmed.contains("?") || trimmed.endsWith("/token")) return trimmed;
        return trimmed.endsWith("/") ? trimmed + "token" : trimmed + "/token";
    }

    private List<String> buildSpeechEndpointCandidates() {
        List<String> tokenEndpoints = buildTokenEndpointCandidates();
        List<String> speechEndpoints = new ArrayList<>();
        for (String endpoint : tokenEndpoints) {
            speechEndpoints.add(toSpeechEndpoint(endpoint));
        }
        return speechEndpoints;
    }

    private String toSpeechEndpoint(String tokenEndpoint) {
        String endpoint = normalizeTokenEndpoint(tokenEndpoint);
        if (endpoint.endsWith("/token")) {
            return endpoint.substring(0, endpoint.length() - "/token".length()) + "/tts";
        }
        int queryIndex = endpoint.indexOf('?');
        String withoutQuery = queryIndex >= 0 ? endpoint.substring(0, queryIndex) : endpoint;
        return withoutQuery.endsWith("/") ? withoutQuery + "tts" : withoutQuery + "/tts";
    }

    private void requestSpeech(List<String> endpoints, int index, String text) {
        if (index >= endpoints.size()) {
            dispatch(MessageProtocol.ServerMessage.text(text, true, false));
            return;
        }

        String endpoint = endpoints.get(index);
        JsonObject json = new JsonObject();
        json.addProperty("text", text);
        String language = AppPrefs.getLanguage(context);
        if (language != null && !language.isEmpty()) {
            json.addProperty("language", language);
        }
        RequestBody requestBody = RequestBody.create(JSON_MEDIA_TYPE, json.toString());
        Request request = new Request.Builder().url(endpoint).post(requestBody).build();
        Call call = TOKEN_HTTP_CLIENT.newCall(request);
        speechCall = call;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (call.isCanceled()) return;
                Log.w(TAG, "Speech request failed for " + endpoint + ": " + e.getMessage());
                requestSpeech(endpoints, index + 1, text);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response autoClose = response) {
                    String body = autoClose.body() == null ? "" : autoClose.body().string();
                    if (!autoClose.isSuccessful()) {
                        Log.w(TAG, "Speech endpoint returned HTTP " + autoClose.code() + " from " + endpoint);
                        requestSpeech(endpoints, index + 1, text);
                        return;
                    }

                    JsonObject payload = JsonParser.parseString(body).getAsJsonObject();
                    String audio = getString(payload, "data", "");
                    if (audio.isEmpty()) {
                        requestSpeech(endpoints, index + 1, text);
                        return;
                    }
                    dispatch(MessageProtocol.ServerMessage.audio(audio, getString(payload, "text", text)));
                } catch (Exception e) {
                    Log.w(TAG, "Invalid speech response from " + endpoint + ": " + e.getMessage());
                    requestSpeech(endpoints, index + 1, text);
                }
            }
        });
    }

    private LiveKitCredentials parseTokenResponse(String body) throws IOException {
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        String token = getRequiredString(json, "token");
        String url = getString(json, "url", AppPrefs.getLiveKitUrl(context));
        if (url.isEmpty()) {
            throw new IOException("Missing LiveKit URL");
        }
        return new LiveKitCredentials(url, token);
    }

    private String getRequiredString(JsonObject json, String key) throws IOException {
        String value = getString(json, key, "");
        if (value.isEmpty()) {
            throw new IOException("Missing " + key);
        }
        return value;
    }

    private String getString(JsonObject json, String key, String fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        String value = json.get(key).getAsString();
        return value == null ? fallback : value.trim();
    }

    private void connectWithSavedTokenOrFail() {
        livekitUrl = AppPrefs.getLiveKitUrl(context);
        livekitToken = AppPrefs.getLiveKitToken(context);

        if (livekitUrl != null && !livekitUrl.isEmpty() && livekitToken != null && !livekitToken.isEmpty()) {
            Log.w(TAG, "Token server unavailable; falling back to saved LiveKit token");
            reconnectHandler.post(() -> connectWithCredentials(livekitUrl, livekitToken));
            return;
        }

        connectInProgress = false;
        String endpoint = AppPrefs.getTokenEndpointUrl(context);
        Log.e(TAG, "Unable to fetch LiveKit token from " + endpoint);
        dispatch(MessageProtocol.ServerMessage.error("Unable to get LiveKit token. Start token_server.py and check the token endpoint."));
        scheduleReconnect();
    }

    public void disconnect() {
        intentionalDisconnect = true;
        connected = false;
        connectInProgress = false;
        reconnectHandler.removeCallbacks(reconnectRunnable);
        microphoneHandler.removeCallbacksAndMessages(null);
        pendingAudioEndRunnable = null;
        microphoneActive = false;
        if (tokenCall != null) {
            tokenCall.cancel();
            tokenCall = null;
        }
        if (speechCall != null) {
            speechCall.cancel();
            speechCall = null;
        }
        if (eventsJob != null) {
            eventsJob.cancel(null);
            eventsJob = null;
        }
        if (room != null) {
            try {
                room.disconnect();
                room.release();
            } catch (Exception e) {
                Log.e(TAG, "Error disconnecting", e);
            }
            room = null;
        }
        pendingAudioChunks.clear();
    }

    public boolean isConnected() {
        return connected && room != null;
    }

    /**
     * Request natural speech from the local token server TTS endpoint.
     * Falls back to Android local TTS through a speak-only text message if unavailable.
     */
    public void requestSpeech(String text) {
        String message = text == null ? "" : text.trim();
        if (message.isEmpty()) return;
        requestSpeech(buildSpeechEndpointCandidates(), 0, message);
    }

    /**
     * Send a text message to the Python agent via Data Channel.
     */
    public void sendText(String text) {
        if (!isConnected()) return;
        JsonObject json = new JsonObject();
        json.addProperty("type", "text");
        json.addProperty("text", text);
        String language = com.smarthelp.app.AppPrefs.getLanguage(context);
        if (language != null && !language.isEmpty()) {
            json.addProperty("language", language);
        }
        publishData(json.toString());
    }

    /**
     * Notify the Python agent that Android detected a banking, payment, or auth screen.
     */
    public void sendSensitiveScreen(String packageName) {
        if (!isConnected()) return;
        JsonObject json = new JsonObject();
        json.addProperty("type", "sensitive_screen");
        json.addProperty("detected", true);
        if (packageName != null && !packageName.isEmpty()) {
            json.addProperty("app", packageName);
        }
        publishData(json.toString());
    }

    /**
     * Send a screenshot to the Python agent via Data Channel.
     * Large images are chunked to stay within LiveKit's data channel limits.
     */
    public void sendScreenshot(String base64Image, String userQuery, boolean verify) {
        sendScreenshot(base64Image, userQuery, verify, 0, 0);
    }

    /**
     * Send a screenshot to the Python agent with the captured image dimensions.
     */
    public void sendScreenshot(String base64Image, String userQuery, boolean verify, int width, int height) {
        if (!isConnected()) return;

        String language = com.smarthelp.app.AppPrefs.getLanguage(context);

        // Build the metadata portion to estimate its size
        JsonObject meta = new JsonObject();
        meta.addProperty("type", "image");
        if (language != null && !language.isEmpty()) {
            meta.addProperty("language", language);
        }
        if (userQuery != null && !userQuery.isEmpty()) {
            meta.addProperty("query", userQuery);
        }
        if (verify) {
            meta.addProperty("verify", true);
        }
        if (width > 0 && height > 0) {
            meta.addProperty("width", width);
            meta.addProperty("height", height);
            JsonArray accessibility = ScreenCaptureService.latestAccessibilitySnapshot;
            if (accessibility.size() > 0) {
                meta.add("accessibility", accessibility);
            }
        }

        // Estimate total payload size with the image data included
        JsonObject full = meta.deepCopy();
        full.addProperty("data", base64Image);
        int totalSize = full.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

        if (totalSize <= MAX_CHUNK_SIZE) {
            // Small enough — send as a single message
            publishData(full.toString());
        } else {
            // Chunk the base64 image data
            String chunkId = UUID.randomUUID().toString().substring(0, 8);
            int chunkSize = MAX_CHUNK_SIZE - 200; // Reserve space for chunk metadata
            int totalChunks = (base64Image.length() + chunkSize - 1) / chunkSize;

            for (int seq = 0; seq < totalChunks; seq++) {
                int start = seq * chunkSize;
                int end = Math.min(start + chunkSize, base64Image.length());
                String chunk = base64Image.substring(start, end);

                JsonObject chunkJson = new JsonObject();
                chunkJson.addProperty("type", "image_chunk");
                chunkJson.addProperty("id", chunkId);
                chunkJson.addProperty("seq", seq);
                chunkJson.addProperty("total", totalChunks);
                chunkJson.addProperty("data", chunk);
                publishData(chunkJson.toString());
            }

            // Send end marker with metadata
            JsonObject endJson = new JsonObject();
            endJson.addProperty("type", "image_end");
            endJson.addProperty("id", chunkId);
            endJson.addProperty("total", totalChunks);
            if (language != null && !language.isEmpty()) {
                endJson.addProperty("language", language);
            }
            if (userQuery != null && !userQuery.isEmpty()) {
                endJson.addProperty("query", userQuery);
            }
            if (verify) {
                endJson.addProperty("verify", true);
            }
            if (width > 0 && height > 0) {
                endJson.addProperty("width", width);
                endJson.addProperty("height", height);
                JsonArray accessibility = ScreenCaptureService.latestAccessibilitySnapshot;
                if (accessibility.size() > 0) {
                    endJson.add("accessibility", accessibility);
                }
            }
            publishData(endJson.toString());
        }
    }

    /**
     * Start microphone — in LiveKit architecture, audio is published as a Track.
     * For now, we use the Data Channel approach to match the existing protocol.
     * The Python agent handles audio via its own Gemini integration.
     */
    public void startMicrophone() {
        if (room != null && room.getLocalParticipant() != null) {
            if (pendingAudioEndRunnable != null) {
                microphoneHandler.removeCallbacks(pendingAudioEndRunnable);
                pendingAudioEndRunnable = null;
            }
            microphoneActive = true;
            microphoneTurnId++;
            // Signal start of listening session
            sendListeningStart(microphoneTurnId);
            setMicrophoneEnabled(true);
        }
    }

    public void stopMicrophone() {
        if (room != null && room.getLocalParticipant() != null) {
            if (!microphoneActive) {
                return;
            }
            microphoneActive = false;
            if (pendingAudioEndRunnable != null) {
                microphoneHandler.removeCallbacks(pendingAudioEndRunnable);
            }
            final int endedTurnId = microphoneTurnId;
            // Stage 1: keep mic open briefly so any trailing speech (after silence timer
            // or after user releases) still gets captured. Then close the mic.
            pendingAudioEndRunnable = () -> {
                setMicrophoneEnabled(false);
                // Stage 2: mic is closed; wait for in-flight WebRTC frames to arrive
                // server-side before signaling audio_end (which triggers STT).
                Runnable sendEnd = () -> {
                    sendAudioEnd(endedTurnId);
                    pendingAudioEndRunnable = null;
                };
                pendingAudioEndRunnable = sendEnd;
                microphoneHandler.postDelayed(sendEnd, AUDIO_END_FLUSH_MS);
            };
            microphoneHandler.postDelayed(pendingAudioEndRunnable, AUDIO_END_TRAILING_CAPTURE_MS);
        }
    }

    /**
     * Signal start of listening session.3
     */
    public void sendListeningStart() {
        sendListeningStart(microphoneTurnId);
    }

    private void sendListeningStart(int turnId) {
        if (!isConnected()) return;
        JsonObject json = new JsonObject();
        json.addProperty("type", "listening_start");
        json.addProperty("turnId", turnId);
        publishData(json.toString());
    }

    /**
     * Send audio_end signal to trigger server-side STT processing.
     */
    public void sendAudioEnd() {
        sendAudioEnd(microphoneTurnId);
    }

    private void sendAudioEnd(int turnId) {
        if (!isConnected()) return;
        JsonObject json = new JsonObject();
        json.addProperty("type", "audio_end");
        json.addProperty("turnId", turnId);
        publishData(json.toString());
    }

    /**
     * Replay is not directly supported in LiveKit architecture.
     * Returns false to indicate replay is not available.
     */
    public boolean replayLastAssistantAudio() {
        // Audio replay would require storing audio data locally.
        // In the LiveKit model, audio is streamed and not easily replayable.
        return false;
    }

    // ── Reconnect ────────────────────────────────────────────────────────

    private void scheduleReconnect() {
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts reached");
            dispatch(MessageProtocol.ServerMessage.error("Unable to reconnect. Please check your connection."));
            return;
        }
        long delay = Math.min(BASE_RECONNECT_DELAY_MS * (1L << reconnectAttempt), MAX_RECONNECT_DELAY_MS);
        reconnectAttempt++;
        Log.d(TAG, "Reconnecting in " + delay + "ms (attempt " + reconnectAttempt + ")");
        reconnectHandler.postDelayed(reconnectRunnable, delay);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Handle incoming data from the Python agent via Data Channel.
     */
    private void handleDataReceived(RoomEvent.DataReceived event) {
        try {
            String jsonStr = new String(event.getData(), java.nio.charset.StandardCharsets.UTF_8);
            Log.d(TAG, "Data received: " + jsonStr.substring(0, Math.min(jsonStr.length(), 200)));
            JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();
            String type = getString(json, "type", "");
            if ("audio_chunk".equals(type) || "audio_end".equals(type)) {
                handleIncomingAudioChunk(type, json);
                return;
            }
            MessageProtocol.ServerMessage message = MessageProtocol.decodeServerMessage(jsonStr);
            dispatch(message);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing data message", e);
        }
    }

    private void handleIncomingAudioChunk(String type, JsonObject json) {
        String id = getString(json, "id", "");
        int total = getInt(json, "total", 0);
        if (id.isEmpty() || total <= 0) return;

        cleanupPendingAudioChunks();

        if ("audio_chunk".equals(type)) {
            int seq = getInt(json, "seq", -1);
            if (seq < 0 || seq >= total) return;
            IncomingAudioChunks chunks = pendingAudioChunks.get(id);
            if (chunks == null || chunks.total != total) {
                chunks = new IncomingAudioChunks(total);
                pendingAudioChunks.put(id, chunks);
            }
            chunks.parts[seq] = getString(json, "data", "");
            return;
        }

        IncomingAudioChunks chunks = pendingAudioChunks.remove(id);
        if (chunks == null || !chunks.isComplete()) {
            Log.w(TAG, "Incomplete audio chunks for id " + id);
            return;
        }
        StringBuilder joined = new StringBuilder();
        for (String part : chunks.parts) {
            joined.append(part);
        }
        dispatch(MessageProtocol.ServerMessage.audio(joined.toString(), getString(json, "text", "")));
    }

    private int getInt(JsonObject json, String key, int fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }

    private void cleanupPendingAudioChunks() {
        long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(2);
        pendingAudioChunks.entrySet().removeIf(entry -> entry.getValue().createdAtMs < cutoff);
    }

    /**
     * Publish a JSON string to all participants in the room via reliable Data Channel.
     */
    private void publishData(String jsonString) {
        if (room == null || room.getLocalParticipant() == null) {
            Log.w(TAG, "Cannot publish data: not connected");
            return;
        }
        Log.d(TAG, "publishData: " + jsonString.substring(0, Math.min(jsonString.length(), 200)));
        LocalParticipant participant = room.getLocalParticipant();
        byte[] data = jsonString.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        launchSuspend("publish data", (scope, continuation) -> {
            Object result = invokePublishData(participant, data, continuation);
            return toUnitOrSuspended(result);
        });
    }

    private void dispatch(MessageProtocol.ServerMessage message) {
        if (listener != null) {
            listener.onServerMessage(message);
        }
    }

    private void collectRoomEvents(Room room) {
        if (eventsJob != null) {
            eventsJob.cancel(null);
        }
        eventsJob = BuildersKt.launch(
                coroutineScope,
                EmptyCoroutineContext.INSTANCE,
                CoroutineStart.DEFAULT,
                (scope, continuation) -> EventListenableKt.collect(
                        room.getEvents(),
                        (event, eventContinuation) -> {
                            handleRoomEvent((RoomEvent) event);
                            return Unit.INSTANCE;
                        },
                        continuation
                )
        );
    }

    private void handleRoomEvent(RoomEvent event) {
        Log.d(TAG, "RoomEvent: " + event.getClass().getSimpleName());
        if (event instanceof RoomEvent.DataReceived) {
            handleDataReceived((RoomEvent.DataReceived) event);
        } else if (event instanceof RoomEvent.Connected) {
            Log.d(TAG, "Connected to LiveKit room");
            connectInProgress = false;
            connected = true;
            reconnectAttempt = 0;
            dispatch(MessageProtocol.ServerMessage.ready());
            Log.d(TAG, "Dispatched READY to listener");
        } else if (event instanceof RoomEvent.Disconnected) {
            Log.d(TAG, "Disconnected from LiveKit room");
            connectInProgress = false;
            connected = false;
            dispatch(MessageProtocol.ServerMessage.disconnected());
            if (!intentionalDisconnect) {
                scheduleReconnect();
            }
        } else if (event instanceof RoomEvent.FailedToConnect) {
            Log.e(TAG, "Failed to connect to LiveKit");
            connectInProgress = false;
            connected = false;
            dispatch(MessageProtocol.ServerMessage.error("Failed to connect to server"));
            scheduleReconnect();
        }
    }

    private void setMicrophoneEnabled(boolean enabled) {
        LocalParticipant participant = room.getLocalParticipant();
        launchSuspend(enabled ? "enable microphone" : "disable microphone", (scope, continuation) -> {
            Object result = participant.setMicrophoneEnabled(enabled, (Continuation) continuation);
            return toUnitOrSuspended(result);
        });
    }

    private static final class LiveKitCredentials {
        final String url;
        final String token;

        LiveKitCredentials(String url, String token) {
            this.url = url;
            this.token = token;
        }
    }

    private static final class IncomingAudioChunks {
        final int total;
        final String[] parts;
        final long createdAtMs;

        IncomingAudioChunks(int total) {
            this.total = total;
            this.parts = new String[total];
            this.createdAtMs = System.currentTimeMillis();
        }

        boolean isComplete() {
            for (String part : parts) {
                if (part == null) return false;
            }
            return true;
        }
    }

    private interface SuspendBlock {
        Object run(CoroutineScope scope, Continuation<? super Unit> continuation) throws Exception;
    }

    private void launchSuspend(String operation, SuspendBlock block) {
        BuildersKt.launch(
                coroutineScope,
                EmptyCoroutineContext.INSTANCE,
                CoroutineStart.DEFAULT,
                (scope, continuation) -> {
                    try {
                        return block.run(scope, continuation);
                    } catch (Exception e) {
                        if ("connect".equals(operation)) {
                            connectInProgress = false;
                        }
                        Log.e(TAG, "LiveKit " + operation + " failed", e);
                        dispatch(MessageProtocol.ServerMessage.error("LiveKit " + operation + " failed"));
                        return Unit.INSTANCE;
                    }
                }
        );
    }

    private Object invokePublishData(LocalParticipant participant, byte[] data, Continuation<? super Unit> continuation)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method publishData = LocalParticipant.class.getMethod(
                "publishData-yxL6bBk",
                byte[].class,
                DataPublishReliability.class,
                String.class,
                java.util.List.class,
                Continuation.class
        );
        return publishData.invoke(
                participant,
                data,
                DataPublishReliability.RELIABLE,
                null,
                Collections.emptyList(),
                continuation
        );
    }

    private Object toUnitOrSuspended(Object result) {
        return result == IntrinsicsKt.getCOROUTINE_SUSPENDED() ? result : Unit.INSTANCE;
    }
}
