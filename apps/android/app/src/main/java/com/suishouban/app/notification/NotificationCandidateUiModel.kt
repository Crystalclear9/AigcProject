package com.suishouban.app.notification

import com.suishouban.app.data.local.NotificationCandidateEntity

data class NotificationCandidateUiModel(
    val id: String,
    val sourceLabel: String,
    val summary: String,
    val postedAtMillis: Long,
) {
    companion object {
        private const val MAX_SUMMARY_CHARS = 72

        fun from(entity: NotificationCandidateEntity): NotificationCandidateUiModel {
            val text = listOf(entity.title, entity.body)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .replace(Regex("\\s+"), " ")
                .trim()
            return NotificationCandidateUiModel(
                id = entity.id,
                sourceLabel = entity.appLabel.ifBlank { entity.packageName },
                summary = if (text.length <= MAX_SUMMARY_CHARS) text else text.take(MAX_SUMMARY_CHARS - 1) + "…",
                postedAtMillis = entity.postedAtMillis,
            )
        }

        fun from(entities: List<NotificationCandidateEntity>): List<NotificationCandidateUiModel> =
            entities.sortedByDescending { it.postedAtMillis }.map(::from)
    }
}
