package com.smarthelp.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AppPrefs {
    private static final String PREF_NAME = "smarthelp_prefs";
    public static final String HELP_REQUEST_TOKEN = "__NEED_HELP_FIND_TARGET__";

    public static final String KEY_LANGUAGE    = "language";
    public static final String KEY_LIVEKIT_URL  = "livekit_url";
    public static final String KEY_LIVEKIT_TOKEN = "livekit_token";
    public static final String KEY_TOKEN_ENDPOINT_URL = "token_endpoint_url";
    public static final String KEY_VOICE_SPEED = "voice_speed";
    public static final String KEY_TEXT_SIZE   = "text_size";
    private static final String KEY_PRIVACY_CONSENT   = "privacy_consent";
    private static final String KEY_PENDING_QUERY    = "pending_query";
    private static final String KEY_PENDING_QUERY_CREATED_AT = "pending_query_created_at";
    private static final String KEY_RECENT_TASKS     = "recent_tasks";
    private static final String KEY_ONBOARDING_SHOWN = "onboarding_shown";
    private static final String KEY_QUICK_ACTIONS    = "quick_actions";
    private static final String KEY_GUIDANCE_VOICE   = "guidance_voice";
    private static final String KEY_ACTION_SOUNDS    = "action_sounds";

    /** Separator between label and query within one entry. */
    private static final String INNER_SEP = ":::";

    public static final String DEFAULT_LANGUAGE    = "en";
    public static final int    DEFAULT_VOICE_SPEED = 2;
    public static final int    DEFAULT_TEXT_SIZE   = 1;

    private static final int    MAX_RECENT = 3;
    private static final long   PENDING_QUERY_TTL_MS = 5 * 60 * 1000L;
    private static final String SEP        = "|||";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ── Language ──────────────────────────────────────────────────────────────

    public static String getLanguage(Context ctx) {
        return DEFAULT_LANGUAGE;
    }

    public static void setLanguage(Context ctx, String lang) {
        prefs(ctx).edit().putString(KEY_LANGUAGE, DEFAULT_LANGUAGE).apply();
    }

    // ── LiveKit Cloud ────────────────────────────────────────────────────────

    public static final String DEFAULT_LIVEKIT_URL = "wss://smarthelp-3hkh37c7.livekit.cloud";
    public static final String DEFAULT_TOKEN_ENDPOINT_URL = "http://127.0.0.1:8765/token";

    public static String getLiveKitUrl(Context ctx) {
        String url = prefs(ctx).getString(KEY_LIVEKIT_URL, DEFAULT_LIVEKIT_URL);
        return (url == null || url.trim().isEmpty()) ? DEFAULT_LIVEKIT_URL : url.trim();
    }

    public static void setLiveKitUrl(Context ctx, String url) {
        prefs(ctx).edit().putString(KEY_LIVEKIT_URL, url == null ? "" : url.trim()).apply();
    }

    public static String getLiveKitToken(Context ctx) {
        return prefs(ctx).getString(KEY_LIVEKIT_TOKEN, "");
    }

    public static void setLiveKitToken(Context ctx, String token) {
        prefs(ctx).edit().putString(KEY_LIVEKIT_TOKEN, token == null ? "" : token.trim()).apply();
    }

    public static String getTokenEndpointUrl(Context ctx) {
        // Check adb-pushed config file first (set by start-all.bat)
        try {
            java.io.File configFile = new java.io.File(
                    ctx.getExternalFilesDir(null), "smarthelp_server.txt");
            if (configFile.exists()) {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.FileReader(configFile));
                String line = reader.readLine();
                reader.close();
                if (line != null && !line.trim().isEmpty()) {
                    return line.trim();
                }
            }
        } catch (Exception ignored) {}
        
        String defaultUrl = DEFAULT_TOKEN_ENDPOINT_URL;
        if (android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("Emulator")) {
            defaultUrl = "http://10.0.2.2:8765/token";
        }
        String url = prefs(ctx).getString(KEY_TOKEN_ENDPOINT_URL, defaultUrl);
        return (url == null || url.trim().isEmpty()) ? defaultUrl : url.trim();
    }

    public static void setTokenEndpointUrl(Context ctx, String url) {
        prefs(ctx).edit().putString(KEY_TOKEN_ENDPOINT_URL, url == null ? "" : url.trim()).apply();
    }

    // ── Voice / Display ───────────────────────────────────────────────────────

    public static int getVoiceSpeed(Context ctx) {
        return prefs(ctx).getInt(KEY_VOICE_SPEED, DEFAULT_VOICE_SPEED);
    }

    public static void setVoiceSpeed(Context ctx, int speed) {
        prefs(ctx).edit().putInt(KEY_VOICE_SPEED, speed).apply();
    }

    public static int getTextSize(Context ctx) {
        return prefs(ctx).getInt(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE);
    }

    public static void setTextSize(Context ctx, int size) {
        prefs(ctx).edit().putInt(KEY_TEXT_SIZE, size).apply();
    }

    public static float getTextScaleMultiplier(Context ctx) {
        switch (getTextSize(ctx)) {
            case 0: return 0.9f;
            case 1: return 1.0f;
            case 2: return 1.1f;
            case 3: return 1.2f;
            case 4: return 1.3f;
            default: return 1.0f;
        }
    }

    public static boolean hasPrivacyConsent(Context ctx) {
        return prefs(ctx).getBoolean(KEY_PRIVACY_CONSENT, false);
    }

    public static void setPrivacyConsent(Context ctx, boolean accepted) {
        prefs(ctx).edit().putBoolean(KEY_PRIVACY_CONSENT, accepted).apply();
    }

    // ── Guidance Voice & Sounds ───────────────────────────────────────────────

    public static boolean isGuidanceVoiceEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_GUIDANCE_VOICE, true);
    }

    public static void setGuidanceVoiceEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_GUIDANCE_VOICE, enabled).apply();
    }

    public static boolean isActionSoundsEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ACTION_SOUNDS, true);
    }

    public static void setActionSoundsEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_ACTION_SOUNDS, enabled).apply();
    }

    // ── Pending query (chip → service handoff) ────────────────────────────────

    /** Returns the pre-set query from a chip tap, or null if none. */
    public static String getPendingQuery(Context ctx) {
        SharedPreferences sharedPrefs = prefs(ctx);
        String query = sharedPrefs.getString(KEY_PENDING_QUERY, null);
        if (query == null) return null;

        long createdAt = sharedPrefs.getLong(KEY_PENDING_QUERY_CREATED_AT, 0L);
        if (createdAt <= 0L || System.currentTimeMillis() - createdAt > PENDING_QUERY_TTL_MS) {
            clearPendingQuery(ctx);
            return null;
        }
        return query;
    }

    public static void setPendingQuery(Context ctx, String query) {
        if (query == null || query.trim().isEmpty()) {
            clearPendingQuery(ctx);
            return;
        }
        prefs(ctx).edit()
                .putString(KEY_PENDING_QUERY, query)
                .putLong(KEY_PENDING_QUERY_CREATED_AT, System.currentTimeMillis())
                .apply();
    }

    public static void clearPendingQuery(Context ctx) {
        prefs(ctx).edit()
                .remove(KEY_PENDING_QUERY)
                .remove(KEY_PENDING_QUERY_CREATED_AT)
                .apply();
    }

    // ── Onboarding ────────────────────────────────────────────────────────────

    public static boolean isOnboardingShown(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ONBOARDING_SHOWN, false);
    }

    public static void setOnboardingShown(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_ONBOARDING_SHOWN, true).apply();
    }

    // ── Quick actions (overlay shortcut chips) ────────────────────────────────

    /**
     * Returns the list of quick actions as {label, query} pairs.
     * Defaults to a built-in starter set if none have been saved yet.
     */
    public static List<String[]> getQuickActions(Context ctx) {
        String raw = prefs(ctx).getString(KEY_QUICK_ACTIONS, null);
        if (raw == null || raw.isEmpty()) return getDefaultQuickActions(ctx);
        List<String[]> result = new ArrayList<>();
        for (String entry : raw.split("\\|\\|\\|")) {
            String[] parts = entry.split(":::", 2);
            if (parts.length == 2) result.add(new String[]{parts[0], parts[1]});
        }
        return result.isEmpty() ? getDefaultQuickActions(ctx) : result;
    }

    public static void saveQuickActions(Context ctx, List<String[]> actions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) sb.append(SEP);
            sb.append(actions.get(i)[0]).append(INNER_SEP).append(actions.get(i)[1]);
        }
        prefs(ctx).edit().putString(KEY_QUICK_ACTIONS, sb.toString()).apply();
    }

    public static void resetQuickActions(Context ctx) {
        prefs(ctx).edit().remove(KEY_QUICK_ACTIONS).apply();
    }

    private static List<String[]> getDefaultQuickActions(Context ctx) {
        // SmartHelp+ is English-only end-to-end. Chinese default chips removed.
        List<String[]> defaults = new ArrayList<>();
        defaults.add(new String[]{"Make a call", "Use the phone dialer to call my son, not WhatsApp"});
        defaults.add(new String[]{"Send WhatsApp", "Send a WhatsApp message to my daughter"});
        defaults.add(new String[]{"Open Camera", "Open the camera"});
        defaults.add(new String[]{"Check scam", "Help me check whether this message, link, or QR code is a scam"});
        return defaults;
    }

    // ── Recent tasks ──────────────────────────────────────────────────────────

    /** Returns up to 3 most-recent task queries, newest first. */
    public static List<String> getRecentTasks(Context ctx) {
        String raw = prefs(ctx).getString(KEY_RECENT_TASKS, "");
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(raw.split("\\|\\|\\|")));
    }

    /** Adds a task to the front of the history, deduplicating and capping at MAX_RECENT. */
    public static void addRecentTask(Context ctx, String task) {
        if (task == null || task.trim().isEmpty()) return;
        if (PrivacySafety.isSensitiveQuery(task)) return;
        List<String> tasks = getRecentTasks(ctx);
        tasks.remove(task);
        tasks.add(0, task);
        while (tasks.size() > MAX_RECENT) tasks.remove(tasks.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tasks.size(); i++) {
            if (i > 0) sb.append(SEP);
            sb.append(tasks.get(i));
        }
        prefs(ctx).edit().putString(KEY_RECENT_TASKS, sb.toString()).apply();
    }

    public static void clearRecentTasks(Context ctx) {
        prefs(ctx).edit().remove(KEY_RECENT_TASKS).apply();
    }
}
