package com.suishouban.app.data.model

import java.time.OffsetDateTime
import java.util.UUID

object PlanItemKinds {
    const val MILESTONE = "milestone"
    const val WORK_BLOCK = "work_block"
    const val STEP = "step"
}

object PlanStatuses {
    const val DRAFT = "draft"
    const val ACCEPTED = "accepted"
}

object PlanItemStatuses {
    const val PROPOSED = "proposed"
    const val ACCEPTED = "accepted"
    const val DONE = "done"
    const val SKIPPED = "skipped"
}

data class PlanItem(
    val id: String = UUID.randomUUID().toString(),
    val parentId: String? = null,
    val kind: String = PlanItemKinds.STEP,
    val title: String,
    val description: String = "",
    val order: Int = 0,
    val startTime: String? = null,
    val deadline: String? = null,
    val estimatedMinutes: Int? = null,
    val importance: String = Priority.NORMAL,
    val dependencies: List<String> = emptyList(),
    val reminderEnabled: Boolean = false,
    val confidence: Double = 0.5,
    val evidenceRefs: List<String> = emptyList(),
    val needConfirm: List<String> = emptyList(),
    val status: String = PlanItemStatuses.PROPOSED,
)

data class ActionPlan(
    val id: String = UUID.randomUUID().toString(),
    val parentCardId: String,
    val revision: Int = 1,
    val objective: String = "",
    val assumptions: List<String> = emptyList(),
    val evidenceSummary: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val generatedBy: String = "rules",
    val profileVersion: Int? = null,
    val qualityScore: Double = 0.0,
    val constraintErrors: List<String> = emptyList(),
    val profileEffects: List<String> = emptyList(),
    val verificationSummary: String = "",
    val status: String = PlanStatuses.DRAFT,
    val createdAt: String = OffsetDateTime.now().toString(),
    val updatedAt: String = createdAt,
    val items: List<PlanItem> = emptyList(),
)

data class CardAttachment(
    val id: String = UUID.randomUUID().toString(),
    val cardId: String,
    val displayName: String,
    val mimeType: String,
    val uri: String,
    val sizeBytes: Long,
    val sha256: String = "",
    val extractionStatus: String = "pending",
    val warning: String? = null,
    val createdAt: String = OffsetDateTime.now().toString(),
)

data class CardRefinementPreference(
    val cardId: String,
    val refinementEnabled: Boolean? = null,
    val useProfile: Boolean? = null,
    val includeWorkBlocks: Boolean? = null,
    val milestoneReminders: Boolean? = null,
    val granularity: String? = null,
)

data class CardDetail(
    val card: ActionCard,
    val plan: ActionPlan? = null,
    val attachments: List<CardAttachment> = emptyList(),
    val preferences: CardRefinementPreference? = null,
)
