package com.suishouban.app.capture

/** Outcome used before touching Android's screenshot API. */
enum class AccessibilityScreenshotRoute {
    CAPTURE,
    SETUP_REQUIRED,
    UNSUPPORTED,
}

enum class MofeiAccessibilityConnectionState {
    CONNECTED,
    CONFIGURED_NOT_CONNECTED,
    NOT_CONFIGURED,
}

/** Keeps the static accessibility screenshot route explicit and JVM-testable. */
object AccessibilityScreenshotPolicy {
    fun route(apiLevel: Int, serviceConnected: Boolean): AccessibilityScreenshotRoute = when {
        apiLevel < 30 -> AccessibilityScreenshotRoute.UNSUPPORTED
        !serviceConnected -> AccessibilityScreenshotRoute.SETUP_REQUIRED
        else -> AccessibilityScreenshotRoute.CAPTURE
    }

    fun connectionState(
        serviceConnected: Boolean,
        accessibilityMasterEnabled: Boolean,
        serviceListedAsEnabled: Boolean,
    ): MofeiAccessibilityConnectionState = when {
        serviceConnected -> MofeiAccessibilityConnectionState.CONNECTED
        accessibilityMasterEnabled && serviceListedAsEnabled ->
            MofeiAccessibilityConnectionState.CONFIGURED_NOT_CONNECTED
        serviceListedAsEnabled -> MofeiAccessibilityConnectionState.CONFIGURED_NOT_CONNECTED
        else -> MofeiAccessibilityConnectionState.NOT_CONFIGURED
    }
}
