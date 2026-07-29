package com.suishouban.app.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class OnboardingRepositoryTest {
    private val coreAnswers = mapOf(
        "scenario" to "study",
        "active_period" to "evening",
        "planning_granularity" to "detailed",
        "reminder_style" to "standard",
    )

    @Test
    fun offlineQuestionnaireReturnsStructuredFollowupsAndStopsAtThree() = runBlocking {
        val repository = OnboardingRepository()
        val settings = AppSettings(apiBaseUrl = "", preferCloudModel = false)

        val first = repository.requestTurn(
            settings,
            "session-123456",
            "followup",
            "reminder_style",
            coreAnswers,
            emptyList(),
        )
        assertEquals("work_rhythm", first.nextQuestion?.topic)
        assertNotNull(first.nextQuestion)
        assertFalse(first.nextQuestion!!.choices.isEmpty())

        val complete = repository.requestTurn(
            settings,
            "session-123456",
            "followup",
            "weekend_policy",
            coreAnswers + mapOf(
                "work_rhythm" to "steady",
                "buffer_preference" to "standard",
                "weekend_policy" to "avoid",
            ),
            listOf("work_rhythm", "buffer_preference", "weekend_policy"),
        )
        assertEquals(null, complete.nextQuestion)
        assertEquals(true, complete.complete)
    }

    @Test
    fun unsafeRemoteCopyFallsBackToReviewedUserLanguage() {
        assertEquals(
            "好，按你的选择来。",
            OnboardingRepository.safeAssistantMessage(
                "AI 已经智能理解你的画像 🤖",
                "好，按你的选择来。",
            ),
        )
    }
}
