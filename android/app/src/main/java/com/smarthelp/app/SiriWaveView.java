package com.smarthelp.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Siri-like animated wave view
 */
public class SiriWaveView extends View {

    private Paint wavePaint;
    private Paint glowPaint;
    private Path wavePath;

    private float phase = 0f;
    private float amplitude = 0.5f;
    private boolean isAnimating = false;

    private ValueAnimator waveAnimator;

    // Colors for gradient
    private int[] waveColors = {
            0xFF667eea,  // Purple
            0xFF764ba2,  // Pink-purple
            0xFFf093fb,  // Pink
            0xFF667eea   // Purple (loop)
    };

    public SiriWaveView(Context context) {
        super(context);
        init();
    }

    public SiriWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SiriWaveView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeWidth(4f);
        wavePaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(12f);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setAlpha(80);

        wavePath = new Path();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateGradient();
    }

    private void updateGradient() {
        if (getWidth() > 0) {
            LinearGradient gradient = new LinearGradient(
                    0, 0, getWidth(), 0,
                    waveColors, null,
                    Shader.TileMode.CLAMP
            );
            wavePaint.setShader(gradient);
            glowPaint.setShader(gradient);
        }
    }

    public void startAnimation() {
        if (isAnimating) return;
        isAnimating = true;

        waveAnimator = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        waveAnimator.setDuration(1500);
        waveAnimator.setRepeatCount(ValueAnimator.INFINITE);
        waveAnimator.setInterpolator(new LinearInterpolator());
        waveAnimator.addUpdateListener(animation -> {
            phase = (float) animation.getAnimatedValue();
            invalidate();
        });
        waveAnimator.start();
    }

    public void stopAnimation() {
        isAnimating = false;
        if (waveAnimator != null) {
            waveAnimator.cancel();
        }
        amplitude = 0.3f;
        invalidate();
    }

    public void setAmplitude(float amp) {
        this.amplitude = Math.max(0.1f, Math.min(1f, amp));
        invalidate();
    }

    public void setListening(boolean listening) {
        if (listening) {
            amplitude = 0.8f;
            startAnimation();
        } else {
            amplitude = 0.3f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        float centerY = height / 2f;
        float maxAmplitude = height * 0.4f * amplitude;

        // Draw multiple wave layers for depth effect
        for (int layer = 0; layer < 3; layer++) {
            float layerPhase = phase + (layer * 0.5f);
            float layerAmplitude = maxAmplitude * (1f - layer * 0.25f);
            float layerAlpha = 1f - layer * 0.3f;

            wavePath.reset();

            for (int x = 0; x <= width; x += 3) {
                float normalizedX = (float) x / width;

                // Combine multiple sine waves for organic look
                float y = (float) (
                        Math.sin(normalizedX * 4 * Math.PI + layerPhase) * 0.5f +
                        Math.sin(normalizedX * 2 * Math.PI + layerPhase * 0.7f) * 0.3f +
                        Math.sin(normalizedX * 6 * Math.PI + layerPhase * 1.3f) * 0.2f
                );

                // Taper at edges
                float taper = (float) Math.sin(normalizedX * Math.PI);
                y = centerY + y * layerAmplitude * taper;

                if (x == 0) {
                    wavePath.moveTo(x, y);
                } else {
                    wavePath.lineTo(x, y);
                }
            }

            // Draw glow layer
            glowPaint.setAlpha((int) (40 * layerAlpha));
            canvas.drawPath(wavePath, glowPaint);

            // Draw wave
            wavePaint.setAlpha((int) (255 * layerAlpha));
            canvas.drawPath(wavePath, wavePaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimation();
    }
}
