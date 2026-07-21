package com.suishouban.app.mascot.action

/** Business operations that can be invoked from either visible Mofei surface. */
enum class MofeiAction {
    CAPTURE_CURRENT_SCREEN,
    ANALYZE_LATEST_SCREENSHOT,
    PICK_IMAGE,
    TAKE_PHOTO,
    REVIEW_NOTIFICATION_DRAFTS,
    OPEN_CURRENT_CARD,
    OPEN_SETTINGS,
}

/** The in-app pet has more room and platform launchers than the compact system overlay. */
enum class MofeiSurface { IN_APP, OVERLAY }

/** UI-ready availability; Android-specific permission requests remain outside this pure model. */
enum class MofeiActionAvailability { READY, NEEDS_PERMISSION, UNSUPPORTED, BUSY }

data class MofeiCapabilityState(
    val overlayGranted: Boolean = false,
    val notificationAccessGranted: Boolean = false,
    val notificationDraftsEnabled: Boolean = false,
    val screenCaptureSupported: Boolean = true,
    val latestScreenshotAvailable: Boolean = false,
    val pendingNotificationDrafts: Int = 0,
    val busyAction: MofeiAction? = null,
)

data class MofeiActionItem(
    val action: MofeiAction,
    val availability: MofeiActionAvailability,
    val badgeCount: Int = 0,
)
