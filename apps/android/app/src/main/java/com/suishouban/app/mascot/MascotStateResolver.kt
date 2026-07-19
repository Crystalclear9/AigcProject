package com.suishouban.app.mascot

import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime

class MascotStateResolver(
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun resolve(cards: List<ActionCard>, workflowStatus: String?): MascotState {
        val now = clock.instant()
        val openCards = cards.filter { it.status !in setOf(CardStatus.DONE, CardStatus.ARCHIVED) }
        val datedCards = openCards.mapNotNull { card ->
            parseDeadline(card.deadline)?.let { deadline -> TimedCard(card, deadline) }
        }
        val closestDeadline = datedCards.minByOrNull { it.deadline }

        if (closestDeadline != null && !closestDeadline.deadline.isAfter(now.plus(URGENT_WINDOW))) {
            return state(
                mood = MascotMood.URGENT,
                card = closestDeadline.card,
                message = "${closestDeadline.card.title} 即将到期",
                color = MascotColorRole.URGENT,
                animation = MascotAnimationHint.ALERT_PULSE,
            )
        }
        if (closestDeadline != null && !closestDeadline.deadline.isAfter(now.plus(DUE_SOON_WINDOW))) {
            return state(
                mood = MascotMood.DUE_SOON,
                card = closestDeadline.card,
                message = "${closestDeadline.card.title} 将在 24 小时内到期",
                color = MascotColorRole.WARNING,
                animation = MascotAnimationHint.WARNING_PULSE,
            )
        }

        openCards.firstOrNull { it.status == CardStatus.DRAFT }?.let { draft ->
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
        cards.firstOrNull { it.status == CardStatus.DONE }?.let { completed ->
            return state(
                mood = MascotMood.COMPLETE,
                card = completed,
                message = "${completed.title} 已完成",
                color = MascotColorRole.SUCCESS,
                animation = MascotAnimationHint.CELEBRATE,
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

    // Deadlines arrive from local and remote sources in several ISO-8601 variants.
    private fun parseDeadline(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value).atZone(clock.zone).toInstant() }.getOrNull()
    }

    private data class TimedCard(val card: ActionCard, val deadline: Instant)

    private companion object {
        val URGENT_WINDOW = java.time.Duration.ofHours(3)
        val DUE_SOON_WINDOW = java.time.Duration.ofHours(24)
        val ACTIVE_WORKFLOW_STATUSES = setOf("queued", "running", "analyzing", "reviewing", "awaiting_review")
        val UNAVAILABLE_WORKFLOW_STATUSES = setOf("failed", "unavailable", "cancelled")
        val REST_WORKFLOW_STATUSES = setOf("paused", "suspended")
    }
}
