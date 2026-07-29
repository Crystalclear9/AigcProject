package com.suishouban.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.suishouban.app.data.model.ActionPlan
import com.suishouban.app.data.model.CardAttachment
import com.suishouban.app.data.model.CardRefinementPreference
import com.suishouban.app.data.model.PlanItem

@Entity(
    tableName = "action_plans",
    foreignKeys = [
        ForeignKey(
            entity = ActionCardEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_card_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["parent_card_id"], unique = true)],
)
data class ActionPlanEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "parent_card_id") val parentCardId: String,
    val revision: Int,
    val objective: String,
    val assumptions: List<String>,
    @ColumnInfo(name = "evidence_summary") val evidenceSummary: List<String>,
    val warnings: List<String>,
    @ColumnInfo(name = "generated_by") val generatedBy: String,
    @ColumnInfo(name = "profile_version") val profileVersion: Int?,
    @ColumnInfo(name = "quality_score") val qualityScore: Double,
    @ColumnInfo(name = "constraint_errors") val constraintErrors: List<String>,
    @ColumnInfo(name = "profile_effects") val profileEffects: List<String>,
    @ColumnInfo(name = "verification_summary") val verificationSummary: String,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

@Entity(
    tableName = "plan_items",
    foreignKeys = [
        ForeignKey(
            entity = ActionPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["plan_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("plan_id"), Index("parent_id")],
)
data class PlanItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "parent_id") val parentId: String?,
    val kind: String,
    val title: String,
    val description: String,
    @ColumnInfo(name = "sort_order") val order: Int,
    @ColumnInfo(name = "start_time") val startTime: String?,
    val deadline: String?,
    @ColumnInfo(name = "estimated_minutes") val estimatedMinutes: Int?,
    val importance: String,
    val dependencies: List<String>,
    @ColumnInfo(name = "reminder_enabled") val reminderEnabled: Boolean,
    val confidence: Double,
    @ColumnInfo(name = "evidence_refs") val evidenceRefs: List<String>,
    @ColumnInfo(name = "need_confirm") val needConfirm: List<String>,
    val status: String,
)

@Entity(
    tableName = "card_attachments",
    foreignKeys = [
        ForeignKey(
            entity = ActionCardEntity::class,
            parentColumns = ["id"],
            childColumns = ["card_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("card_id")],
)
data class CardAttachmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "card_id") val cardId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    val uri: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    val sha256: String,
    @ColumnInfo(name = "extraction_status") val extractionStatus: String,
    val warning: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
)

@Entity(
    tableName = "card_refinement_preferences",
    foreignKeys = [
        ForeignKey(
            entity = ActionCardEntity::class,
            parentColumns = ["id"],
            childColumns = ["card_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CardRefinementPreferenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "card_id")
    val cardId: String,
    @ColumnInfo(name = "refinement_enabled") val refinementEnabled: Boolean?,
    @ColumnInfo(name = "use_profile") val useProfile: Boolean?,
    @ColumnInfo(name = "include_work_blocks") val includeWorkBlocks: Boolean?,
    @ColumnInfo(name = "milestone_reminders") val milestoneReminders: Boolean?,
    val granularity: String?,
)

data class ActionPlanWithItems(
    @Embedded val plan: ActionPlanEntity,
    @Relation(parentColumn = "id", entityColumn = "plan_id")
    val items: List<PlanItemEntity>,
)

fun ActionPlanWithItems.toDomain(): ActionPlan = plan.toDomain(
    items = items.sortedBy(PlanItemEntity::order).map(PlanItemEntity::toDomain),
)

fun ActionPlanEntity.toDomain(items: List<PlanItem>): ActionPlan = ActionPlan(
    id = id,
    parentCardId = parentCardId,
    revision = revision,
    objective = objective,
    assumptions = assumptions,
    evidenceSummary = evidenceSummary,
    warnings = warnings,
    generatedBy = generatedBy,
    profileVersion = profileVersion,
    qualityScore = qualityScore,
    constraintErrors = constraintErrors,
    profileEffects = profileEffects,
    verificationSummary = verificationSummary,
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt,
    items = items,
)

fun ActionPlan.toEntity(): ActionPlanEntity = ActionPlanEntity(
    id = id,
    parentCardId = parentCardId,
    revision = revision,
    objective = objective,
    assumptions = assumptions,
    evidenceSummary = evidenceSummary,
    warnings = warnings,
    generatedBy = generatedBy,
    profileVersion = profileVersion,
    qualityScore = qualityScore,
    constraintErrors = constraintErrors,
    profileEffects = profileEffects,
    verificationSummary = verificationSummary,
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun PlanItemEntity.toDomain(): PlanItem = PlanItem(
    id = id,
    parentId = parentId,
    kind = kind,
    title = title,
    description = description,
    order = order,
    startTime = startTime,
    deadline = deadline,
    estimatedMinutes = estimatedMinutes,
    importance = importance,
    dependencies = dependencies,
    reminderEnabled = reminderEnabled,
    confidence = confidence,
    evidenceRefs = evidenceRefs,
    needConfirm = needConfirm,
    status = status,
)

fun PlanItem.toEntity(planId: String): PlanItemEntity = PlanItemEntity(
    id = id,
    planId = planId,
    parentId = parentId,
    kind = kind,
    title = title,
    description = description,
    order = order,
    startTime = startTime,
    deadline = deadline,
    estimatedMinutes = estimatedMinutes,
    importance = importance,
    dependencies = dependencies,
    reminderEnabled = reminderEnabled,
    confidence = confidence,
    evidenceRefs = evidenceRefs,
    needConfirm = needConfirm,
    status = status,
)

fun CardAttachmentEntity.toDomain(): CardAttachment = CardAttachment(
    id = id,
    cardId = cardId,
    displayName = displayName,
    mimeType = mimeType,
    uri = uri,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    extractionStatus = extractionStatus,
    warning = warning,
    createdAt = createdAt,
)

fun CardAttachment.toEntity(): CardAttachmentEntity = CardAttachmentEntity(
    id = id,
    cardId = cardId,
    displayName = displayName,
    mimeType = mimeType,
    uri = uri,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    extractionStatus = extractionStatus,
    warning = warning,
    createdAt = createdAt,
)

fun CardRefinementPreferenceEntity.toDomain(): CardRefinementPreference =
    CardRefinementPreference(
        cardId = cardId,
        refinementEnabled = refinementEnabled,
        useProfile = useProfile,
        includeWorkBlocks = includeWorkBlocks,
        milestoneReminders = milestoneReminders,
        granularity = granularity,
    )

fun CardRefinementPreference.toEntity(): CardRefinementPreferenceEntity =
    CardRefinementPreferenceEntity(
        cardId = cardId,
        refinementEnabled = refinementEnabled,
        useProfile = useProfile,
        includeWorkBlocks = includeWorkBlocks,
        milestoneReminders = milestoneReminders,
        granularity = granularity,
    )
