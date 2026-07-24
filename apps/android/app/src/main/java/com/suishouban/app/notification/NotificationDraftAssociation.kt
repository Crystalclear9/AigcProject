package com.suishouban.app.notification

/** Binds a reviewed notification candidate to only the drafts produced from that candidate. */
data class NotificationDraftAssociation(
    val candidateId: String,
    val draftIds: Set<String>,
) {
    fun candidateToConsume(savedDraftIds: Set<String>): String? =
        candidateId.takeIf { draftIds.any(savedDraftIds::contains) }
}
