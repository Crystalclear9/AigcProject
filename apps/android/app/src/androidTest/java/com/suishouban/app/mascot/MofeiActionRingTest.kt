package com.suishouban.app.mascot

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.suishouban.app.mascot.action.MofeiAction
import com.suishouban.app.mascot.action.MofeiActionAvailability
import com.suishouban.app.mascot.action.MofeiActionItem
import com.suishouban.app.mascot.action.MofeiSurface
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MofeiActionRingTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun expandedRingExposesActionSemanticsBadgeAndCallback() {
        var selected: MofeiAction? = null
        val items = MofeiAction.entries.map {
            MofeiActionItem(
                action = it,
                availability = if (it == MofeiAction.REVIEW_NOTIFICATION_DRAFTS) {
                    MofeiActionAvailability.NEEDS_PERMISSION
                } else {
                    MofeiActionAvailability.READY
                },
                badgeCount = if (it == MofeiAction.REVIEW_NOTIFICATION_DRAFTS) 3 else 0,
            )
        }

        compose.setContent {
            MofeiActionRing(
                surface = MofeiSurface.IN_APP,
                items = items,
                expanded = true,
                reduceMotion = true,
                onAction = { selected = it },
                onDismiss = {},
            )
        }

        compose.onNodeWithTag("mofei-action-ring").assertExists()
        compose.onAllNodes(hasTestTag("mofei-action-item"), useUnmergedTree = true)
            .assertCountEquals(MofeiAction.entries.size)
        compose.onNodeWithText("3").assertExists()
        compose.onNodeWithText("需要通知读取权限").assertExists()
        compose.onNodeWithTag("mofei-action-take-photo", useUnmergedTree = true).performClick()
        assertEquals(MofeiAction.TAKE_PHOTO, selected)
    }

    @Test
    fun collapsedRingInvokesDismissFromCenterSeal() {
        var dismissed = false
        compose.setContent {
            MofeiActionRing(
                surface = MofeiSurface.OVERLAY,
                items = emptyList(),
                expanded = false,
                reduceMotion = true,
                onAction = {},
                onDismiss = { dismissed = true },
            )
        }

        compose.onNodeWithTag("mofei-action-dismiss").performClick()
        assertEquals(true, dismissed)
    }
}
