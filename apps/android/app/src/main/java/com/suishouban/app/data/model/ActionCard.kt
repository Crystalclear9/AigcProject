package com.suishouban.app.data.model

import java.time.OffsetDateTime
import java.util.UUID

object CardTypes {
    const val TASK = "task"
    const val EVENT = "event"
    const val PROMISE = "promise"
    const val NOTE = "note"
}

object CardStatus {
    const val DRAFT = "draft"
    const val CONFIRMED = "confirmed"
    const val DONE = "done"
    const val ARCHIVED = "archived"
}

object Priority {
    const val LOW = "low"
    const val NORMAL = "normal"
    const val HIGH = "high"
}

data class ActionCard(
    val id: String = UUID.randomUUID().toString(),
    val cardType: String = CardTypes.TASK,
    val title: String,
    val summary: String = "",
    val deadline: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val location: String? = null,
    val materials: List<String> = emptyList(),
    val submitMethod: String? = null,
    val priority: String = Priority.NORMAL,
    val tags: List<String> = emptyList(),
    val reminders: List<String> = emptyList(),
    val needConfirm: List<String> = emptyList(),
    val status: String = CardStatus.DRAFT,
    val sourceText: String = "",
    val createdAt: String = OffsetDateTime.now().toString(),
)

data class AnalyzeResult(
    val ocrText: String,
    val cards: List<ActionCard>,
    val previewActions: List<String>,
    val engine: String,
)

fun ActionCard.isTimed(): Boolean = deadline != null || startTime != null

fun ActionCard.primaryTime(): String? = startTime ?: deadline
