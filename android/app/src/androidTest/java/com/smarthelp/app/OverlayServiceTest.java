package com.smarthelp.app;

import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Instrumented tests for OverlayService using UIAutomator.
 *
 * These tests require:
 *   1. A connected device / emulator with Google Speech Recognition installed
 *   2. SYSTEM_ALERT_WINDOW permission granted (granted automatically by the helper below)
 *   3. The SmartHelp+ app installed (installed by the test runner)
 *
 * Run with: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4.class)
public class OverlayServiceTest {

    private static final String APP_PACKAGE = "com.smarthelp.app";
    private static final String MIC_BUTTON_DESC = "Tap to speak";
    private static final long TIMEOUT_MS = 5_000;
    private static final long SERVICE_START_TIMEOUT = 5_000;

    private UiDevice uiDevice;
    private Context context;

    @Before
    public void setUp() {
        uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppPrefs.setLanguage(context, AppPrefs.DEFAULT_LANGUAGE);
        AppPrefs.setPrivacyConsent(context, true);
        OverlayService.setSkipServerConnectionForTests(true);
        grantOverlayPermission();
    }

    @After
    public void tearDown() {
        context.stopService(new Intent(context, OverlayService.class));
        OverlayService.setSkipServerConnectionForTests(false);
        uiDevice.pressHome();
    }

    /**
     * Grants SYSTEM_ALERT_WINDOW via ADB shell if not already granted.
     * No user interaction needed in CI / physical device testing.
     */
    private void grantOverlayPermission() {
        if (!Settings.canDrawOverlays(context)) {
            runShellCommand("appops set " + APP_PACKAGE + " SYSTEM_ALERT_WINDOW allow");
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
        }
        runShellCommand("pm grant " + APP_PACKAGE + " android.permission.RECORD_AUDIO");
    }

    /**
     * Verify the overlay mic button appears on screen after service start.
     */
    @Test
    public void overlayMicButton_appearsAfterServiceStart() {
        startOverlayService();

        UiObject2 micButton = uiDevice.wait(
                Until.findObject(By.desc(MIC_BUTTON_DESC)),
                SERVICE_START_TIMEOUT
        );
        assertNotNull("Mic button should be visible after service starts", micButton);
    }

    /**
     * Tap the mic button and verify the listening status appears in either locale.
     */
    @Test
    public void micButton_tapStartsListening() {
        startOverlayService();

        UiObject2 micButton = uiDevice.wait(
                Until.findObject(By.desc(MIC_BUTTON_DESC)),
                SERVICE_START_TIMEOUT
        );
        assertNotNull("Mic button must be visible", micButton);

        micButton.click();

        UiObject2 statusPill = waitForAnyText(TIMEOUT_MS, "Listening", "听");
        assertNotNull("Listening status pill should appear after mic tap", statusPill);
    }

    /**
     * Tap the chat button and verify the chat panel opens.
     */
    @Test
    public void chatButton_opensChatPanel() {
        startOverlayService();

        UiObject2 chatBtn = uiDevice.wait(
                Until.findObject(By.desc("Chat history")),
                SERVICE_START_TIMEOUT
        );
        assertNotNull("Chat button should be visible", chatBtn);
        chatBtn.click();

        UiObject2 closeBtn = uiDevice.wait(
                Until.findObject(By.desc("Close")),
                TIMEOUT_MS
        );
        assertNotNull("Chat panel close button should appear", closeBtn);
    }

    /**
     * Open chat panel then close it with the close button.
     * Verify the panel is dismissed and the mic overlay is still present.
     */
    @Test
    public void chatPanel_closesWithCloseButton() {
        startOverlayService();

        UiObject2 chatBtn = uiDevice.wait(
                Until.findObject(By.desc("Chat history")),
                SERVICE_START_TIMEOUT
        );
        assertNotNull(chatBtn);
        chatBtn.click();

        UiObject2 closeBtn = uiDevice.wait(
                Until.findObject(By.desc("Close")),
                TIMEOUT_MS
        );
        assertNotNull(closeBtn);
        closeBtn.click();

        boolean chatGone = uiDevice.wait(Until.gone(By.desc("Close")), TIMEOUT_MS);
        assertTrue("Chat panel should close", chatGone);

        UiObject2 mic = uiDevice.findObject(By.desc(MIC_BUTTON_DESC));
        assertNotNull("Mic overlay should still be on screen", mic);
    }

    private void startOverlayService() {
        Intent intent = new Intent(context, OverlayService.class);
        context.startForegroundService(intent);
    }

    private UiObject2 waitForAnyText(long timeoutMs, String... fragments) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (String fragment : fragments) {
                UiObject2 match = uiDevice.findObject(By.textContains(fragment));
                if (match != null) return match;
            }
            uiDevice.waitForIdle();
        }
        return null;
    }

    private void runShellCommand(String command) {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand(command);
        if (descriptor == null) return;
        try {
            descriptor.close();
        } catch (Exception ignored) {
        }
    }
}
