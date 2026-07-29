package com.suishouban.app.data.model

import java.time.OffsetDateTime
import java.util.UUID

object TemporalKinds {
    const val START = "start"
    const val END = "end"
    const val DEADLINE = "deadline"
    const val MILESTONE = "milestone"
    const val WORK_BLOCK = "work_block"
    const val CALENDAR = "calendar"
}

object TemporalCertainty {
    const val CONFIRMED = "confirmed"
    const val INFERRED = "inferred"
    const val UNCERTAIN = "uncertain"
}

data class TemporalSelection(
    val kind: String,
    val instant: String?,
    val zoneId: String,
    val certainty: String = TemporalCertainty.CONFIRMED,
)

object ReminderModes {
    const val RELATIVE = "relative"
    const val ABSOLUTE = "absolute"
}

object ReminderSources {
    const val USER = "user"
    const val AI_SUGGESTION = "ai_suggestion"
    const val MIGRATED = "migrated"
}

data class ReminderNode(
    val id: String = UUID.randomUUID().toString(),
    val mode: String = ReminderModes.RELATIVE,
    val absoluteTime: String? = null,
    val offsetMinutes: Long? = null,
    val enabled: Boolean = true,
    val source: String = ReminderSources.USER,
    val revision: Int = 0,
    val legacyLabel: String? = null,
) {
    fun displayLabel(): String = when {
        !enabled && !legacyLabel.isNullOrBlank() -> "$legacyLabel · 待检查"
        mode == ReminderModes.ABSOLUTE && !absoluteTime.isNullOrBlank() ->
            runCatching { OffsetDateTime.parse(absoluteTime) }
                .map { "%d月%d日 %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) }
                .getOrDefault(absoluteTime)
        offsetMinutes != null -> {
            val days = offsetMinutes / 1440
            val hours = offsetMinutes % 1440 / 60
            val minutes = offsetMinutes % 60
            buildString {
                append("截止前")
                if (days > 0) append("${days}天")
                if (hours > 0) append("${hours}小时")
                if (minutes > 0 || offsetMinutes == 0L) append("${minutes}分钟")
            }
        }
        else -> legacyLabel ?: "提醒时间待确认"
    }
}

fun reminderNodeFromLegacy(label: String, revision: Int = 0): ReminderNode {
    val days = Regex("""(\d+)\s*(?:天|日)""").find(label)
        ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val hours = Regex("""(\d+)\s*(?:小时|时)""").find(label)
        ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val minutes = Regex("""(\d+)\s*(?:分钟|分)""").find(label)
        ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val immediate = listOf("尽快", "马上", "现在").any(label::contains)
    val offset = days * 1440 + hours * 60 + minutes
    return ReminderNode(
        offsetMinutes = when {
            immediate -> 0
            offset > 0 -> offset
            else -> null
        },
        enabled = immediate || offset > 0,
        source = ReminderSources.MIGRATED,
        revision = revision,
        legacyLabel = label,
    )
}

fun ActionCard.effectiveReminderNodes(): List<ReminderNode> =
    reminderNodes.ifEmpty { reminders.map(::reminderNodeFromLegacy) }

fun mergeReminderLabels(
    existing: List<ReminderNode>,
    labels: List<String>,
): List<ReminderNode> {
    val byOffset = existing.associateBy { it.offsetMinutes }
    return labels.map { label ->
        val parsed = reminderNodeFromLegacy(label)
        byOffset[parsed.offsetMinutes]?.copy(
            enabled = true,
            revision = byOffset[parsed.offsetMinutes]!!.revision + 1,
            legacyLabel = label,
        ) ?: parsed.copy(source = ReminderSources.USER)
    }
}
