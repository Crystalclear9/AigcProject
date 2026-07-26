package com.suishouban.app.mascot

import com.suishouban.app.capture.AccessibilityScreenshotPolicy
import com.suishouban.app.capture.AccessibilityScreenshotRoute

enum class MofeiOverlayCaptureStart {
    CAPTURE_ACCESSIBILITY,
    OPEN_ACCESSIBILITY_SETUP,
    SHOW_UNSUPPORTED,
}

enum class MofeiOverlayCaptureFinish {
    OPEN_PREVIEW,
    RESTORE_AND_REPORT_ERROR,
}

data class MofeiOverlayCaptureStartPlan(
    val removeOverlay: Boolean,
    val start: MofeiOverlayCaptureStart,
)

/** Pure lifecycle policy used to keep external capture off the MediaProjection route. */
object MofeiOverlayCapturePlan {
    fun begin(apiLevel: Int, accessibilityConnected: Boolean): MofeiOverlayCaptureStartPlan =
        when (AccessibilityScreenshotPolicy.route(apiLevel, accessibilityConnected)) {
            AccessibilityScreenshotRoute.CAPTURE -> MofeiOverlayCaptureStartPlan(
                removeOverlay = true,
                start = MofeiOverlayCaptureStart.CAPTURE_ACCESSIBILITY,
            )
            AccessibilityScreenshotRoute.SETUP_REQUIRED -> MofeiOverlayCaptureStartPlan(
                removeOverlay = true,
                start = MofeiOverlayCaptureStart.OPEN_ACCESSIBILITY_SETUP,
            )
            AccessibilityScreenshotRoute.UNSUPPORTED -> MofeiOverlayCaptureStartPlan(
                removeOverlay = false,
                start = MofeiOverlayCaptureStart.SHOW_UNSUPPORTED,
            )
        }

    fun finish(success: Boolean): MofeiOverlayCaptureFinish =
        if (success) {
            MofeiOverlayCaptureFinish.OPEN_PREVIEW
        } else {
            MofeiOverlayCaptureFinish.RESTORE_AND_REPORT_ERROR
        }
}
