package com.suishouban.app.data.repository

/** Primitive notification snapshot copied before work leaves the system listener callback. */
data class NotificationCandidateInput(
    val notificationKey: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val body: String,
    val postedAtMillis: Long,
    val isOngoing: Boolean = false,
    val isGroupSummary: Boolean = false,
)

enum class NotificationCandidateDecision {
    ACCEPT,
    NOT_ALLOWLISTED,
    SELF_NOTIFICATION,
    SENSITIVE,
    SYSTEM_NOISE,
    EMPTY,
}
