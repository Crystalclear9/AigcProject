package com.suishouban.app.mascot.action

/** Platform-neutral commands keep the action catalog independent from Activity launchers. */
sealed interface MofeiActionCommand {
    data object LaunchPhotoPicker : MofeiActionCommand
    data object LaunchCamera : MofeiActionCommand
    data object RequestScreenCapture : MofeiActionCommand
    data object OpenLatestScreenshot : MofeiActionCommand
    data object OpenNotificationDrafts : MofeiActionCommand
    data class OpenCard(val cardId: String?) : MofeiActionCommand
    data object OpenSettings : MofeiActionCommand

    companion object {
        fun forAction(action: MofeiAction, cardId: String?): MofeiActionCommand = when (action) {
            MofeiAction.CAPTURE_CURRENT_SCREEN -> RequestScreenCapture
            MofeiAction.ANALYZE_LATEST_SCREENSHOT -> OpenLatestScreenshot
            MofeiAction.PICK_IMAGE -> LaunchPhotoPicker
            MofeiAction.TAKE_PHOTO -> LaunchCamera
            MofeiAction.REVIEW_NOTIFICATION_DRAFTS -> OpenNotificationDrafts
            MofeiAction.OPEN_CURRENT_CARD -> OpenCard(cardId)
            MofeiAction.OPEN_SETTINGS -> OpenSettings
        }
    }
}
