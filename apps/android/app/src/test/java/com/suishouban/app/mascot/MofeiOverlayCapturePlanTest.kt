package com.suishouban.app.mascot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MofeiOverlayCapturePlanTest {
    @Test
    fun connectedSupportedDeviceHidesOverlayAndCapturesThroughAccessibility() {
        val plan = MofeiOverlayCapturePlan.begin(apiLevel = 35, accessibilityConnected = true)

        assertTrue(plan.removeOverlay)
        assertEquals(MofeiOverlayCaptureStart.CAPTURE_ACCESSIBILITY, plan.start)
    }

    @Test
    fun missingServiceOpensAccessibilitySetupWithoutProjectionFallback() {
        val plan = MofeiOverlayCapturePlan.begin(apiLevel = 35, accessibilityConnected = false)

        assertTrue(plan.removeOverlay)
        assertEquals(MofeiOverlayCaptureStart.OPEN_ACCESSIBILITY_SETUP, plan.start)
    }

    @Test
    fun unsupportedDeviceReportsUnsupportedWithoutProjectionFallback() {
        val plan = MofeiOverlayCapturePlan.begin(apiLevel = 29, accessibilityConnected = false)

        assertEquals(false, plan.removeOverlay)
        assertEquals(MofeiOverlayCaptureStart.SHOW_UNSUPPORTED, plan.start)
    }

    @Test
    fun captureFailureRestoresOverlayWhileSuccessDelegatesRestoreToPreview() {
        assertEquals(
            MofeiOverlayCaptureFinish.OPEN_PREVIEW,
            MofeiOverlayCapturePlan.finish(success = true),
        )
        assertEquals(
            MofeiOverlayCaptureFinish.RESTORE_AND_REPORT_ERROR,
            MofeiOverlayCapturePlan.finish(success = false),
        )
    }
}
