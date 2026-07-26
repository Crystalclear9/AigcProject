package com.suishouban.app.capture

/** Outcome used before touching Android's screenshot API. */
enum class AccessibilityScreenshotRoute {
    CAPTURE,
    SETUP_REQUIRED,
    UNSUPPORTED,
}

/** Keeps the no-MediaProjection fallback rule explicit and JVM-testable. */
object AccessibilityScreenshotPolicy {
    fun route(apiLevel: Int, serviceConnected: Boolean): AccessibilityScreenshotRoute = when {
        apiLevel < 30 -> AccessibilityScreenshotRoute.UNSUPPORTED
        !serviceConnected -> AccessibilityScreenshotRoute.SETUP_REQUIRED
        else -> AccessibilityScreenshotRoute.CAPTURE
    }
}
