package com.smarthelp.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class SmartHelpAccessibilityService extends AccessibilityService {
    private static final String TAG = "SmartHelp.Accessibility";
    private static final String ACTION_USER_ACTION_DETECTED = "com.smarthelp.USER_ACTION_DETECTED";
    private static final String ACTION_SENSITIVE_SCREEN_DETECTED = "com.smarthelp.SENSITIVE_SCREEN_DETECTED";
    private static final int MAX_SNAPSHOT_NODES = 40;
    private static final int MAX_LABEL_CHARS = 120;
    private static final long ACTION_BROADCAST_THROTTLE_MS = 150L;
    private static final long SENSITIVE_BROADCAST_THROTTLE_MS = 5000L;
    private static volatile SmartHelpAccessibilityService instance;
    private long lastActionBroadcastAtMs = 0L;
    private long lastSensitiveBroadcastAtMs = 0L;
    private String lastSensitivePackage = "";

    public static boolean isRunning() {
        return instance != null;
    }

    public static Rect findTargetBounds(String target, List<String> hints) {
        SmartHelpAccessibilityService service = instance;
        if (service == null) {
            return null;
        }
        return service.findTargetBoundsInternal(target, hints);
    }

    public static JsonArray getVisibleControlsSnapshot(int screenWidth, int screenHeight) {
        SmartHelpAccessibilityService service = instance;
        if (service == null || screenWidth <= 0 || screenHeight <= 0) {
            return new JsonArray();
        }
        return service.getVisibleControlsSnapshotInternal(screenWidth, screenHeight);
    }

    static boolean shouldPreferVisionBounds(String target, List<String> hints) {
        List<String> values = new ArrayList<>();
        addTerms(values, target);
        if (hints != null) {
            for (String hint : hints) {
                addTerms(values, hint);
            }
        }
        StringBuilder builder = new StringBuilder(" ");
        for (String value : values) {
            builder.append(value).append(' ');
        }
        String joined = builder.toString();
        return joined.contains(" gallery ")
                || joined.contains(" photo ")
                || joined.contains(" photos ")
                || joined.contains(" image ")
                || joined.contains(" images ")
                || joined.contains(" thumbnail ")
                || joined.contains(" 相册 ")
                || joined.contains(" 相簿 ")
                || joined.contains(" 圖庫 ")
                || joined.contains(" 图库 ")
                || joined.contains(" 照片 ")
                || joined.contains(" 相片 ")
                || joined.contains(" 图片 ")
                || joined.contains(" 圖片 ");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            setServiceInfo(info);
        }
        Log.d(TAG, "Accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !isUserActionEvent(event)) {
            return;
        }

        CharSequence eventPackage = event.getPackageName();
        if (eventPackage != null && getPackageName().contentEquals(eventPackage)) {
            return;
        }
        String sourcePackage = eventPackage == null ? "" : eventPackage.toString();

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && PrivacySafety.isSensitivePackage(sourcePackage)) {
            broadcastSensitiveScreen(sourcePackage);
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (now - lastActionBroadcastAtMs < ACTION_BROADCAST_THROTTLE_MS) {
            return;
        }
        lastActionBroadcastAtMs = now;

        Intent intent = new Intent(ACTION_USER_ACTION_DETECTED);
        intent.setPackage(getPackageName());
        intent.putExtra("eventType", event.getEventType());
        if (!sourcePackage.isEmpty()) {
            intent.putExtra("sourcePackage", sourcePackage);
        }
        sendBroadcast(intent);
    }

    private void broadcastSensitiveScreen(String sourcePackage) {
        long now = SystemClock.uptimeMillis();
        if (sourcePackage.equals(lastSensitivePackage)
                && now - lastSensitiveBroadcastAtMs < SENSITIVE_BROADCAST_THROTTLE_MS) {
            return;
        }
        lastSensitivePackage = sourcePackage;
        lastSensitiveBroadcastAtMs = now;

        Intent intent = new Intent(ACTION_SENSITIVE_SCREEN_DETECTED);
        intent.setPackage(getPackageName());
        intent.putExtra("sourcePackage", sourcePackage);
        sendBroadcast(intent);
        Log.d(TAG, "Sensitive screen detected: " + sourcePackage);
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    private Rect findTargetBoundsInternal(String target, List<String> hints) {
        List<String> terms = buildTerms(target, hints);
        if (terms.isEmpty()) {
            return null;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return null;
        }

        Candidate best = new Candidate();
        try {
            traverse(root, terms, best);
        } finally {
            root.recycle();
        }
        if (best.bounds != null) {
            Log.d(TAG, "Matched target=\"" + target + "\" label=\"" + best.label
                    + "\" score=" + best.score + " bounds=" + best.bounds.toShortString());
            return new Rect(best.bounds);
        }
        return null;
    }

    private JsonArray getVisibleControlsSnapshotInternal(int screenWidth, int screenHeight) {
        JsonArray controls = new JsonArray();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return controls;
        }
        try {
            traverseSnapshot(root, controls, screenWidth, screenHeight);
        } finally {
            root.recycle();
        }
        return controls;
    }

    private void traverseSnapshot(AccessibilityNodeInfo node, JsonArray controls, int screenWidth, int screenHeight) {
        if (node == null || controls.size() >= MAX_SNAPSHOT_NODES) {
            return;
        }

        if (node.isVisibleToUser()) {
            String label = buildNodeLabel(node);
            Rect bounds = getBounds(node);
            if (!label.isEmpty() && isUsable(bounds)) {
                JsonObject item = new JsonObject();
                item.addProperty("label", truncate(label, MAX_LABEL_CHARS));
                CharSequence className = node.getClassName();
                if (className != null) {
                    item.addProperty("className", className.toString());
                }
                item.addProperty("clickable", node.isClickable());
                item.addProperty("enabled", node.isEnabled());
                item.addProperty("focused", node.isFocused());
                item.addProperty("x", toPercent(bounds.centerX(), screenWidth));
                item.addProperty("y", toPercent(bounds.centerY(), screenHeight));
                item.addProperty("x1", toPercent(bounds.left, screenWidth));
                item.addProperty("y1", toPercent(bounds.top, screenHeight));
                item.addProperty("x2", toPercent(bounds.right, screenWidth));
                item.addProperty("y2", toPercent(bounds.bottom, screenHeight));
                controls.add(item);
            }
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount && controls.size() < MAX_SNAPSHOT_NODES; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            try {
                traverseSnapshot(child, controls, screenWidth, screenHeight);
            } finally {
                if (child != null) {
                    child.recycle();
                }
            }
        }
    }

    private void traverse(AccessibilityNodeInfo node, List<String> terms, Candidate best) {
        if (node == null) {
            return;
        }

        if (node.isVisibleToUser()) {
            String label = buildNodeLabel(node);
            int score = scoreLabel(label, terms);
            if (score > 0) {
                if (node.isClickable()) score += 15;
                if (node.isEnabled()) score += 5;

                Rect bounds = getEffectiveBounds(node);
                if (isUsable(bounds) && score > best.score) {
                    best.score = score;
                    best.bounds = bounds;
                    best.label = label;
                }
            }
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            try {
                traverse(child, terms, best);
            } finally {
                if (child != null) {
                    child.recycle();
                }
            }
        }
    }

    private static String buildNodeLabel(AccessibilityNodeInfo node) {
        List<String> parts = new ArrayList<>();
        addText(parts, node.getText());
        addText(parts, node.getContentDescription());
        addText(parts, node.getViewIdResourceName());
        return TextUtils.join(" ", parts);
    }

    private static void addText(List<String> parts, CharSequence value) {
        if (value == null) return;
        String text = value.toString().trim();
        if (!text.isEmpty()) {
            parts.add(text);
        }
    }

    private static Rect getEffectiveBounds(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = null;
        try {
            if (node.isClickable()) {
                return getBounds(node);
            }

            current = node.getParent();
            for (int depth = 0; depth < 4 && current != null; depth++) {
                if (current.isClickable() && current.isVisibleToUser()) {
                    return getBounds(current);
                }
                AccessibilityNodeInfo next = current.getParent();
                current.recycle();
                current = next;
            }
            return getBounds(node);
        } finally {
            if (current != null) {
                current.recycle();
            }
        }
    }

    private static Rect getBounds(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return bounds;
    }

    private static boolean isUsable(Rect bounds) {
        return bounds != null && !bounds.isEmpty() && bounds.width() >= 8 && bounds.height() >= 8;
    }

    private static int toPercent(int value, int max) {
        if (max <= 0) return 0;
        int percent = Math.round((value * 100f) / max);
        return Math.max(0, Math.min(100, percent));
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars).trim();
    }

    private static List<String> buildTerms(String target, List<String> hints) {
        List<String> values = new ArrayList<>();
        addTerms(values, target);
        if (hints != null) {
            for (String hint : hints) {
                addTerms(values, hint);
            }
        }
        addSynonyms(values);
        values.removeAll(Collections.singleton(""));
        return values;
    }

    private static void addTerms(List<String> values, String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return;
        }
        values.add(normalized);
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2 && !isGenericToken(token)) {
                values.add(token);
            }
        }
    }

    private static void addSynonyms(List<String> values) {
        String joined = " " + TextUtils.join(" ", values) + " ";
        if (joined.contains(" phone ") || joined.contains(" dialer ")) {
            Collections.addAll(values, "telephone", "dialer");
        }
        if (joined.contains(" contacts ") || joined.contains(" contact ")) {
            Collections.addAll(values, "contacts", "people");
        }
        if (joined.contains(" settings ") || joined.contains(" setting ")) {
            Collections.addAll(values, "settings", "gear");
        }
        if (joined.contains(" messages ") || joined.contains(" message ")) {
            Collections.addAll(values, "messages", "sms");
        }
    }

    private static boolean isGenericToken(String token) {
        return "tap".equals(token)
                || "press".equals(token)
                || "open".equals(token)
                || "select".equals(token)
                || "button".equals(token)
                || "icon".equals(token)
                || "app".equals(token)
                || "contact".equals(token)
                || "contacts".equals(token);
    }

    private static boolean isUserActionEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        return type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
    }

    private static int scoreLabel(String rawLabel, List<String> terms) {
        String label = normalize(rawLabel);
        if (label.isEmpty()) {
            return 0;
        }

        int score = 0;
        for (String term : terms) {
            if (term.isEmpty()) continue;
            if (label.equals(term)) {
                score = Math.max(score, 140);
            } else if (label.contains(term)) {
                score = Math.max(score, term.length() >= 4 ? 105 : 80);
            } else if (term.contains(label) && label.length() >= 3) {
                score = Math.max(score, 70);
            }
        }
        return score;
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replace("'", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static final class Candidate {
        int score = 0;
        Rect bounds;
        String label = "";
    }
}
