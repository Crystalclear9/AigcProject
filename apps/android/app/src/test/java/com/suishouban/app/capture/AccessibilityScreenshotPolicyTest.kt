package com.suishouban.app.capture

import com.suishouban.app.domain.screenshot.ScreenshotCaptureSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccessibilityScreenshotPolicyTest {
    @Test
    fun apiBelowThirtyIsUnsupportedWithoutFallback() {
        assertEquals(
            AccessibilityScreenshotRoute.UNSUPPORTED,
            AccessibilityScreenshotPolicy.route(apiLevel = 29, serviceConnected = true),
        )
    }

    @Test
    fun connectedServiceOnSupportedApiCapturesDirectly() {
        assertEquals(
            AccessibilityScreenshotRoute.CAPTURE,
            AccessibilityScreenshotPolicy.route(apiLevel = 30, serviceConnected = true),
        )
    }

    @Test
    fun disconnectedServiceOnSupportedApiRequiresSetup() {
        assertEquals(
            AccessibilityScreenshotRoute.SETUP_REQUIRED,
            AccessibilityScreenshotPolicy.route(apiLevel = 35, serviceConnected = false),
        )
    }

    @Test
    fun accessibilityCaptureHasItsOwnFingerprintSource() {
        assertNotEquals(
            ScreenshotCaptureSource.MEDIA_PROJECTION,
            ScreenshotCaptureSource.ACCESSIBILITY,
        )
    }
}
