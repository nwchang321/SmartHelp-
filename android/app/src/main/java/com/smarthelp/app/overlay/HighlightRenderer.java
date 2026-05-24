package com.smarthelp.app.overlay;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.smarthelp.app.HighlightOverlayView;

public final class HighlightRenderer {

    private final WindowManager windowManager;
    private final HighlightOverlayView highlightView;

    public HighlightRenderer(Context context) {
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.highlightView = new HighlightOverlayView(context);

        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        windowManager.addView(highlightView, params);
        highlightView.setVisibility(View.GONE);
    }

    public void show(String position) {
        highlightView.setTargetPosition(position);
        highlightView.setVisibility(View.VISIBLE);
    }

    public void showPercent(int x, int y) {
        highlightView.setTargetPositionPercent(x, y);
        highlightView.setVisibility(View.VISIBLE);
    }

    public void showPercent(int x, int y, int x1, int y1, int x2, int y2) {
        highlightView.setTargetPositionPercent(x, y, x1, y1, x2, y2);
        highlightView.setVisibility(View.VISIBLE);
    }

    public void showBounds(Rect bounds) {
        highlightView.setTargetBounds(bounds);
        highlightView.setVisibility(View.VISIBLE);
    }

    public void showScrollDirection(String direction) {
        highlightView.clearHighlight();
        highlightView.setScrollDirection(direction);
        highlightView.setVisibility(View.VISIBLE);
    }

    public void temporaryHide() {
        highlightView.setVisibility(View.INVISIBLE);
    }

    public void restoreVisible() {
        highlightView.setVisibility(View.VISIBLE);
    }

    public void hide() {
        highlightView.clearHighlight();
        highlightView.setVisibility(View.GONE);
    }

    public boolean isVisible() {
        return highlightView.getVisibility() == View.VISIBLE;
    }

    public void release() {
        if (!highlightView.isAttachedToWindow()) return;
        try {
            windowManager.removeView(highlightView);
        } catch (IllegalArgumentException ignored) {}
    }
}
