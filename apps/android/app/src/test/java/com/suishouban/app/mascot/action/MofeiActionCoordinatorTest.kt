package com.suishouban.app.mascot.action

import org.junit.Assert.assertEquals
import org.junit.Test

class MofeiActionCoordinatorTest {
    private val coordinator = MofeiActionCoordinator()

    @Test
    fun inAppShowsTheCompleteActionSetInStableOrder() {
        val actions = coordinator.actionsFor(
            surface = MofeiSurface.IN_APP,
            state = MofeiCapabilityState(
                overlayGranted = true,
                notificationAccessGranted = true,
                notificationDraftsEnabled = true,
                screenCaptureSupported = true,
                latestScreenshotAvailable = true,
            ),
        )

        assertEquals(
            listOf(
                MofeiAction.CAPTURE_CURRENT_SCREEN,
                MofeiAction.ANALYZE_LATEST_SCREENSHOT,
                MofeiAction.PICK_IMAGE,
                MofeiAction.TAKE_PHOTO,
                MofeiAction.REVIEW_NOTIFICATION_DRAFTS,
                MofeiAction.OPEN_CURRENT_CARD,
                MofeiAction.OPEN_SETTINGS,
            ),
            actions.map { it.action },
        )
    }

    @Test
    fun overlayOmitsMediaPickerAndCameraActions() {
        val actions = coordinator.actionsFor(
            surface = MofeiSurface.OVERLAY,
            state = MofeiCapabilityState(
                overlayGranted = true,
                notificationAccessGranted = true,
                notificationDraftsEnabled = true,
                screenCaptureSupported = true,
                pendingNotificationDrafts = 2,
            ),
        )

        assertEquals(
            listOf(
                MofeiAction.CAPTURE_CURRENT_SCREEN,
                MofeiAction.ANALYZE_LATEST_SCREENSHOT,
                MofeiAction.REVIEW_NOTIFICATION_DRAFTS,
                MofeiAction.OPEN_CURRENT_CARD,
                MofeiAction.OPEN_SETTINGS,
            ),
            actions.map { it.action },
        )
    }

    @Test
    fun notificationActionIsSealedUntilFeatureAndSpecialAccessAreEnabled() {
        val disabled = coordinator.actionsFor(
            MofeiSurface.IN_APP,
            MofeiCapabilityState(notificationAccessGranted = true, notificationDraftsEnabled = false),
        ).single { it.action == MofeiAction.REVIEW_NOTIFICATION_DRAFTS }
        val notGranted = coordinator.actionsFor(
            MofeiSurface.IN_APP,
            MofeiCapabilityState(notificationAccessGranted = false, notificationDraftsEnabled = true),
        ).single { it.action == MofeiAction.REVIEW_NOTIFICATION_DRAFTS }

        assertEquals(MofeiActionAvailability.NEEDS_PERMISSION, disabled.availability)
        assertEquals(MofeiActionAvailability.NEEDS_PERMISSION, notGranted.availability)
    }

    @Test
    fun unsupportedScreenCaptureAndBusyActionsHaveExplicitStates() {
        val state = MofeiCapabilityState(
            screenCaptureSupported = false,
            busyAction = MofeiAction.TAKE_PHOTO,
        )
        val actions = coordinator.actionsFor(MofeiSurface.IN_APP, state).associateBy { it.action }

        assertEquals(
            MofeiActionAvailability.UNSUPPORTED,
            actions.getValue(MofeiAction.CAPTURE_CURRENT_SCREEN).availability,
        )
        assertEquals(
            MofeiActionAvailability.BUSY,
            actions.getValue(MofeiAction.TAKE_PHOTO).availability,
        )
    }

    @Test
    fun notificationBadgeIsClampedToNonNegativeCount() {
        val positive = coordinator.actionsFor(
            MofeiSurface.OVERLAY,
            MofeiCapabilityState(pendingNotificationDrafts = 4),
        ).single { it.action == MofeiAction.REVIEW_NOTIFICATION_DRAFTS }
        val negative = coordinator.actionsFor(
            MofeiSurface.OVERLAY,
            MofeiCapabilityState(pendingNotificationDrafts = -1),
        ).single { it.action == MofeiAction.REVIEW_NOTIFICATION_DRAFTS }

        assertEquals(4, positive.badgeCount)
        assertEquals(0, negative.badgeCount)
    }
}
