package com.smarthelp.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.JsonArray;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {

    private static final String TAG = "SmartHelp.Capture";
    private static final String CHANNEL_ID = "smarthelp_capture_channel";
    private static final int NOTIFICATION_ID = 1001;
    public static final String ACTION_ANALYZE_REQUEST = "com.smarthelp.ANALYZE_REQUEST";
    public static final String ACTION_STOP_SERVICE = "com.smarthelp.STOP_SERVICE";
    public static final String ACTION_SERVICE_STOPPED = "com.smarthelp.SERVICE_STOPPED";

    // Capture interval in milliseconds (only when auto-capture is enabled)
    private static final long CAPTURE_INTERVAL_MS = 10000; // 10 seconds for auto mode
    private static final int MAX_CAPTURE_RETRIES = 8;
    private static final long CAPTURE_RETRY_DELAY_MS = 150;
    private static final int JPEG_QUALITY = 70;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    private Handler handler;
    private Runnable captureRunnable;

    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

    private boolean isCapturing = false;
    private boolean autoCapture = false; // Disabled by default - only capture on user request

    // Shared memory for fast cross-service transfer without hitting Intent size limits
    public static String latestScreenshotBase64 = null;
    public static int latestScreenshotWidth = 0;
    public static int latestScreenshotHeight = 0;
    public static JsonArray latestAccessibilitySnapshot = new JsonArray();

    // Lightweight hash of the latest screenshot for change detection (Plan 2: screenshot comparison)
    public static long latestScreenshotHash = 0;

    // Broadcast receiver for capture requests
    private BroadcastReceiver analyzeRequestReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "ScreenCaptureService onCreate");

        handler = new Handler(Looper.getMainLooper());

        // Get real screen dimensions (including navigation bar)
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);

        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        Log.d(TAG, "Screen size: " + screenWidth + "x" + screenHeight);

        // Create notification channel FIRST
        createNotificationChannel();

        // Register broadcast receiver for analyze requests from OverlayService
        registerAnalyzeReceiver();
    }

    private void registerAnalyzeReceiver() {
        analyzeRequestReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_ANALYZE_REQUEST.equals(action)) {
                    Log.d(TAG, "Received capture request from Overlay");
                    handler.post(() -> captureScreen());
                } else if (ACTION_STOP_SERVICE.equals(action)) {
                    Log.d(TAG, "Received stop request");
                    handler.post(() -> shutdownAndStop());
                }
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_ANALYZE_REQUEST);
        filter.addAction(ACTION_STOP_SERVICE);
        ContextCompat.registerReceiver(
                this,
                analyzeRequestReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        Log.d(TAG, "Analyze request receiver registered");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "ScreenCaptureService onStartCommand");

        // MUST call startForeground immediately
        startForeground(NOTIFICATION_ID, createNotification());
        Log.d(TAG, "Foreground service started");

        if (intent != null && ACTION_STOP_SERVICE.equals(intent.getAction())) {
            shutdownAndStop();
            return START_NOT_STICKY;
        }

        if (intent != null) {
            int resultCode = intent.getIntExtra("resultCode", -1);
            Intent resultData = intent.getParcelableExtra("resultData");

            Log.d(TAG, "Result code: " + resultCode + ", resultData: " + (resultData != null));

            if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
                setupMediaProjection(resultCode, resultData);
            } else {
                Log.e(TAG, "Invalid result code or data (resultCode: " + resultCode + ")");
                stopSelf();
            }
        } else {
            Log.e(TAG, "Intent is null");
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void setupMediaProjection(int resultCode, Intent resultData) {
        Log.d(TAG, "Setting up MediaProjection...");

        try {
            MediaProjectionManager projectionManager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);

            if (mediaProjection != null) {
                Log.d(TAG, "MediaProjection created successfully");

                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        Log.d(TAG, "MediaProjection stopped");
                        stopCapturing();
                    }
                }, handler);

                setupVirtualDisplay();
                startCapturing();
            } else {
                Log.e(TAG, "Failed to create MediaProjection");
                stopSelf();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up MediaProjection", e);
            stopSelf();
        }
    }

    private void setupVirtualDisplay() {
        Log.d(TAG, "Setting up VirtualDisplay...");

        try {
            imageReader = ImageReader.newInstance(
                    screenWidth,
                    screenHeight,
                    PixelFormat.RGBA_8888,
                    2
            );

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "SmartHelpCapture",
                    screenWidth,
                    screenHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    handler
            );

            Log.d(TAG, "VirtualDisplay created: " + screenWidth + "x" + screenHeight);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up VirtualDisplay", e);
        }
    }

    private void startCapturing() {
        Log.d(TAG, "Starting capture service (waiting for user requests)...");
        isCapturing = true;

        // Only start auto-capture loop if enabled
        if (autoCapture) {
            captureRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isCapturing && autoCapture) {
                        captureScreen();
                        handler.postDelayed(this, CAPTURE_INTERVAL_MS);
                    }
                }
            };
            handler.postDelayed(captureRunnable, 2000);
        }

        // No auto-capture - wait for user to tap microphone and ask a question
        Log.d(TAG, "Ready to receive analyze requests from user");
    }

    private void stopCapturing() {
        Log.d(TAG, "Stopping capture loop...");
        isCapturing = false;
        if (captureRunnable != null) {
            handler.removeCallbacks(captureRunnable);
            captureRunnable = null;
        }
    }

    private void shutdownAndStop() {
        Log.d(TAG, "Shutting down ScreenCaptureService");
        stopCapturing();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        latestScreenshotBase64 = null;
        latestScreenshotWidth = 0;
        latestScreenshotHeight = 0;
        latestScreenshotHash = 0;
        releaseCaptureResources();
        broadcastServiceStopped();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void captureScreen() {
        if (imageReader == null) {
            Log.w(TAG, "ImageReader is null");
            return;
        }

        // Hide overlay before capturing
        Intent hideIntent = new Intent("com.smarthelp.HIDE_OVERLAY");
        hideIntent.setPackage(getPackageName());
        sendBroadcast(hideIntent);
        Log.d(TAG, "Sent HIDE_OVERLAY broadcast");

        // Wait for overlay windows to be hidden, then capture the latest available frame.
        handler.postDelayed(() -> doActualCapture(0), 650);
    }

    private void doActualCapture(int retryCount) {
        if (imageReader == null) {
            Log.w(TAG, "ImageReader is null");
            return;
        }

        Image image = null;
        boolean shouldShowOverlay = true;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) {
                if (retryCount < MAX_CAPTURE_RETRIES) {
                    Log.d(TAG, "No image available yet, retrying " + (retryCount + 1) + "/" + MAX_CAPTURE_RETRIES);
                    shouldShowOverlay = false;
                    handler.postDelayed(() -> doActualCapture(retryCount + 1), CAPTURE_RETRY_DELAY_MS);
                    return;
                }
                Log.d(TAG, "No image available after retries");
                return;
            }

            Log.d(TAG, "Captured image: " + image.getWidth() + "x" + image.getHeight());

            // Convert image to bitmap
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;

            Bitmap bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
            );
            bitmap.copyPixelsFromBuffer(buffer);

            // Crop to actual screen size
            if (rowPadding > 0) {
                Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);
                bitmap.recycle();
                bitmap = croppedBitmap;
            }

            // Compute lightweight hash BEFORE compression for change detection
            latestScreenshotHash = computeBitmapHash(bitmap);
            latestScreenshotWidth = bitmap.getWidth();
            latestScreenshotHeight = bitmap.getHeight();
            latestAccessibilitySnapshot = SmartHelpAccessibilityService.getVisibleControlsSnapshot(
                    latestScreenshotWidth,
                    latestScreenshotHeight
            );

            // Convert to base64
            String base64Image = bitmapToBase64(bitmap);
            Log.d(TAG, "Base64 image size: " + base64Image.length()
                    + " chars, size: " + latestScreenshotWidth + "x" + latestScreenshotHeight
                    + ", hash: " + latestScreenshotHash);

            // Expose broadly to OverlayService
            latestScreenshotBase64 = base64Image;

            // Notify OverlayService that screenshot is ready
            Intent intent = new Intent("com.smarthelp.SCREENSHOT_READY");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);

            bitmap.recycle();

        } catch (Exception e) {
            Log.e(TAG, "Error capturing screen", e);
        } finally {
            if (image != null) {
                image.close();
            }
            if (shouldShowOverlay) {
                // Show overlay again after capturing or after a final capture failure.
                Intent showIntent = new Intent("com.smarthelp.SHOW_OVERLAY");
                showIntent.setPackage(getPackageName());
                sendBroadcast(showIntent);
            }
        }
    }

    /**
     * Compute a lightweight perceptual hash by sampling an 8×8 grid of pixels.
     * Quantizes each pixel to reduce noise from minor rendering differences.
     * Two screenshots of the same screen state will produce the same hash.
     */
    static long computeBitmapHash(Bitmap bitmap) {
        if (bitmap == null) return 0;
        long hash = 17;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        // Sample 8×8 grid = 64 points across the screen
        for (int gy = 0; gy < 8; gy++) {
            for (int gx = 0; gx < 8; gx++) {
                int px = Math.min(gx * w / 8 + w / 16, w - 1);
                int py = Math.min(gy * h / 8 + h / 16, h - 1);
                int pixel = bitmap.getPixel(px, py);
                // Keep top 4 bits of each channel to absorb minor pixel noise
                int quantized = (pixel >> 4) & 0x0F0F0F0F;
                hash = hash * 31 + quantized;
            }
        }
        return hash;
    }

    private String bitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        // Keep UI text and small icons clear enough for coordinate grounding.
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream);
        byte[] byteArray = outputStream.toByteArray();
        return Base64.encodeToString(byteArray, Base64.NO_WRAP);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SmartHelp+ Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("SmartHelp+ screen capture service");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                Log.d(TAG, "Notification channel created");
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SmartHelp+ Active")
                .setContentText("Helping you navigate your phone")
                .setSmallIcon(android.R.drawable.ic_menu_help)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void releaseCaptureResources() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (Exception e) {
                Log.w(TAG, "MediaProjection stop failed: " + e.getMessage());
            }
            mediaProjection = null;
        }
    }

    private void broadcastServiceStopped() {
        Intent stoppedIntent = new Intent(ACTION_SERVICE_STOPPED);
        stoppedIntent.setPackage(getPackageName());
        sendBroadcast(stoppedIntent);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "ScreenCaptureService onDestroy");

        stopCapturing();

        // Unregister broadcast receiver
        if (analyzeRequestReceiver != null) {
            unregisterReceiver(analyzeRequestReceiver);
            analyzeRequestReceiver = null;
        }

        releaseCaptureResources();
        broadcastServiceStopped();

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
