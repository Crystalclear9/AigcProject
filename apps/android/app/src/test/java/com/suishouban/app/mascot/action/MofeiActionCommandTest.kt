package com.suishouban.app.mascot.action

import org.junit.Assert.assertEquals
import org.junit.Test

class MofeiActionCommandTest {
    @Test
    fun everyActionMapsToExactlyOnePlatformNeutralCommand() {
        val mapped = MofeiAction.entries.associateWith { MofeiActionCommand.forAction(it, "card-7") }

        assertEquals(MofeiAction.entries.toSet(), mapped.keys)
        assertEquals(MofeiActionCommand.RequestScreenCapture, mapped[MofeiAction.CAPTURE_CURRENT_SCREEN])
        assertEquals(MofeiActionCommand.OpenLatestScreenshot, mapped[MofeiAction.ANALYZE_LATEST_SCREENSHOT])
        assertEquals(MofeiActionCommand.LaunchPhotoPicker, mapped[MofeiAction.PICK_IMAGE])
        assertEquals(MofeiActionCommand.LaunchCamera, mapped[MofeiAction.TAKE_PHOTO])
        assertEquals(MofeiActionCommand.OpenNotificationDrafts, mapped[MofeiAction.REVIEW_NOTIFICATION_DRAFTS])
        assertEquals(MofeiActionCommand.OpenCard("card-7"), mapped[MofeiAction.OPEN_CURRENT_CARD])
        assertEquals(MofeiActionCommand.OpenSettings, mapped[MofeiAction.OPEN_SETTINGS])
    }
}
