package com.suishouban.app.mascot

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.suishouban.app.notification.NotificationCandidateUiModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MofeiNotificationFirefliesTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun fireflyShowsSourceAndInvokesOnlyReviewOrReject() {
        var opened: String? = null
        var rejected: String? = null
        val candidates = listOf(
            NotificationCandidateUiModel("new", "课程群", "周五前交实验报告", 2_000L),
            NotificationCandidateUiModel("old", "邮箱", "下午三点参加评审", 1_000L),
        )
        compose.setContent {
            MofeiNotificationFireflies(
                candidates = candidates,
                onOpen = { opened = it },
                onReject = { rejected = it },
            )
        }

        compose.onNodeWithText("课程群").assertExists()
        compose.onNodeWithTag("mofei-firefly-new").performClick()
        assertEquals("new", opened)
        compose.onNodeWithTag("mofei-firefly-reject-new").performClick()
        assertEquals("new", rejected)
    }
}
