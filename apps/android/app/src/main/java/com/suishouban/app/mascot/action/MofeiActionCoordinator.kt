package com.suishouban.app.mascot.action

/**
 * Produces one deterministic action catalog for both Mofei renderers.
 *
 * The coordinator intentionally contains no Android or Compose types, which keeps permission and
 * surface policy JVM-testable while Activities and Services remain responsible for execution.
 */
class MofeiActionCoordinator {
    fun actionsFor(surface: MofeiSurface, state: MofeiCapabilityState): List<MofeiActionItem> {
        val actions = when (surface) {
            MofeiSurface.IN_APP -> IN_APP_ACTIONS
            MofeiSurface.OVERLAY -> OVERLAY_ACTIONS
        }
        return actions.map { action ->
            MofeiActionItem(
                action = action,
                availability = availabilityFor(action, state),
                badgeCount = if (action == MofeiAction.REVIEW_NOTIFICATION_DRAFTS) {
                    state.pendingNotificationDrafts.coerceAtLeast(0)
                } else {
                    0
                },
            )
        }
    }

    private fun availabilityFor(
        action: MofeiAction,
        state: MofeiCapabilityState,
    ): MofeiActionAvailability = when {
        state.busyAction == action -> MofeiActionAvailability.BUSY
        action == MofeiAction.CAPTURE_CURRENT_SCREEN && !state.screenCaptureSupported ->
            MofeiActionAvailability.UNSUPPORTED
        action == MofeiAction.REVIEW_NOTIFICATION_DRAFTS &&
            (!state.notificationDraftsEnabled || !state.notificationAccessGranted) ->
            MofeiActionAvailability.NEEDS_PERMISSION
        else -> MofeiActionAvailability.READY
    }

    private companion object {
        val IN_APP_ACTIONS = listOf(
            MofeiAction.CAPTURE_CURRENT_SCREEN,
            MofeiAction.ANALYZE_LATEST_SCREENSHOT,
            MofeiAction.PICK_IMAGE,
            MofeiAction.TAKE_PHOTO,
            MofeiAction.REVIEW_NOTIFICATION_DRAFTS,
            MofeiAction.OPEN_CURRENT_CARD,
            MofeiAction.OPEN_SETTINGS,
        )
        val OVERLAY_ACTIONS = listOf(
            MofeiAction.CAPTURE_CURRENT_SCREEN,
            MofeiAction.REVIEW_NOTIFICATION_DRAFTS,
            MofeiAction.OPEN_CURRENT_CARD,
        )
    }
}
