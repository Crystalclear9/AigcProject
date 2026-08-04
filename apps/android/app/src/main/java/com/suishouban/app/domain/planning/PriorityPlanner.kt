package com.suishouban.app.domain.planning

import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardStatus
import com.suishouban.app.data.model.Priority
import com.suishouban.app.data.model.PriorityModes
import com.suishouban.app.data.model.WorkspaceTypes
import java.time.Duration
import java.time.OffsetDateTime

/** Deterministic offline calibration that never overrides a manual lock. */
object PriorityPlanner {
    fun calibrate(card: ActionCard, now: OffsetDateTime = OffsetDateTime.now()): ActionCard {
        if (card.priorityMode == PriorityModes.MANUAL || card.priorityLocked) {
            return card.copy(
                priorityMode = PriorityModes.MANUAL,
                priorityLocked = true,
                priorityScore = scoreForManual(card.priority),
                priorityReason = "由你手动设置",
                priorityUpdatedAt = now.toString(),
            )
        }

        var score = 30.0
        val reasons = mutableListOf<String>()
        val due = parseTime(card.deadline ?: card.startTime)
        if (due != null) {
            val hours = Duration.between(now, due).toHours()
            when {
                hours < 0 -> {
                    score += 45
                    reasons += "已经到期"
                }
                hours <= 6 -> {
                    score += 42
                    reasons += "6 小时内到期"
                }
                hours <= 24 -> {
                    score += 34
                    reasons += "今天需要处理"
                }
                hours <= 72 -> {
                    score += 24
                    reasons += "3 天内到期"
                }
                hours <= 168 -> {
                    score += 14
                    reasons += "一周内到期"
                }
            }
        } else {
            reasons += "尚未设置时间"
        }
        if (card.dependencies.isNotEmpty()) {
            score += 10
            reasons += "存在前置依赖"
        }
        if (card.workspaceType == WorkspaceTypes.TEAM) {
            score += 8
            reasons += "会影响团队协作"
        }
        if (card.deliverables.isNotEmpty()) {
            score += 6
            reasons += "包含明确交付物"
        }
        if (card.status == CardStatus.DONE || card.status == CardStatus.ARCHIVED) score = 0.0
        score = score.coerceIn(0.0, 100.0)
        return card.copy(
            priority = when {
                score >= 70 -> Priority.HIGH
                score < 38 -> Priority.LOW
                else -> Priority.NORMAL
            },
            priorityMode = PriorityModes.ADAPTIVE,
            priorityScore = score,
            priorityReason = reasons.take(3).joinToString("，"),
            priorityUpdatedAt = now.toString(),
            priorityLocked = false,
        )
    }

    private fun parseTime(value: String?): OffsetDateTime? =
        value?.takeIf(String::isNotBlank)?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

    private fun scoreForManual(priority: String): Double = when (priority) {
        Priority.HIGH -> 85.0
        Priority.LOW -> 20.0
        else -> 50.0
    }
}
