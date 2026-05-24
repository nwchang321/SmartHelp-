package com.smarthelp.app;

import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Thin wrapper around WindowManager.addView() / removeView() / updateViewLayout().
 *
 * Inspired by FloatingWindows (github.com/diegoRodriguezAguila/FloatingWindows).
 * Centralises the repetitive WindowManager.LayoutParams construction that was
 * scattered across OverlayService, and provides lifecycle-style show/hide/dismiss.
 *
 * Usage:
 *   OverlayWindowHelper chat = new OverlayWindowHelper(windowManager)
 *       .size(MATCH_PARENT, MATCH_PARENT)
 *       .gravity(Gravity.BOTTOM)
 *       .passThrough(true)       // invisible / non-interactive by default
 *       .build(chatView);
 *
 *   chat.show();    // makes interactive + visible
 *   chat.hide();    // pass-through + gone
 *   chat.dismiss(); // removes from WindowManager entirely
 */
public class OverlayWindowHelper {

    // ── State ─────────────────────────────────────────────────────────────────
    private final WindowManager windowManager;
    private View view;
    private WindowManager.LayoutParams params;

    private int width  = WindowManager.LayoutParams.WRAP_CONTENT;
    private int height = WindowManager.LayoutParams.WRAP_CONTENT;
    private int gravity = Gravity.NO_GRAVITY;
    private int xOffset = 0;
    private int yOffset = 0;
    private boolean initiallyPassThrough = false;
    private int extraFlags = 0;
    private boolean added = false;

    // ── Constructor ───────────────────────────────────────────────────────────

    public OverlayWindowHelper(WindowManager windowManager) {
        this.windowManager = windowManager;
    }

    // ── Builder methods ───────────────────────────────────────────────────────

    public OverlayWindowHelper size(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    public OverlayWindowHelper gravity(int gravity) {
        this.gravity = gravity;
        return this;
    }

    public OverlayWindowHelper offset(int x, int y) {
        this.xOffset = x;
        this.yOffset = y;
        return this;
    }

    /** If true, adds FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE so window is transparent to input. */
    public OverlayWindowHelper passThrough(boolean passThrough) {
        this.initiallyPassThrough = passThrough;
        return this;
    }

    /** Any additional WindowManager flags (e.g. FLAG_LAYOUT_NO_LIMITS, FLAG_DIM_BEHIND). */
    public OverlayWindowHelper flags(int flags) {
        this.extraFlags = flags;
        return this;
    }

    /**
     * Attaches the given view and adds it to the WindowManager.
     * @return this helper for chaining
     */
    public OverlayWindowHelper build(View view) {
        this.view = view;
        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                  | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;

        if (initiallyPassThrough) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        flags |= extraFlags;

        params = new WindowManager.LayoutParams(width, height, layoutType, flags, PixelFormat.TRANSLUCENT);
        params.flags = flags;   // explicit assignment — stub constructor does not set fields
        params.gravity = gravity;
        params.x = xOffset;
        params.y = yOffset;

        windowManager.addView(view, params);
        added = true;
        return this;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Makes the window interactive and visible.
     * Removes FLAG_NOT_TOUCHABLE and FLAG_NOT_FOCUSABLE, then sets visibility VISIBLE.
     */
    public void show() {
        if (!added || view == null) return;
        params.flags &= ~(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        windowManager.updateViewLayout(view, params);
        view.setVisibility(View.VISIBLE);
    }

    /**
     * Makes the window pass-through and invisible — window stays in WindowManager.
     * Re-adds FLAG_NOT_TOUCHABLE and FLAG_NOT_FOCUSABLE, then sets visibility GONE.
     */
    public void hide() {
        if (!added || view == null) return;
        view.setVisibility(View.GONE);
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                      | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        windowManager.updateViewLayout(view, params);
    }

    /**
     * Removes the view from WindowManager entirely. Call in onDestroy.
     */
    public void dismiss() {
        if (!added || view == null) return;
        try {
            windowManager.removeView(view);
        } catch (Exception ignored) {}
        added = false;
    }

    /**
     * Update position (useful for draggable windows).
     */
    public void moveTo(int x, int y) {
        if (!added || view == null) return;
        params.x = x;
        params.y = y;
        windowManager.updateViewLayout(view, params);
    }

    /**
     * Update raw params and apply — use for custom flag changes.
     */
    public void updateParams(WindowManager.LayoutParams newParams) {
        if (!added || view == null) return;
        this.params = newParams;
        windowManager.updateViewLayout(view, params);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public View getView() { return view; }
    public WindowManager.LayoutParams getParams() { return params; }
    public boolean isAdded() { return added; }
    public boolean isVisible() { return view != null && view.getVisibility() == View.VISIBLE; }
}
