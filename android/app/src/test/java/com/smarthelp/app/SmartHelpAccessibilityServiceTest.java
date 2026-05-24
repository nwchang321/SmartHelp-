package com.smarthelp.app;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SmartHelpAccessibilityServiceTest {

    @Test
    public void shouldPreferVisionBoundsForGalleryTarget() {
        assertTrue(SmartHelpAccessibilityService.shouldPreferVisionBounds(
                "相册",
                Arrays.asList("gallery", "photo")));
    }

    @Test
    public void shouldPreferVisionBoundsForPhotoThumbnailTarget() {
        assertTrue(SmartHelpAccessibilityService.shouldPreferVisionBounds(
                "Tap a photo thumbnail",
                Arrays.asList("image")));
    }

    @Test
    public void shouldKeepAccessibilityMatchingForContactTargets() {
        assertFalse(SmartHelpAccessibilityService.shouldPreferVisionBounds(
                "Wen (Son)",
                Arrays.asList("contact row", "chat")));
    }
}
