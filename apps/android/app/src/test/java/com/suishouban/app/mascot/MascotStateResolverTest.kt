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
            cards = listOf(card(id = "draft", status = CardStatus.DRAFT)),
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
    fun completedCardProducesCompletionFeedbackBeforeReturningToIdle() {
        val state = resolver.resolve(
            cards = listOf(card(id = "done", status = CardStatus.DONE)),
            workflowStatus = "completed",
        )

        assertEquals(MascotMood.COMPLETE, state.mood)
        assertEquals("done", state.actionCardId)
        assertEquals(MascotColorRole.SUCCESS, state.colorRole)
        assertEquals(MascotAnimationHint.CELEBRATE, state.animationHint)
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
    ) = ActionCard(
        id = id,
        title = "$id card",
        deadline = deadline,
        priority = Priority.HIGH,
        status = status,
    )
}
