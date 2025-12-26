package com.smarthelp.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;

/**
 * Custom view that draws highlight boxes and arrows on the screen
 * to guide elderly users to tap specific areas.
 */
public class HighlightOverlayView extends View {

    // Paints
    private Paint highlightPaint;
    private Paint highlightFillPaint;
    private Paint arrowPaint;
    private Paint pulseCirclePaint;

    // Target position
    private RectF targetRect;
    private String position = "center";

    // Animation
    private ValueAnimator pulseAnimator;
    private float pulseRadius = 0f;
    private float pulseAlpha = 1f;

    // Screen dimensions
    private int screenWidth;
    private int screenHeight;

    // Arrow properties
    private Path arrowPath;
    private float arrowX, arrowY;
    private float arrowBounceOffset = 0f;
    private ValueAnimator bounceAnimator;
    private boolean arrowPointsUp = false;

    public HighlightOverlayView(Context context) {
        super(context);
        init(context);
    }

    public HighlightOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        // Get real screen dimensions (including system bars)
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        // Use getRealMetrics to get actual screen size including navigation bar
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        android.util.Log.d("HighlightOverlay", "Screen size: " + screenWidth + "x" + screenHeight);

        // Initialize highlight border paint - bright red for visibility
        highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setColor(Color.parseColor("#FF3B30")); // Bright red
        highlightPaint.setStrokeWidth(6f);

        // Initialize highlight fill paint
        highlightFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        highlightFillPaint.setStyle(Paint.Style.FILL);
        highlightFillPaint.setColor(Color.parseColor("#33FF3B30")); // Semi-transparent red

        // Initialize arrow paint - bright red
        arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setStyle(Paint.Style.FILL);
        arrowPaint.setColor(Color.parseColor("#FF3B30")); // Bright red

        // Initialize pulse circle paint
        pulseCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pulseCirclePaint.setStyle(Paint.Style.STROKE);
        pulseCirclePaint.setColor(Color.parseColor("#FF3B30"));
        pulseCirclePaint.setStrokeWidth(4f);

        // Initialize arrow path
        arrowPath = new Path();

        // Start animations
        startPulseAnimation();
        startBounceAnimation();
    }

    /**
     * Set the target position based on position string from Gemini
     * Supports both old format (top-left, center, etc.) and new format (x:50,y:85)
     */
    public void setTargetPosition(String positionStr) {
        this.position = positionStr != null ? positionStr : "center";

        int boxWidth = 150;
        int boxHeight = 80;
        int x, y;

        // Try to parse new percentage format: "x:50,y:85" or just check for numbers
        if (position.contains("x:") && position.contains("y:")) {
            // Parse "x:50,y:85" format
            try {
                String[] parts = position.split(",");
                int xPercent = Integer.parseInt(parts[0].replace("x:", "").trim());
                int yPercent = Integer.parseInt(parts[1].replace("y:", "").trim());
                x = (int) (screenWidth * xPercent / 100.0f) - boxWidth / 2;
                y = (int) (screenHeight * yPercent / 100.0f) - boxHeight / 2;
            } catch (Exception e) {
                // Fallback to center
                x = (screenWidth - boxWidth) / 2;
                y = (screenHeight - boxHeight) / 2;
            }
        } else {
            // Old 9-grid format fallback
            int padding = 50;

            if (position.contains("left")) {
                x = padding;
            } else if (position.contains("right")) {
                x = screenWidth - boxWidth - padding;
            } else {
                x = (screenWidth - boxWidth) / 2;
            }

            if (position.contains("top")) {
                y = padding + 100;
            } else if (position.contains("bottom")) {
                y = screenHeight - boxHeight - padding - 150;
            } else {
                y = (screenHeight - boxHeight) / 2;
            }
        }

        // Clamp to screen bounds
        x = Math.max(10, Math.min(x, screenWidth - boxWidth - 10));
        y = Math.max(10, Math.min(y, screenHeight - boxHeight - 10));

        targetRect = new RectF(x, y, x + boxWidth, y + boxHeight);

        // Position arrow above the highlight box
        arrowX = targetRect.centerX();
        arrowY = targetRect.top - 50;

        invalidate();
    }

    /**
     * Set target position using percentage coordinates (0-100)
     */
    public void setTargetPositionPercent(float xPercent, float yPercent) {
        // Calculate target position from percentage
        int targetX = (int) (screenWidth * xPercent / 100.0f);
        int targetY = (int) (screenHeight * yPercent / 100.0f);

        android.util.Log.d("HighlightOverlay", "Percent: " + xPercent + "%, " + yPercent + "% -> Pixel: " + targetX + ", " + targetY);

        // Store target for touch detection (small area around target)
        int boxSize = 80;
        targetRect = new RectF(targetX - boxSize/2, targetY - boxSize/2,
                               targetX + boxSize/2, targetY + boxSize/2);

        // Position arrow directly above/below the target point
        arrowX = targetX;

        // Arrow offset from target (closer now)
        int arrowOffset = 60;

        if (yPercent < 20) {
            // Element is at top, arrow points up from below
            arrowY = targetY + arrowOffset;
            arrowPointsUp = true;
        } else {
            // Arrow points down from above
            arrowY = targetY - arrowOffset;
            arrowPointsUp = false;
        }

        // Make sure arrow is visible on screen
        arrowX = Math.max(40, Math.min(arrowX, screenWidth - 40));
        arrowY = Math.max(60, Math.min(arrowY, screenHeight - 60));

        invalidate();
    }

    /**
     * Set exact pixel coordinates for the highlight
     */
    public void setTargetCoordinates(int x, int y, int width, int height) {
        targetRect = new RectF(x, y, x + width, y + height);
        arrowX = targetRect.centerX();
        arrowY = targetRect.top - 60;
        invalidate();
    }

    /**
     * Clear the highlight
     */
    public void clearHighlight() {
        targetRect = null;
        invalidate();
    }

    private void startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(1500);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnimator.addUpdateListener(animation -> {
            float value = (float) animation.getAnimatedValue();
            pulseRadius = value * 50f;
            pulseAlpha = 1f - value;
            invalidate();
        });
        pulseAnimator.start();
    }

    private void startBounceAnimation() {
        bounceAnimator = ValueAnimator.ofFloat(0f, 20f, 0f);
        bounceAnimator.setDuration(800);
        bounceAnimator.setRepeatCount(ValueAnimator.INFINITE);
        bounceAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        bounceAnimator.addUpdateListener(animation -> {
            arrowBounceOffset = (float) animation.getAnimatedValue();
            invalidate();
        });
        bounceAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (targetRect == null) return;

        // Only draw the bouncing arrow (no box or pulsing circle)
        drawArrow(canvas, arrowX, arrowY - arrowBounceOffset);
    }

    private void drawArrow(Canvas canvas, float x, float y) {
        arrowPath.reset();

        float arrowSize = 55f;  // Arrow size

        if (arrowPointsUp) {
            // Draw an upward pointing arrow (target is above)
            arrowPath.moveTo(x, y - arrowSize); // Top point (pointing up)
            arrowPath.lineTo(x - arrowSize * 0.6f, y); // Bottom left
            arrowPath.lineTo(x - arrowSize * 0.2f, y); // Inner left
            arrowPath.lineTo(x - arrowSize * 0.2f, y + arrowSize * 0.4f); // Stem bottom left
            arrowPath.lineTo(x + arrowSize * 0.2f, y + arrowSize * 0.4f); // Stem bottom right
            arrowPath.lineTo(x + arrowSize * 0.2f, y); // Inner right
            arrowPath.lineTo(x + arrowSize * 0.6f, y); // Bottom right
            arrowPath.close();
        } else {
            // Draw a downward pointing arrow (target is below)
            arrowPath.moveTo(x, y + arrowSize); // Bottom point (pointing down)
            arrowPath.lineTo(x - arrowSize * 0.6f, y); // Top left
            arrowPath.lineTo(x - arrowSize * 0.2f, y); // Inner left
            arrowPath.lineTo(x - arrowSize * 0.2f, y - arrowSize * 0.4f); // Stem top left
            arrowPath.lineTo(x + arrowSize * 0.2f, y - arrowSize * 0.4f); // Stem top right
            arrowPath.lineTo(x + arrowSize * 0.2f, y); // Inner right
            arrowPath.lineTo(x + arrowSize * 0.6f, y); // Top right
            arrowPath.close();
        }

        canvas.drawPath(arrowPath, arrowPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
        if (bounceAnimator != null) {
            bounceAnimator.cancel();
        }
    }
}
