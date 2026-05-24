package com.smarthelp.app.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MessageProtocol {

    private MessageProtocol() {}

    public static String createAudioMessage(String base64Data) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "audio");
        json.addProperty("data", base64Data);
        return json.toString();
    }

    public static String createScreenshotMessage(String base64Image, String userQuery, boolean verify) {
        return createScreenshotMessage(base64Image, userQuery, verify, null);
    }

    public static String createScreenshotMessage(String base64Image, String userQuery, boolean verify, String language) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "image");
        json.addProperty("data", base64Image);
        if (language != null && !language.isEmpty()) {
            json.addProperty("language", language);
        }
        if (userQuery != null && !userQuery.isEmpty()) {
            json.addProperty("query", userQuery);
        }
        if (verify) {
            json.addProperty("verify", true);
        }
        return json.toString();
    }

    public static String createTextMessage(String text) {
        return createTextMessage(text, null);
    }

    public static String createTextMessage(String text, String language) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "text");
        json.addProperty("text", text);
        if (language != null && !language.isEmpty()) {
            json.addProperty("language", language);
        }
        return json.toString();
    }

    public static ServerMessage decodeServerMessage(String text) {
        JsonObject json = JsonParser.parseString(text).getAsJsonObject();
        String rawType = getString(json, "type");

        switch (rawType) {
            case "connecting":
                return ServerMessage.connecting();
            case "ready":
                return ServerMessage.ready();
            case "text":
                return ServerMessage.text(
                        getString(json, "text"),
                        getBoolean(json, "speak", true),
                        getBoolean(json, "display", true));
            case "audio":
                return ServerMessage.audio(getString(json, "data"), getNullableString(json, "text"));
            case "highlight":
                return ServerMessage.highlight(
                        getInt(json, "x", -1),
                        getInt(json, "y", -1),
                        getInt(json, "x1", -1),
                        getInt(json, "y1", -1),
                        getInt(json, "x2", -1),
                        getInt(json, "y2", -1),
                        getBoolean(json, "completed", false),
                        getBoolean(json, "blockerDetected", false),
                        getNullableString(json, "blockerReason"),
                        getNullableString(json, "direction"),
                        getNullableString(json, "target"),
                        getStringList(json, "matchHints"));
            case "transcription":
                return ServerMessage.transcription(getString(json, "text"), getBoolean(json, "partial", false));
            case "taskComplete":
                return ServerMessage.taskComplete(getString(json, "goal"));
            case "requestScreenshot":
                return ServerMessage.requestScreenshot();
            case "thinking":
                return ServerMessage.thinking();
            case "error":
                return ServerMessage.error(getString(json, "message"));
            default:
                return ServerMessage.unknown(rawType, text);
        }
    }

    private static boolean getBoolean(JsonObject json, String key, boolean fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        return json.get(key).getAsBoolean();
    }

    private static int getInt(JsonObject json, String key, int fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        return (int) Math.round(json.get(key).getAsDouble());
    }

    private static String getString(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        return json.get(key).getAsString();
    }

    private static String getNullableString(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        return json.get(key).getAsString();
    }

    private static List<String> getStringList(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull() || !json.get(key).isJsonArray()) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        json.getAsJsonArray(key).forEach(element -> {
            if (!element.isJsonNull()) {
                values.add(element.getAsString());
            }
        });
        return Collections.unmodifiableList(values);
    }

    public static final class ServerMessage {
        public enum Type {
            CONNECTING,
            READY,
            DISCONNECTED,
            TEXT,
            AUDIO,
            HIGHLIGHT,
            TRANSCRIPTION,
            TASK_COMPLETE,
            REQUEST_SCREENSHOT,
            THINKING,
            ERROR,
            UNKNOWN
        }

        private final Type type;
        private final String text;
        private final String audioData;
        private final String goal;
        private final String rawType;
        private final String rawPayload;
        private final String audioText;
        private final int highlightX;
        private final int highlightY;
        private final int highlightX1;
        private final int highlightY1;
        private final int highlightX2;
        private final int highlightY2;
        private final boolean completed;
        private final boolean blockerDetected;
        private final String blockerReason;
        private final String direction;
        private final String target;
        private final List<String> matchHints;
        private final boolean shouldSpeak;
        private final boolean shouldDisplay;
        private final boolean partial;

        private ServerMessage(
                Type type,
                String text,
                String audioData,
                String goal,
                String rawType,
                String rawPayload,
                int highlightX,
                int highlightY,
                boolean completed,
                boolean blockerDetected,
                String blockerReason,
                String direction) {
            this(type, text, audioData, goal, rawType, rawPayload, null, highlightX, highlightY,
                    -1, -1, -1, -1, completed, blockerDetected, blockerReason, direction,
                    null, Collections.emptyList(), true, true);
        }

        private ServerMessage(
                Type type,
                String text,
                String audioData,
                String goal,
                String rawType,
                String rawPayload,
                String audioText,
                int highlightX,
                int highlightY,
                int highlightX1,
                int highlightY1,
                int highlightX2,
                int highlightY2,
                boolean completed,
                boolean blockerDetected,
                String blockerReason,
                String direction,
                String target,
                List<String> matchHints,
                boolean shouldSpeak,
                boolean shouldDisplay) {
            this.type = type;
            this.text = text;
            this.audioData = audioData;
            this.goal = goal;
            this.rawType = rawType;
            this.rawPayload = rawPayload;
            this.audioText = audioText;
            this.highlightX = highlightX;
            this.highlightY = highlightY;
            this.highlightX1 = highlightX1;
            this.highlightY1 = highlightY1;
            this.highlightX2 = highlightX2;
            this.highlightY2 = highlightY2;
            this.completed = completed;
            this.blockerDetected = blockerDetected;
            this.blockerReason = blockerReason;
            this.direction = direction;
            this.target = target;
            this.matchHints = matchHints == null ? Collections.emptyList() : Collections.unmodifiableList(matchHints);
            this.shouldSpeak = shouldSpeak;
            this.shouldDisplay = shouldDisplay;
            this.partial = false;
        }

        private ServerMessage(
                Type type,
                String text,
                String audioData,
                String goal,
                String rawType,
                String rawPayload,
                int highlightX,
                int highlightY,
                boolean completed,
                boolean blockerDetected,
                String blockerReason,
                String direction,
                boolean partial) {
            this.type = type;
            this.text = text;
            this.audioData = audioData;
            this.goal = goal;
            this.rawType = rawType;
            this.rawPayload = rawPayload;
            this.audioText = null;
            this.highlightX = highlightX;
            this.highlightY = highlightY;
            this.highlightX1 = -1;
            this.highlightY1 = -1;
            this.highlightX2 = -1;
            this.highlightY2 = -1;
            this.completed = completed;
            this.blockerDetected = blockerDetected;
            this.blockerReason = blockerReason;
            this.direction = direction;
            this.target = null;
            this.matchHints = Collections.emptyList();
            this.shouldSpeak = true;
            this.shouldDisplay = true;
            this.partial = partial;
        }

        public static ServerMessage connecting() {
            return new ServerMessage(Type.CONNECTING, null, null, null, null, null, -1, -1, false, false, null, null);
        }

        public static ServerMessage ready() {
            return new ServerMessage(Type.READY, null, null, null, null, null, -1, -1, false, false, null, null);
        }

        public static ServerMessage disconnected() {
            return new ServerMessage(Type.DISCONNECTED, null, null, null, null, null, -1, -1, false, false, null, null);
        }

        public static ServerMessage text(String text) {
            return new ServerMessage(Type.TEXT, text, null, null, null, null, -1, -1, false, false, null, null);
        }

        public static ServerMessage text(String text, boolean shouldSpeak, boolean shouldDisplay) {
            return new ServerMessage(Type.TEXT, text, null, null, null, null, null, -1, -1,
                    -1, -1, -1, -1, false, false, null, null,
                    null, Collections.emptyList(), shouldSpeak, shouldDisplay);
        }

        public static ServerMessage audio(String audioData) {
            return new ServerMessage(Type.AUDIO, null, audioData, null, null, null, -1, -1, false, false, null, null);
        }

        public static ServerMessage audio(String audioData, String audioText) {
            return new ServerMessage(Type.AUDIO, null, audioData, null, null, null, audioText, -1, -1,
                    -1, -1, -1, -1, false, false, null, null,
                    null, Collections.emptyList(), true, true);
        }

        public static ServerMessage highlight(int x, int y, boolean completed, boolean blockerDetected, String blockerReason, String direction) {
            return new ServerMessage(Type.HIGHLIGHT, null, null, null, null, null, x, y, completed, blockerDetected, blockerReason, direction);
        }

        public static ServerMessage highlight(
                int x,
                int y,
                int x1,
                int y1,
                int x2,
                int y2,
                boolean completed,
                boolean blockerDetected,
                String blockerReason,
                String direction,
                String target,
                List<String> matchHints) {
            return new ServerMessage(Type.HIGHLIGHT, null, null, null, null, null, null,
                    x, y, x1, y1, x2, y2, completed, blockerDetected, blockerReason, direction,
                    target, matchHints, true, true);
        }

        public static ServerMessage transcription(String text) {
            return transcription(text, false);
        }

        public static ServerMessage transcription(String text, boolean partial) {
            return new ServerMessage(Type.TRANSCRIPTION, text, null, null, null, null, -1, -1, false, false, null, null, partial);
        }

        public static ServerMessage taskComplete(String goal) {
            return new ServerMessage(Type.TASK_COMPLETE, null, null, goal, null, null, -1, -1, false, false, null, null);
        }

        public static ServerMessage requestScreenshot() {
            return new ServerMessage(Type.REQUEST_SCREENSHOT, null, null, null, null, null, -1, -1, false, false, null, null);
        }

        public static ServerMessage thinking() {
            return new ServerMessage(Type.THINKING, null, null, null, null, null, -1, -1, false, false, null, null);
        }

        public static ServerMessage error(String text) {
            return new ServerMessage(Type.ERROR, text, null, null, null, null, -1, -1, false, false, null, null);
        }

        public static ServerMessage unknown(String rawType, String rawPayload) {
            return new ServerMessage(Type.UNKNOWN, null, null, null, rawType, rawPayload, -1, -1, false, false, null, null);
        }

        public Type getType() {
            return type;
        }

        public String getText() {
            return text;
        }

        public String getAudioData() {
            return audioData;
        }

        public String getAudioText() {
            return audioText;
        }

        public String getGoal() {
            return goal;
        }

        public String getRawType() {
            return rawType;
        }

        public String getRawPayload() {
            return rawPayload;
        }

        public int getHighlightX() {
            return highlightX;
        }

        public int getHighlightY() {
            return highlightY;
        }

        public int getHighlightX1() {
            return highlightX1;
        }

        public int getHighlightY1() {
            return highlightY1;
        }

        public int getHighlightX2() {
            return highlightX2;
        }

        public int getHighlightY2() {
            return highlightY2;
        }

        public boolean hasHighlightBounds() {
            return highlightX1 >= 0 && highlightY1 >= 0 && highlightX2 >= 0 && highlightY2 >= 0;
        }

        public boolean isCompleted() {
            return completed;
        }

        public boolean isBlockerDetected() {
            return blockerDetected;
        }

        public String getBlockerReason() {
            return blockerReason;
        }

        public String getDirection() {
            return direction;
        }

        public String getTarget() {
            return target;
        }

        public List<String> getMatchHints() {
            return matchHints;
        }

        public boolean shouldSpeak() {
            return shouldSpeak;
        }

        public boolean shouldDisplay() {
            return shouldDisplay;
        }

        public boolean isPartial() {
            return partial;
        }
    }
}
