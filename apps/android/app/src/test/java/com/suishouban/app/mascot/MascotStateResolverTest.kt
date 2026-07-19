package com.suishouban.app.mascot

import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardStatus
import com.suishouban.app.data.model.Priority
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MascotStateResolverTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-19T08:00:00Z"), ZoneOffset.UTC)
    private val resolver = MascotStateResolver(clock)

    @Test
    fun urgentDeadlineWinsOverEveryOtherSignal() {
        val state = resolver.resolve(
            cards = listOf(
                card(id = "urgent", deadline = "2026-07-19T07:59:00Z"),
                card(id = "draft", status = CardStatus.DRAFT),
            ),
            workflowStatus = "running",
        )

        assertEquals(MascotMood.URGENT, state.mood)
        assertEquals("urgent", state.actionCardId)
        assertEquals(MascotColorRole.URGENT, state.colorRole)
        assertEquals(MascotAnimationHint.ALERT_PULSE, state.animationHint)
    }

    @Test
    fun deadlineWithinTwentyFourHoursIsDueSoon() {
        val state = resolver.resolve(
            cards = listOf(card(id = "soon", deadline = "2026-07-20T07:59:00Z")),
            workflowStatus = null,
        )

        assertEquals(MascotMood.DUE_SOON, state.mood)
        assertEquals("soon", state.actionCardId)
        assertEquals(MascotColorRole.WARNING, state.colorRole)
    }

    @Test
    fun draftConfirmationWinsOverActiveWorkflow() {
        val state = resolver.resolve(
            cards = emptyList(),
            draftCards = listOf(card(id = "draft", status = CardStatus.DRAFT)),
            workflowStatus = "awaiting_review",
        )

        assertEquals(MascotMood.CONFIRM, state.mood)
        assertEquals("draft", state.actionCardId)
        assertEquals(MascotAnimationHint.PEEK, state.animationHint)
    }

    @Test
    fun activeWorkflowUsesFocusMoodWhenNoCardNeedsAttention() {
        val state = resolver.resolve(emptyList(), workflowStatus = "running")

        assertEquals(MascotMood.FOCUS, state.mood)
        assertNull(state.actionCardId)
        assertEquals(MascotColorRole.FOCUS, state.colorRole)
        assertEquals(MascotAnimationHint.SCAN, state.animationHint)
    }

    @Test
    fun persistedCompletedCardDoesNotKeepMascotInCompletionMood() {
        val state = resolver.resolve(
            cards = listOf(card(id = "done", status = CardStatus.DONE)),
            workflowStatus = "completed",
        )

        assertEquals(MascotMood.IDLE, state.mood)
        assertNull(state.actionCardId)
    }

    @Test
    fun recentCompletionEventProducesTemporaryCompletionFeedback() {
        val state = resolver.resolve(
            cards = listOf(card(id = "done", status = CardStatus.DONE)),
            workflowStatus = "completed",
            completionEvent = MascotCompletionEvent(
                actionCardId = "done",
                occurredAt = Instant.parse("2026-07-19T07:59:55Z"),
            ),
        )

        assertEquals(MascotMood.COMPLETE, state.mood)
        assertEquals("done", state.actionCardId)
        assertEquals("done card 已完成", state.userMessage)
        assertEquals(MascotColorRole.SUCCESS, state.colorRole)
        assertEquals(MascotAnimationHint.CELEBRATE, state.animationHint)
    }

    @Test
    fun expiredCompletionEventReturnsToIdle() {
        val state = resolver.resolve(
            cards = emptyList(),
            workflowStatus = "completed",
            completionEvent = MascotCompletionEvent(
                occurredAt = Instant.parse("2026-07-19T07:59:30Z"),
            ),
        )

        assertEquals(MascotMood.IDLE, state.mood)
    }

    @Test
    fun overdueDeadlineUsesUrgentMoodAndOverdueMessage() {
        val state = resolver.resolve(
            cards = listOf(card(id = "late", deadline = "2026-07-19T07:59:00Z")),
            workflowStatus = null,
        )

        assertEquals(MascotMood.URGENT, state.mood)
        assertEquals("late card 已逾期", state.userMessage)
    }

    @Test
    fun exactThreeHourDeadlineRemainsUrgent() {
        val state = resolver.resolve(
            cards = listOf(card(id = "three-hours", deadline = "2026-07-19T11:00:00Z")),
            workflowStatus = null,
        )

        assertEquals(MascotMood.URGENT, state.mood)
        assertEquals("three-hours card 将在 3 小时内到期", state.userMessage)
    }

    @Test
    fun exactTwentyFourHourDeadlineIsDueSoon() {
        val state = resolver.resolve(
            cards = listOf(card(id = "tomorrow", deadline = "2026-07-20T08:00:00Z")),
            workflowStatus = null,
        )

        assertEquals(MascotMood.DUE_SOON, state.mood)
        assertEquals("tomorrow card 将在 24 小时内到期", state.userMessage)
    }

    @Test
    fun deadlineTieUsesPriorityThenDeadlineThenId() {
        val state = resolver.resolve(
            cards = listOf(
                card(id = "normal-earlier", deadline = "2026-07-19T08:30:00Z", priority = Priority.NORMAL),
                card(id = "high-later", deadline = "2026-07-19T09:00:00Z", priority = Priority.HIGH),
                card(id = "high-earlier-b", deadline = "2026-07-19T08:45:00Z", priority = Priority.HIGH),
                card(id = "high-earlier-a", deadline = "2026-07-19T08:45:00Z", priority = Priority.HIGH),
            ),
            workflowStatus = null,
        )

        assertEquals("high-earlier-a", state.actionCardId)
    }

    @Test
    fun malformedAndNoZoneDeadlinesDoNotCreateAlerts() {
        val state = resolver.resolve(
            cards = listOf(
                card(id = "malformed", deadline = "tomorrow morning"),
                card(id = "local", deadline = "2026-07-19T09:00:00"),
            ),
            workflowStatus = null,
        )

        assertEquals(MascotMood.IDLE, state.mood)
        assertNull(state.actionCardId)
    }

    @Test
    fun noActionableSignalLeavesMascotIdle() {
        val state = resolver.resolve(emptyList(), workflowStatus = null)

        assertEquals(MascotMood.IDLE, state.mood)
        assertNull(state.actionCardId)
        assertEquals(MascotColorRole.DEFAULT, state.colorRole)
        assertEquals(MascotAnimationHint.BREATHE, state.animationHint)
    }

    private fun card(
        id: String,
        deadline: String? = null,
        status: String = CardStatus.CONFIRMED,
        priority: String = Priority.HIGH,
    ) = ActionCard(
        id = id,
        title = "$id card",
        deadline = deadline,
        priority = priority,
        status = status,
    )
}
