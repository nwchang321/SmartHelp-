package com.smarthelp.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OverlayDemoLaunchTest {

    @Test
    public void whatsappFamilyTask_defersLaunchUntilClarification() {
        String query = "Send a WhatsApp message to my daughter";

        assertFalse(OverlayService.shouldAutoLaunchWhatsAppForDemo(query));
        assertTrue(OverlayService.shouldDeferWhatsAppLaunchForClarification(query));
    }

    @Test
    public void whatsappConcreteTask_launchesForDemo() {
        String query = "Send a WhatsApp message to Alice";

        assertTrue(OverlayService.shouldAutoLaunchWhatsAppForDemo(query));
        assertFalse(OverlayService.shouldDeferWhatsAppLaunchForClarification(query));
    }

    @Test
    public void nonWhatsappOrExplicitNegative_doesNotLaunch() {
        assertFalse(OverlayService.shouldAutoLaunchWhatsAppForDemo("Open the camera"));
        assertFalse(OverlayService.shouldAutoLaunchWhatsAppForDemo("Use the phone dialer to call my son, not WhatsApp"));
    }

    @Test
    public void taskActionButtons_showOnlyDuringActiveIncompleteTask() {
        assertTrue(OverlayService.shouldShowTaskActionButtons(false, true));
        assertFalse(OverlayService.shouldShowTaskActionButtons(true, true));
        assertFalse(OverlayService.shouldShowTaskActionButtons(false, false));
    }
}
