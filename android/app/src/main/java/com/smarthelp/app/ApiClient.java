package com.smarthelp.app;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ApiClient {

    private static final String TAG = "SmartHelp.ApiClient";

    // Change this to your server IP when testing on physical device
    // For emulator: use 10.0.2.2
    // For physical device: use your computer's local IP
    // Current WiFi IP: 192.168.0.155
    private static final String BASE_URL = "http://192.168.0.155:3000";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final Gson gson;

    public interface AnalysisCallback {
        void onSuccess(GuidanceResponse response);
        void onError(String error);
    }

    public ApiClient() {
        client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        gson = new Gson();
    }

    public void analyzeScreenshot(String base64Image, String userQuery, AnalysisCallback callback) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("screenshot", base64Image);
        if (userQuery != null && !userQuery.isEmpty()) {
            requestBody.addProperty("userQuery", userQuery);
        }

        RequestBody body = RequestBody.create(gson.toJson(requestBody), JSON);

        Request request = new Request.Builder()
                .url(BASE_URL + "/api/analyze")
                .post(body)
                .build();

        Log.d(TAG, "Sending request to: " + BASE_URL + "/api/analyze");
        Log.d(TAG, "Query: " + userQuery);
        Log.d(TAG, "Image size: " + base64Image.length() + " chars");

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "API call failed", e);
                callback.onError("Connection failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                Log.d(TAG, "Raw response: " + responseBody);

                if (response.isSuccessful()) {
                    try {
                        GuidanceResponse guidanceResponse = gson.fromJson(responseBody, GuidanceResponse.class);
                        Log.d(TAG, "Parsed instruction: " + guidanceResponse.instruction);
                        if (guidanceResponse.highlight != null) {
                            Log.d(TAG, "Parsed highlight: x=" + guidanceResponse.highlight.x + ", y=" + guidanceResponse.highlight.y);
                        }
                        callback.onSuccess(guidanceResponse);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse response: " + e.getMessage(), e);
                        Log.e(TAG, "Response body was: " + responseBody);
                        callback.onError("Failed to parse server response: " + e.getMessage());
                    }
                } else {
                    Log.e(TAG, "Server error: " + response.code() + " - " + responseBody);
                    callback.onError("Server error: " + response.code());
                }
            }
        });
    }

    public void testConnection(AnalysisCallback callback) {
        Request request = new Request.Builder()
                .url(BASE_URL + "/api/test")
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("Connection test failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    callback.onSuccess(null);
                } else {
                    callback.onError("Server responded with error: " + response.code());
                }
            }
        });
    }

    // Response classes - simplified format
    public static class GuidanceResponse {
        public boolean success;
        public String timestamp;
        public String instruction;  // Simple instruction like "Please tap..."
        public Highlight highlight;
        public boolean completed = false;  // True if task is complete
    }

    public static class Highlight {
        public int x = -1;  // Percentage 0-100
        public int y = -1;  // Percentage 0-100
    }
}
