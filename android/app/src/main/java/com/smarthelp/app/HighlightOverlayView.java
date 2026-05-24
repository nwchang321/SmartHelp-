package com.smarthelp.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
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
    private static final String TAG = "HighlightOverlay";

    // Paints
    private Paint highlightPaint;
    private Paint highlightFillPaint;
    private Paint arrowPaint;
    private Paint pulseCirclePaint;
    private Paint scrollArrowPaint;
    private Paint scrollBgPaint;
    private Paint centerDotPaint;
    private Paint centerDotBorderPaint;

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
    private final int[] viewLocationOnScreen = new int[2];

    // Scroll direction mode
    private boolean scrollDirectionMode = false;
    private String scrollDirection = null;
    private float scrollArrowBounceOffset = 0f;
    private ValueAnimator scrollBounceAnimator;

    public HighlightOverlayView(Context context) {
        super(context);
        init(context);
    }

    public HighlightOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setWillNotDraw(false);
        // Get real screen dimensions (including system bars)
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        // Use getRealMetrics to get actual screen size including navigation bar
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        android.util.Log.d(TAG, "Screen size: " + screenWidth + "x" + screenHeight);

        // Initialize highlight border paint - bright orange for high visibility
        highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setColor(Color.parseColor("#FF6B00")); // Bright orange
        highlightPaint.setStrokeWidth(8f);

        // Initialize highlight fill paint - semi-transparent orange
        highlightFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        highlightFillPaint.setStyle(Paint.Style.FILL);
        highlightFillPaint.setColor(Color.parseColor("#55FF6B00")); // Semi-transparent orange

        // Initialize arrow paint - bright orange
        arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setStyle(Paint.Style.FILL);
        arrowPaint.setColor(Color.parseColor("#FF6B00")); // Bright orange

        // Initialize pulse circle paint
        pulseCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pulseCirclePaint.setStyle(Paint.Style.STROKE);
        pulseCirclePaint.setColor(Color.parseColor("#FF6B00"));
        pulseCirclePaint.setStrokeWidth(5f);

        // Initialize scroll direction arrow paint
        scrollArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        scrollArrowPaint.setStyle(Paint.Style.FILL);
        scrollArrowPaint.setColor(Color.parseColor("#FF6B00"));

        // Initialize scroll direction background paint
        scrollBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        scrollBgPaint.setStyle(Paint.Style.FILL);
        scrollBgPaint.setColor(Color.parseColor("#33FF6B00"));

        centerDotBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerDotBorderPaint.setStyle(Paint.Style.FILL);
        centerDotBorderPaint.setColor(Color.WHITE);

        centerDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerDotPaint.setStyle(Paint.Style.FILL);
        centerDotPaint.setColor(Color.parseColor("#FF3D00"));

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
        setTargetPositionPercent(xPercent, yPercent, -1, -1, -1, -1);
    }

    public void setTargetPositionPercent(float xPercent, float yPercent, float x1Percent, float y1Percent, float x2Percent, float y2Percent) {
        scrollDirectionMode = false;
        scrollDirection = null;
        if (scrollBounceAnimator != null) {
            scrollBounceAnimator.cancel();
        }

        int overlayWidth = getWidth() > 0 ? getWidth() : screenWidth;
        int overlayHeight = getHeight() > 0 ? getHeight() : screenHeight;

        float safeXPercent = Math.max(0f, Math.min(100f, xPercent));
        float safeYPercent = Math.max(0f, Math.min(100f, yPercent));
        int targetX = Math.round((overlayWidth - 1) * (safeXPercent / 100.0f));
        int targetY = Math.round((overlayHeight - 1) * (safeYPercent / 100.0f));

        android.util.Log.d(TAG, "Percent: " + xPercent + "%, " + yPercent
                + "% -> Local: " + targetX + ", " + targetY
                + " (overlay=" + overlayWidth + "x" + overlayHeight + ")");

        targetX = Math.max(0, Math.min(targetX, overlayWidth - 1));
        targetY = Math.max(0, Math.min(targetY, overlayHeight - 1));

        targetRect = buildTargetRectFromPercentBounds(
                targetX, targetY, overlayWidth, overlayHeight,
                x1Percent, y1Percent, x2Percent, y2Percent);

        configureArrow(targetX, targetY, safeYPercent, overlayWidth, overlayHeight);

        postInvalidateOnAnimation();
    }

    public void setTargetBounds(Rect bounds) {
        if (bounds == null || bounds.isEmpty()) {
            return;
        }
        scrollDirectionMode = false;
        scrollDirection = null;
        if (scrollBounceAnimator != null) {
            scrollBounceAnimator.cancel();
        }

        int overlayWidth = getWidth() > 0 ? getWidth() : screenWidth;
        int overlayHeight = getHeight() > 0 ? getHeight() : screenHeight;
        getLocationOnScreen(viewLocationOnScreen);

        float left = bounds.left - viewLocationOnScreen[0];
        float top = bounds.top - viewLocationOnScreen[1];
        float right = bounds.right - viewLocationOnScreen[0];
        float bottom = bounds.bottom - viewLocationOnScreen[1];
        targetRect = enforceMinimumRect(new RectF(left, top, right, bottom));

        int targetX = Math.round(targetRect.centerX());
        int targetY = Math.round(targetRect.centerY());
        float yPercent = overlayHeight <= 1 ? 50f : (targetY * 100f / (overlayHeight - 1));
        configureArrow(targetX, targetY, yPercent, overlayWidth, overlayHeight);

        android.util.Log.d(TAG, "Accessibility bounds: " + bounds.toShortString()
                + " -> target=" + targetX + "," + targetY
                + " (overlay=" + overlayWidth + "x" + overlayHeight
                + ", origin=" + viewLocationOnScreen[0] + "," + viewLocationOnScreen[1] + ")");

        postInvalidateOnAnimation();
    }

    private RectF buildTargetRectFromPercentBounds(
            int targetX,
            int targetY,
            int overlayWidth,
            int overlayHeight,
            float x1Percent,
            float y1Percent,
            float x2Percent,
            float y2Percent) {
        if (x1Percent >= 0 && y1Percent >= 0 && x2Percent >= 0 && y2Percent >= 0) {
            float left = overlayWidth * Math.max(0f, Math.min(100f, Math.min(x1Percent, x2Percent))) / 100f;
            float top = overlayHeight * Math.max(0f, Math.min(100f, Math.min(y1Percent, y2Percent))) / 100f;
            float right = overlayWidth * Math.max(0f, Math.min(100f, Math.max(x1Percent, x2Percent))) / 100f;
            float bottom = overlayHeight * Math.max(0f, Math.min(100f, Math.max(y1Percent, y2Percent))) / 100f;
            RectF bounded = new RectF(left, top, right, bottom);
            return enforceMinimumRect(new RectF(
                    targetX - bounded.width() / 2f,
                    targetY - bounded.height() / 2f,
                    targetX + bounded.width() / 2f,
                    targetY + bounded.height() / 2f));
        }
        int boxSize = 72;
        return new RectF(
                targetX - boxSize / 2f,
                targetY - boxSize / 2f,
                targetX + boxSize / 2f,
                targetY + boxSize / 2f);
    }

    private RectF enforceMinimumRect(RectF rect) {
        float minSize = 56f;
        float cx = rect.centerX();
        float cy = rect.centerY();
        float width = Math.max(minSize, rect.width());
        float height = Math.max(minSize, rect.height());
        return new RectF(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f);
    }

    private void configureArrow(int targetX, int targetY, float yPercent, int overlayWidth, int overlayHeight) {
        // Position arrow directly above/below the target point
        arrowX = targetX;

        float arrowSize = 55f;

        if (yPercent < 20) {
            // Element is at top, arrow points upward from below; tip lands on the dot.
            arrowY = targetY + arrowSize;
            arrowPointsUp = true;
        } else {
            // Arrow points downward from above; tip lands on the dot.
            arrowY = targetY - arrowSize;
            arrowPointsUp = false;
        }

        // Make sure arrow is visible on screen
        arrowX = Math.max(40, Math.min(arrowX, overlayWidth - 40));
        arrowY = Math.max(60, Math.min(arrowY, overlayHeight - 60));

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
        scrollDirectionMode = false;
        scrollDirection = null;
        if (scrollBounceAnimator != null) {
            scrollBounceAnimator.cancel();
        }
        invalidate();
    }

    public void setScrollDirection(String direction) {
        this.scrollDirectionMode = true;
        this.scrollDirection = direction;
        this.targetRect = null;
        startScrollBounceAnimation();
        invalidate();
    }

    public void clearScrollDirection() {
        this.scrollDirectionMode = false;
        this.scrollDirection = null;
        if (scrollBounceAnimator != null) {
            scrollBounceAnimator.cancel();
        }
        invalidate();
    }

    private void startScrollBounceAnimation() {
        if (scrollBounceAnimator != null) {
            scrollBounceAnimator.cancel();
        }
        scrollBounceAnimator = ValueAnimator.ofFloat(0f, 30f, 0f);
        scrollBounceAnimator.setDuration(1200);
        scrollBounceAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scrollBounceAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        scrollBounceAnimator.addUpdateListener(animation -> {
            scrollArrowBounceOffset = (float) animation.getAnimatedValue();
            invalidate();
        });
        scrollBounceAnimator.start();
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

        if (scrollDirectionMode && scrollDirection != null) {
            drawScrollDirectionArrow(canvas);
            return;
        }

        if (targetRect == null) return;

        android.util.Log.d(TAG, "onDraw target=" + targetRect.toShortString()
                + " canvas=" + canvas.getWidth() + "x" + canvas.getHeight());

        float cx = targetRect.centerX();
        float cy = targetRect.centerY();
        float radius = Math.max(28f, Math.min(56f, Math.max(targetRect.width(), targetRect.height()) / 2f + 8f));

        // 1. Pulsing outer ring (expanding animation)
        if (pulseRadius > 0) {
            pulseCirclePaint.setAlpha((int) (pulseAlpha * 120));
            canvas.drawCircle(cx, cy, radius + pulseRadius * 0.45f, pulseCirclePaint);
        }

        // 2. Compact ring around the tappable element
        canvas.drawCircle(cx, cy, radius, highlightFillPaint);
        canvas.drawCircle(cx, cy, radius, highlightPaint);

        // 3. Arrow tip points at the center dot.
        float animatedArrowY = arrowPointsUp ? arrowY + arrowBounceOffset : arrowY - arrowBounceOffset;
        drawArrow(canvas, arrowX, animatedArrowY);

        // 4. Center dot is the exact tap point.
        canvas.drawCircle(cx, cy, 11f, centerDotBorderPaint);
        canvas.drawCircle(cx, cy, 6f, centerDotPaint);
    }

    private void drawScrollDirectionArrow(Canvas canvas) {
        int w = canvas.getWidth();
        int h = canvas.getHeight();
        float arrowSize = 120f;
        float cx, cy;
        float bounceX = 0f, bounceY = 0f;

        switch (scrollDirection) {
            case "down":
                cx = w / 2f;
                cy = h - 200f;
                bounceY = scrollArrowBounceOffset;
                break;
            case "up":
                cx = w / 2f;
                cy = 200f;
                bounceY = -scrollArrowBounceOffset;
                break;
            case "left":
                cx = 150f;
                cy = h / 2f;
                bounceX = -scrollArrowBounceOffset;
                break;
            case "right":
                cx = w - 150f;
                cy = h / 2f;
                bounceX = scrollArrowBounceOffset;
                break;
            default:
                return;
        }

        cx += bounceX;
        cy += bounceY;

        // Draw semi-transparent background pill
        float bgPadding = 40f;
        RectF bgRect;
        if ("down".equals(scrollDirection) || "up".equals(scrollDirection)) {
            bgRect = new RectF(cx - arrowSize - bgPadding, cy - arrowSize * 0.8f,
                    cx + arrowSize + bgPadding, cy + arrowSize * 0.8f);
        } else {
            bgRect = new RectF(cx - arrowSize * 0.8f, cy - arrowSize - bgPadding,
                    cx + arrowSize * 0.8f, cy + arrowSize + bgPadding);
        }
        canvas.drawRoundRect(bgRect, 30f, 30f, scrollBgPaint);

        // Draw the arrow path
        Path arrowPath = new Path();
        switch (scrollDirection) {
            case "down":
                arrowPath.moveTo(cx, cy + arrowSize);
                arrowPath.lineTo(cx - arrowSize * 0.6f, cy);
                arrowPath.lineTo(cx - arrowSize * 0.2f, cy);
                arrowPath.lineTo(cx - arrowSize * 0.2f, cy - arrowSize * 0.4f);
                arrowPath.lineTo(cx + arrowSize * 0.2f, cy - arrowSize * 0.4f);
                arrowPath.lineTo(cx + arrowSize * 0.2f, cy);
                arrowPath.lineTo(cx + arrowSize * 0.6f, cy);
                arrowPath.close();
                break;
            case "up":
                arrowPath.moveTo(cx, cy - arrowSize);
                arrowPath.lineTo(cx - arrowSize * 0.6f, cy);
                arrowPath.lineTo(cx - arrowSize * 0.2f, cy);
                arrowPath.lineTo(cx - arrowSize * 0.2f, cy + arrowSize * 0.4f);
                arrowPath.lineTo(cx + arrowSize * 0.2f, cy + arrowSize * 0.4f);
                arrowPath.lineTo(cx + arrowSize * 0.2f, cy);
                arrowPath.lineTo(cx + arrowSize * 0.6f, cy);
                arrowPath.close();
                break;
            case "left":
                arrowPath.moveTo(cx - arrowSize, cy);
                arrowPath.lineTo(cx, cy - arrowSize * 0.6f);
                arrowPath.lineTo(cx, cy - arrowSize * 0.2f);
                arrowPath.lineTo(cx + arrowSize * 0.4f, cy - arrowSize * 0.2f);
                arrowPath.lineTo(cx + arrowSize * 0.4f, cy + arrowSize * 0.2f);
                arrowPath.lineTo(cx, cy + arrowSize * 0.2f);
                arrowPath.lineTo(cx, cy + arrowSize * 0.6f);
                arrowPath.close();
                break;
            case "right":
                arrowPath.moveTo(cx + arrowSize, cy);
                arrowPath.lineTo(cx, cy - arrowSize * 0.6f);
                arrowPath.lineTo(cx, cy - arrowSize * 0.2f);
                arrowPath.lineTo(cx - arrowSize * 0.4f, cy - arrowSize * 0.2f);
                arrowPath.lineTo(cx - arrowSize * 0.4f, cy + arrowSize * 0.2f);
                arrowPath.lineTo(cx, cy + arrowSize * 0.2f);
                arrowPath.lineTo(cx, cy + arrowSize * 0.6f);
                arrowPath.close();
                break;
        }
        canvas.drawPath(arrowPath, scrollArrowPaint);
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
        if (scrollBounceAnimator != null) {
            scrollBounceAnimator.cancel();
        }
    }
}
