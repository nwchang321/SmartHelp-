package com.smarthelp.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Voice wave animation view - shows animated bars like a sound visualizer
 */
public class VoiceWaveView extends View {

    private Paint barPaint;
    private int barCount = 5;
    private float[] barHeights;
    private float maxBarHeight;
    private float barWidth;
    private float barSpacing;
    private float cornerRadius;

    private ValueAnimator animator;
    private boolean isAnimating = false;
    private float phase = 0f;
    private volatile float amplitude = 1f;  // written from audio thread, read on draw thread

    // Colors for gradient
    private int colorStart = 0xFFFF9500;  // Orange
    private int colorMiddle = 0xFFB57BEE; // Purple
    private int colorEnd = 0xFF5C9CE6;    // Blue

    public VoiceWaveView(Context context) {
        super(context);
        init();
    }

    public VoiceWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VoiceWaveView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        barPaint.setStyle(Paint.Style.FILL);

        barHeights = new float[barCount];
        cornerRadius = 4f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w == 0 || h == 0) return;

        maxBarHeight = h * 0.8f;
        barWidth = w / (barCount * 2f);
        barSpacing = barWidth;

        LinearGradient gradient = new LinearGradient(
                0, 0, w, 0,
                new int[]{colorStart, colorMiddle, colorEnd},
                null,
                Shader.TileMode.CLAMP
        );
        barPaint.setShader(gradient);

        // If startAnimation() was called before layout, kick off the animator now
        if (isAnimating && (animator == null || !animator.isRunning())) {
            isAnimating = false; // reset flag so startAnimation() doesn't short-circuit
            startAnimation();
        }
    }

    public void startAnimation() {
        if (isAnimating) return;
        isAnimating = true;

        animator = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        animator.setDuration(600);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            phase = (float) animation.getAnimatedValue();
            updateBarHeights();
            invalidate();
        });
        animator.start();
    }

    public void stopAnimation() {
        isAnimating = false;
        if (animator != null) {
            animator.cancel();
        }
        // Reset bars to minimum
        for (int i = 0; i < barCount; i++) {
            barHeights[i] = maxBarHeight * 0.2f;
        }
        invalidate();
    }

    /** Call from audio thread with RMS value 0.0–1.0 to make bars react to real volume. */
    public void setAmplitude(float amp) {
        amplitude = Math.max(0f, Math.min(1f, amp));
    }

    private void updateBarHeights() {
        for (int i = 0; i < barCount; i++) {
            float barPhase = phase + (i * 0.8f);
            float wave = (float) (Math.sin(barPhase) + 1) / 2f;
            // Blend: at low amplitude show gentle idle wave; at high amplitude show full height
            float minFraction = 0.15f + wave * 0.15f;          // 0.15–0.30 idle
            float maxFraction = 0.30f + wave * 0.70f * amplitude; // scales with mic volume
            barHeights[i] = maxBarHeight * Math.max(minFraction, maxFraction);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float centerY = getHeight() / 2f;
        float startX = (getWidth() - (barCount * barWidth + (barCount - 1) * barSpacing)) / 2f;

        for (int i = 0; i < barCount; i++) {
            float x = startX + i * (barWidth + barSpacing);
            float halfHeight = barHeights[i] / 2f;

            RectF rect = new RectF(
                    x,
                    centerY - halfHeight,
                    x + barWidth,
                    centerY + halfHeight
            );

            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, barPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimation();
    }
}
