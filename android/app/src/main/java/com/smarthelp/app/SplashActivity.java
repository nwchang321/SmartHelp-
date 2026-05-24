package com.smarthelp.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DELAY = 900;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // Animate logo: fade in + scale up
        ImageView logo = findViewById(R.id.imgSplashLogo);
        TextView appName = findViewById(R.id.txtSplashAppName);
        TextView tagline = findViewById(R.id.txtSplashTagline);

        if (logo != null) {
            AnimationSet animSet = new AnimationSet(true);
            AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
            fadeIn.setDuration(800);
            ScaleAnimation scaleUp = new ScaleAnimation(
                    0.7f, 1f, 0.7f, 1f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f);
            scaleUp.setDuration(800);
            animSet.addAnimation(fadeIn);
            animSet.addAnimation(scaleUp);
            logo.startAnimation(animSet);
        }

        if (appName != null) {
            AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
            fadeIn.setDuration(600);
            fadeIn.setStartOffset(400);
            appName.startAnimation(fadeIn);
        }

        if (tagline != null) {
            AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
            fadeIn.setDuration(600);
            fadeIn.setStartOffset(700);
            tagline.startAnimation(fadeIn);
        }

        // Navigate to MainActivity after delay
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }, SPLASH_DELAY);
    }
}
