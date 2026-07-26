package com.suishouban.app.mascot

import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardStatus
import com.suishouban.app.data.model.Priority
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime

class MascotStateResolver(
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun resolve(
        cards: List<ActionCard>,
        workflowStatus: String?,
        draftCards: List<ActionCard> = emptyList(),
        completionEvent: MascotCompletionEvent? = null,
    ): MascotState {
        val now = clock.instant()
        val openCards = cards.filter { it.status !in setOf(CardStatus.DONE, CardStatus.ARCHIVED) }
        val datedCards = openCards.mapNotNull { card ->
            // Expired cards stay in storage, but no longer hold the mascot in an alert state.
            // A deadline exactly at `now` remains eligible until the next state refresh.
            parseDeadline(card.deadline)
                ?.takeUnless { deadline -> deadline.isBefore(now) }
                ?.let { deadline -> TimedCard(card, deadline) }
        }
        val urgentDeadline = selectDeadline(
            datedCards.filter { !it.deadline.isAfter(now.plus(URGENT_WINDOW)) },
        )
        val dueSoonDeadline = selectDeadline(
            datedCards.filter {
                it.deadline.isAfter(now.plus(URGENT_WINDOW)) &&
                    !it.deadline.isAfter(now.plus(DUE_SOON_WINDOW))
            },
        )

        if (urgentDeadline != null) {
            return state(
                mood = MascotMood.URGENT,
                card = urgentDeadline.card,
                message = if (urgentDeadline.deadline.isBefore(now)) {
                    "${urgentDeadline.card.title} 已逾期"
                } else {
                    "${urgentDeadline.card.title} 将在 3 小时内到期"
                },
                color = MascotColorRole.URGENT,
                animation = MascotAnimationHint.ALERT_PULSE,
            )
        }
        if (dueSoonDeadline != null) {
            return state(
                mood = MascotMood.DUE_SOON,
                card = dueSoonDeadline.card,
                message = "${dueSoonDeadline.card.title} 将在 24 小时内到期",
                color = MascotColorRole.WARNING,
                animation = MascotAnimationHint.WARNING_PULSE,
            )
        }
        // A dated open card outside alert windows is still actionable. Keep a deterministic
        // target so the compact overlay can open the same card on every refresh.
        selectDeadline(datedCards)?.let { reminder ->
            return state(
                mood = MascotMood.REMINDER,
                card = reminder.card,
                message = "${reminder.card.title} 有待处理事项",
                color = MascotColorRole.REMINDER,
                animation = MascotAnimationHint.NUDGE,
            )
        }

        draftCards.firstOrNull { it.status !in setOf(CardStatus.DONE, CardStatus.ARCHIVED) }?.let { draft ->
            return state(
                mood = MascotMood.CONFIRM,
                card = draft,
                message = "${draft.title} 等待确认",
                color = MascotColorRole.CONFIRM,
                animation = MascotAnimationHint.PEEK,
            )
        }
        if (workflowStatus in ACTIVE_WORKFLOW_STATUSES) {
            return MascotState(
                mood = MascotMood.FOCUS,
                userMessage = "正在识别行动事项",
                colorRole = MascotColorRole.FOCUS,
                animationHint = MascotAnimationHint.SCAN,
            )
        }
        completionEvent?.takeIf { it.isActiveAt(now) }?.let { event ->
            val completedCard = event.actionCardId?.let { id -> cards.firstOrNull { it.id == id } }
            return MascotState(
                mood = MascotMood.COMPLETE,
                actionCardId = event.actionCardId,
                userMessage = event.message
                    ?: completedCard?.let { "${it.title} 已完成" }
                    ?: "任务已完成",
                colorRole = MascotColorRole.SUCCESS,
                animationHint = MascotAnimationHint.CELEBRATE,
            )
        }
        if (workflowStatus in UNAVAILABLE_WORKFLOW_STATUSES) {
            return MascotState(
                mood = MascotMood.UNAVAILABLE,
                userMessage = "识别服务暂不可用",
                colorRole = MascotColorRole.MUTED,
                animationHint = MascotAnimationHint.DIM,
            )
        }
        if (workflowStatus in REST_WORKFLOW_STATUSES) {
            return MascotState(
                mood = MascotMood.REST,
                userMessage = "墨斐正在安静待命",
                colorRole = MascotColorRole.REST,
                animationHint = MascotAnimationHint.SETTLE,
            )
        }
        return MascotState(
            mood = MascotMood.IDLE,
            userMessage = "墨斐正在待命",
            colorRole = MascotColorRole.DEFAULT,
            animationHint = MascotAnimationHint.BREATHE,
        )
    }

    private fun state(
        mood: MascotMood,
        card: ActionCard,
        message: String,
        color: MascotColorRole,
        animation: MascotAnimationHint,
    ) = MascotState(
        mood = mood,
        actionCardId = card.id,
        userMessage = message,
        colorRole = color,
        animationHint = animation,
    )

    // Only zone-qualified remote/local timestamps can drive alerts consistently across devices.
    private fun parseDeadline(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(value).toInstant() }.getOrNull()
    }

    private fun priorityRank(priority: String): Int = when (priority) {
        Priority.HIGH -> 0
        Priority.NORMAL -> 1
        Priority.LOW -> 2
        else -> 3
    }

    private fun MascotCompletionEvent.isActiveAt(now: Instant): Boolean =
        !occurredAt.isAfter(now) && !occurredAt.isBefore(now.minus(COMPLETE_WINDOW))

    // Priority resolves competing cards only after urgency has constrained the candidate set.
    private fun selectDeadline(candidates: List<TimedCard>): TimedCard? = candidates.minWithOrNull(
        compareBy<TimedCard> { priorityRank(it.card.priority) }
            .thenBy { it.deadline }
            .thenBy { it.card.id },
    )

    private data class TimedCard(val card: ActionCard, val deadline: Instant)

    private companion object {
        val URGENT_WINDOW = java.time.Duration.ofHours(3)
        val DUE_SOON_WINDOW = java.time.Duration.ofHours(24)
        val COMPLETE_WINDOW = java.time.Duration.ofSeconds(15)
        val ACTIVE_WORKFLOW_STATUSES = setOf("queued", "running", "analyzing", "reviewing", "awaiting_review")
        val UNAVAILABLE_WORKFLOW_STATUSES = setOf("failed", "unavailable", "cancelled")
        val REST_WORKFLOW_STATUSES = setOf("paused", "suspended")
    }
}
