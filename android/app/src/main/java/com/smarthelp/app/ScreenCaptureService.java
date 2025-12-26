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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {

    private static final String TAG = "SmartHelp.Capture";
    private static final String CHANNEL_ID = "smarthelp_capture_channel";
    private static final int NOTIFICATION_ID = 1001;

    // Capture interval in milliseconds (only when auto-capture is enabled)
    private static final long CAPTURE_INTERVAL_MS = 10000; // 10 seconds for auto mode

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    private Handler handler;
    private Runnable captureRunnable;

    private ApiClient apiClient;
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

    private boolean isCapturing = false;
    private boolean autoCapture = false; // Disabled by default - only capture on user request

    // Current user query
    private String pendingUserQuery = null;

    // Broadcast receiver for analyze requests
    private BroadcastReceiver analyzeRequestReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "ScreenCaptureService onCreate");

        apiClient = new ApiClient();
        handler = new Handler(Looper.getMainLooper());

        // Get real screen dimensions (including navigation bar)
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);

        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        Log.d(TAG, "Real screen size: " + screenWidth + "x" + screenHeight);

        // Scale down for faster processing (1/2 for better position accuracy)
        screenWidth = screenWidth / 2;
        screenHeight = screenHeight / 2;

        Log.d(TAG, "Screen size scaled to: " + screenWidth + "x" + screenHeight);

        // Create notification channel FIRST
        createNotificationChannel();

        // Register broadcast receiver for analyze requests from OverlayService
        registerAnalyzeReceiver();
    }

    private void registerAnalyzeReceiver() {
        analyzeRequestReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.smarthelp.ANALYZE_REQUEST".equals(intent.getAction())) {
                    String userQuery = intent.getStringExtra("userQuery");
                    Log.d(TAG, "Received analyze request with query: " + userQuery);

                    // Store the query and trigger immediate capture
                    pendingUserQuery = userQuery;
                    handler.post(() -> captureScreen());
                }
            }
        };

        IntentFilter filter = new IntentFilter("com.smarthelp.ANALYZE_REQUEST");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(analyzeRequestReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(analyzeRequestReceiver, filter);
        }
        Log.d(TAG, "Analyze request receiver registered");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "ScreenCaptureService onStartCommand");

        // MUST call startForeground immediately
        startForeground(NOTIFICATION_ID, createNotification());
        Log.d(TAG, "Foreground service started");

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
        }
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

        // Wait longer for overlay to fully hide, then capture
        handler.postDelayed(() -> doCaptureScreen(), 500);
    }

    private void doCaptureScreen() {
        if (imageReader == null) {
            Log.w(TAG, "ImageReader is null");
            return;
        }

        // Discard any old cached frames first
        Image oldImage = imageReader.acquireLatestImage();
        if (oldImage != null) {
            oldImage.close();
            Log.d(TAG, "Discarded old cached frame");
        }

        // Wait a bit more for fresh frame, then capture
        handler.postDelayed(() -> doActualCapture(), 100);
    }

    private void doActualCapture() {
        if (imageReader == null) {
            Log.w(TAG, "ImageReader is null");
            return;
        }

        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) {
                Log.d(TAG, "No image available yet");
                // Show overlay again if capture failed
                Intent showIntent = new Intent("com.smarthelp.SHOW_OVERLAY");
                showIntent.setPackage(getPackageName());
                sendBroadcast(showIntent);
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

            // Convert to base64
            String base64Image = bitmapToBase64(bitmap);
            Log.d(TAG, "Base64 image size: " + base64Image.length() + " chars");

            // Send to server for analysis
            analyzeScreenshot(base64Image);

            bitmap.recycle();

        } catch (Exception e) {
            Log.e(TAG, "Error capturing screen", e);
        } finally {
            if (image != null) {
                image.close();
            }
            // Show overlay again after capturing
            Intent showIntent = new Intent("com.smarthelp.SHOW_OVERLAY");
            showIntent.setPackage(getPackageName());
            sendBroadcast(showIntent);
        }
    }

    private String bitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        // Compress to reduce size (Quality 40 is good enough for text/UI recognition)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 40, outputStream);
        byte[] byteArray = outputStream.toByteArray();
        return Base64.encodeToString(byteArray, Base64.NO_WRAP);
    }

    private void analyzeScreenshot(String base64Image) {
        // Get and clear the pending user query
        String userQuery = pendingUserQuery;
        pendingUserQuery = null;

        Log.d(TAG, "Sending screenshot for analysis with query: " + userQuery);

        apiClient.analyzeScreenshot(base64Image, userQuery, new ApiClient.AnalysisCallback() {
            @Override
            public void onSuccess(ApiClient.GuidanceResponse response) {
                Log.d(TAG, "Analysis received: " + response.instruction);

                // Send guidance to OverlayService via broadcast
                Intent intent = new Intent("com.smarthelp.GUIDANCE_UPDATE");
                intent.setPackage(getPackageName());

                // Send simple instruction
                intent.putExtra("instruction", response.instruction);

                // Send highlight coordinates
                if (response.highlight != null) {
                    intent.putExtra("highlightX", response.highlight.x);
                    intent.putExtra("highlightY", response.highlight.y);
                    Log.d(TAG, "Highlight: x=" + response.highlight.x + ", y=" + response.highlight.y);
                }

                // Send completed flag
                intent.putExtra("completed", response.completed);
                Log.d(TAG, "Completed: " + response.completed);

                sendBroadcast(intent);
                Log.d(TAG, "Broadcast sent");
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Analysis error: " + error);

                // Send error to overlay
                Intent intent = new Intent("com.smarthelp.GUIDANCE_UPDATE");
                intent.setPackage(getPackageName());
                intent.putExtra("instruction", "Sorry, something went wrong. Please try again.");
                intent.putExtra("highlightX", -1);
                intent.putExtra("highlightY", -1);
                sendBroadcast(intent);
            }
        });
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

    @Override
    public void onDestroy() {
        Log.d(TAG, "ScreenCaptureService onDestroy");

        stopCapturing();

        // Unregister broadcast receiver
        if (analyzeRequestReceiver != null) {
            unregisterReceiver(analyzeRequestReceiver);
        }

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
