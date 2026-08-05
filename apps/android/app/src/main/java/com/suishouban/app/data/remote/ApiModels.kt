package com.suishouban.app.data.remote

import com.google.gson.annotations.SerializedName
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardTypes
import com.suishouban.app.data.model.ProviderUsage
import com.suishouban.app.data.model.ActionPlan
import com.suishouban.app.data.model.CardAttachment
import com.suishouban.app.data.model.PlanItem
import com.suishouban.app.data.model.UserProfileContext
import com.suishouban.app.domain.EvidenceSummaryComposer
import com.suishouban.app.domain.TextIntegrity

data class OnboardingTurnRequestDto(
    @SerializedName("session_id") val sessionId: String,
    val phase: String,
    @SerializedName("current_step") val currentStep: String,
    val answers: Map<String, String>,
    @SerializedName("answered_followups") val answeredFollowups: List<String>,
    val locale: String = "zh-CN",
    val timezone: String,
    @SerializedName("max_followups") val maxFollowups: Int = 3,
)

data class OnboardingOptionDto(
    val id: String,
    val label: String,
)

data class OnboardingQuestionDto(
    val id: String,
    val topic: String,
    val prompt: String,
    val options: List<OnboardingOptionDto>,
)

data class OnboardingTurnResponseDto(
    @SerializedName("request_id") val requestId: String,
    @SerializedName("assistant_message") val assistantMessage: String,
    @SerializedName("next_question") val nextQuestion: OnboardingQuestionDto? = null,
    val mood: String = "focus",
    @SerializedName("animation_hint") val animationHint: String = "scan",
    val complete: Boolean = false,
    @SerializedName("profile_patch") val profilePatch: Map<String, String> = emptyMap(),
    @SerializedName("provider_usage") val providerUsage: Map<String, ProviderUsageDto> = emptyMap(),
    @SerializedName("enhancement_status") val enhancementStatus: String = "not_configured",
)

data class AnalyzeScreenshotTextRequest(
    val text: String,
    @SerializedName("screenshot_time") val screenshotTime: String? = null,
)

data class AnalyzeScreenshotTextResponse(
    @SerializedName("ocr_text") val ocrText: String,
    val cards: List<ActionCardDto>,
    @SerializedName("preview_actions") val previewActions: List<String>,
    val engine: String,
    @SerializedName("trace_id") val traceId: String = "",
    @SerializedName("fallback_reason") val fallbackReason: String? = null,
    val warnings: List<String> = emptyList(),
    @SerializedName("run_id") val runId: String = "",
    @SerializedName("workflow_status") val workflowStatus: String = "completed",
    @SerializedName("pending_action") val pendingAction: String? = null,
    @SerializedName("node_trace") val nodeTrace: List<NodeTraceDto> = emptyList(),
    val revision: Int = 0,
    @SerializedName("result_stage") val resultStage: String = "provisional",
    @SerializedName("overall_confidence") val overallConfidence: Double = 0.0,
    val route: String = "rules",
    @SerializedName("cache_status") val cacheStatus: String? = null,
    @SerializedName("time_to_first_draft_ms") val timeToFirstDraftMs: Double? = null,
    @SerializedName("time_to_final_ms") val timeToFinalMs: Double? = null,
    @SerializedName("active_agents") val activeAgents: List<String> = emptyList(),
    @SerializedName("decision_reasons") val decisionReasons: List<String> = emptyList(),
    @SerializedName("risk_level") val riskLevel: String = "low",
    @SerializedName("validation_errors") val validationErrors: List<String> = emptyList(),
    @SerializedName("field_conflicts") val fieldConflicts: List<Map<String, Any?>> = emptyList(),
    @SerializedName("field_versions") val fieldVersions: Map<String, Map<String, Int>> = emptyMap(),
    @SerializedName("provider_usage") val providerUsage: Map<String, ProviderUsageDto> = emptyMap(),
    @SerializedName("model_enhancement_status") val modelEnhancementStatus: String = "not_configured",
    @SerializedName("ocr_enhancement_status") val ocrEnhancementStatus: String = "not_configured",
    @SerializedName("ocr_quality_report") val ocrQualityReport: OcrQualityReportDto? = null,
    @SerializedName("ocr_review_reasons") val ocrReviewReasons: List<String> = emptyList(),
    @SerializedName("image_generation_status") val imageGenerationStatus: String = "not_configured",
    @SerializedName("react_suggestions") val reactSuggestions: List<String> = emptyList(),
    @SerializedName("agent_contract_version") val agentContractVersion: String = "agent-contract-v2",
    @SerializedName("agent_outputs") val agentOutputs: List<Map<String, Any?>> = emptyList(),
    @SerializedName("workflow_phase") val workflowPhase: String = "received",
    @SerializedName("evidence_status") val evidenceStatus: String = "trusted",
    @SerializedName("draft_status") val draftStatus: String = "not_started",
    @SerializedName("review_items") val reviewItems: List<Map<String, Any?>> = emptyList(),
    @SerializedName("effect_status") val effectStatus: String = "not_started",
    @SerializedName("blocked_reasons") val blockedReasons: List<String> = emptyList(),
    @SerializedName("checkpoint_id") val checkpointId: String? = null,
    @SerializedName("command_ids") val commandIds: List<String> = emptyList(),
    @SerializedName("evidence_envelopes") val evidenceEnvelopes: List<Map<String, Any?>> = emptyList(),
    @SerializedName("field_evidence") val fieldEvidence: List<Map<String, Any?>> = emptyList(),
)

data class OcrQualityReportDto(
    @SerializedName("quality_score") val qualityScore: Double = 0.0,
    @SerializedName("garbled_ratio") val garbledRatio: Double = 0.0,
    @SerializedName("completeness_score") val completenessScore: Double = 0.0,
    @SerializedName("layout_score") val layoutScore: Double = 0.0,
    @SerializedName("evidence_score") val evidenceScore: Double = 0.0,
    @SerializedName("agreement_score") val agreementScore: Double = 0.0,
    @SerializedName("duplicate_ratio") val duplicateRatio: Double = 0.0,
    @SerializedName("noise_ratio") val noiseRatio: Double = 0.0,
    val reasons: List<String> = emptyList(),
) {
    fun toDomain() = com.suishouban.app.data.model.OcrQualityReport(
        qualityScore = qualityScore,
        garbledRatio = garbledRatio,
        completenessScore = completenessScore,
        layoutScore = layoutScore,
        evidenceScore = evidenceScore,
        agreementScore = agreementScore,
        duplicateRatio = duplicateRatio,
        noiseRatio = noiseRatio,
        reasons = reasons,
    )
}

data class PromptEnvelopeDto(
    val version: String = "",
    @SerializedName("role_template") val roleTemplate: String = "action_analyst",
    @SerializedName("user_policy") val userPolicy: String = "",
    @SerializedName("character_count") val characterCount: Int = 0,
)

data class IntakeSessionResponseDto(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("workflow_run_id") val workflowRunId: String? = null,
    @SerializedName("source_kind") val sourceKind: String = "text",
    @SerializedName("workspace_type") val workspaceType: String = "personal",
    val classification: String = "informational",
    @SerializedName("classification_confidence") val classificationConfidence: Double = 0.0,
    @SerializedName("should_create_cards") val shouldCreateCards: Boolean = false,
    @SerializedName("canonical_text") val canonicalText: String = "",
    val cards: List<ActionCardDto> = emptyList(),
    @SerializedName("prompt_envelope") val promptEnvelope: PromptEnvelopeDto,
    val warnings: List<String> = emptyList(),
    val workflow: AnalyzeScreenshotTextResponse? = null,
)

data class IntakeConfirmRequestDto(
    val revision: Int,
    @SerializedName("selected_card_ids") val selectedCardIds: List<String>,
)

data class IntakeRefineRequestDto(
    @SerializedName("card_id") val cardId: String,
    val options: CardRefinementOptionsDto,
    @SerializedName("profile_context") val profileContext: UserProfileContext? = null,
    val instruction: String = "",
)

data class WorkflowEventEnvelope(
    val snapshot: AnalyzeScreenshotTextResponse? = null,
)

data class DraftPatchRequest(
    @SerializedName("base_revision") val baseRevision: Int,
    val operations: List<DraftFieldOperation>,
)

data class DraftFieldOperation(
    val operation: String,
    @SerializedName("card_id") val cardId: String,
    val field: String,
    val value: Any? = null,
    @SerializedName("base_field_version") val baseFieldVersion: Int? = null,
)

data class HealthResponse(
    val status: String,
    val ready: Boolean = false,
    @SerializedName("langgraph_version") val langGraphVersion: String = "",
    @SerializedName("sqlite_checkpointer_available") val sqliteCheckpointerAvailable: Boolean = false,
    @SerializedName("chat_configured") val chatConfigured: Boolean = false,
    @SerializedName("ocr_configured") val ocrConfigured: Boolean = false,
    @SerializedName("image_generation_configured") val imageGenerationConfigured: Boolean = false,
)

data class ProviderProbeResponse(
    @SerializedName("all_succeeded") val allSucceeded: Boolean = false,
    val results: Map<String, ProviderProbeResult> = emptyMap(),
)

data class ProviderProbeResult(
    val configured: Boolean = false,
    val attempted: Boolean = false,
    val succeeded: Boolean = false,
    @SerializedName("error_type") val errorType: String? = null,
    @SerializedName("latency_ms") val latencyMs: Double? = null,
)

data class ProviderUsageDto(
    @SerializedName("request_count_delta") val requestCountDelta: Int = 0,
    @SerializedName("success_count_delta") val successCountDelta: Int = 0,
    @SerializedName("failure_count_delta") val failureCountDelta: Int = 0,
    @SerializedName("last_success_at") val lastSuccessAt: String? = null,
    @SerializedName("last_error_type") val lastErrorType: String? = null,
    @SerializedName("latency_ms") val latencyMs: Double? = null,
    @SerializedName("circuit_open") val circuitOpen: Boolean = false,
)

data class WorkflowResumeRequest(
    val command: String,
    @SerializedName("ocr_text") val ocrText: String? = null,
    val cards: List<ActionCardDto>? = null,
    @SerializedName("team_tasks") val teamTasks: List<Map<String, Any?>>? = null,
)

data class OcrCandidateRequest(
    val text: String,
    val engine: String = "mlkit",
    val confidence: Double = 0.8,
    val blocks: List<OcrBlockDto> = emptyList(),
    @SerializedName("arrived_at_ms") val arrivedAtMs: Long? = null,
    @SerializedName("image_width") val imageWidth: Int? = null,
    @SerializedName("image_height") val imageHeight: Int? = null,
    @SerializedName("rotation_degrees") val rotationDegrees: Int = 0,
    val variant: String = "original",
)

data class OcrBlockDto(
    val id: String,
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    @SerializedName("reading_order") val readingOrder: Int,
    @SerializedName("page_index") val pageIndex: Int = 0,
)

data class ConfirmWorkflowRequest(val revision: Int)

data class ConfirmEffectsRequest(
    val revision: Int,
    @SerializedName("confirmed_card_ids") val confirmedCardIds: List<String> = emptyList(),
    @SerializedName("confirmed_team_task_ids") val confirmedTeamTaskIds: List<String> = emptyList(),
    @SerializedName("effect_types") val effectTypes: List<String> = emptyList(),
    @SerializedName("idempotency_key") val idempotencyKey: String,
)

data class WorkflowReactRequest(
    @SerializedName("base_revision") val baseRevision: Int,
    val instruction: String = "",
    @SerializedName("selected_card_ids") val selectedCardIds: List<String> = emptyList(),
)

data class CardRefinementOptionsDto(
    val granularity: String = "balanced",
    @SerializedName("include_milestones") val includeMilestones: Boolean = true,
    @SerializedName("include_work_blocks") val includeWorkBlocks: Boolean = true,
    @SerializedName("milestone_reminders") val milestoneReminders: Boolean = true,
    @SerializedName("use_profile") val useProfile: Boolean = true,
)

data class UserProfileContextDto(
    val version: Int,
    val scenario: String,
    @SerializedName("active_period") val activePeriod: String,
    @SerializedName("planning_granularity") val planningGranularity: String,
    @SerializedName("reminder_style") val reminderStyle: String,
    @SerializedName("work_rhythm") val workRhythm: String,
    @SerializedName("buffer_preference") val bufferPreference: String,
    @SerializedName("weekend_policy") val weekendPolicy: String,
    @SerializedName("assistant_tone") val assistantTone: String,
    val timezone: String,
)

data class PlanItemDto(
    val id: String,
    @SerializedName("parent_id") val parentId: String? = null,
    val kind: String,
    val title: String,
    val description: String = "",
    val order: Int = 0,
    @SerializedName("start_time") val startTime: String? = null,
    val deadline: String? = null,
    @SerializedName("estimated_minutes") val estimatedMinutes: Int? = null,
    val importance: String = "normal",
    val dependencies: List<String> = emptyList(),
    @SerializedName("reminder_enabled") val reminderEnabled: Boolean = false,
    val confidence: Double = 0.5,
    @SerializedName("evidence_refs") val evidenceRefs: List<String> = emptyList(),
    @SerializedName("need_confirm") val needConfirm: List<String> = emptyList(),
    val status: String = "proposed",
)

data class CardRefinementPlanDto(
    val id: String,
    @SerializedName("parent_card_id") val parentCardId: String,
    val revision: Int = 1,
    val objective: String = "",
    val items: List<PlanItemDto> = emptyList(),
    val assumptions: List<String> = emptyList(),
    @SerializedName("evidence_summary") val evidenceSummary: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    @SerializedName("generated_by") val generatedBy: String = "rules",
    @SerializedName("profile_version") val profileVersion: Int? = null,
    @SerializedName("quality_score") val qualityScore: Double = 0.0,
    @SerializedName("constraint_errors") val constraintErrors: List<String> = emptyList(),
    @SerializedName("profile_effects") val profileEffects: List<String> = emptyList(),
    @SerializedName("verification_summary") val verificationSummary: String = "",
    val status: String = "draft",
)

data class AttachmentDescriptorDto(
    val id: String,
    val name: String,
    @SerializedName("mime_type") val mimeType: String,
    @SerializedName("size_bytes") val sizeBytes: Long,
    val sha256: String = "",
    @SerializedName("extraction_status") val extractionStatus: String = "pending",
    val warning: String? = null,
)

data class CardRefinementRunResponseDto(
    @SerializedName("run_id") val runId: String,
    @SerializedName("trace_id") val traceId: String,
    val status: String,
    @SerializedName("pending_action") val pendingAction: String? = null,
    val plan: CardRefinementPlanDto? = null,
    val attachments: List<AttachmentDescriptorDto> = emptyList(),
    val warnings: List<String> = emptyList(),
    @SerializedName("validation_errors") val validationErrors: List<String> = emptyList(),
    @SerializedName("provider_usage") val providerUsage: Map<String, ProviderUsageDto> = emptyMap(),
    @SerializedName("model_enhancement_status") val modelEnhancementStatus: String = "not_configured",
    val revision: Int = 0,
    val error: String? = null,
)

data class CardRefinementReactRequestDto(
    @SerializedName("base_revision") val baseRevision: Int,
    val instruction: String,
    @SerializedName("selected_item_ids") val selectedItemIds: List<String>,
)

data class CardRefinementConfirmRequestDto(
    val revision: Int,
    @SerializedName("selected_item_ids") val selectedItemIds: List<String>,
    val items: List<PlanItemDto>? = null,
)

data class NodeTraceDto(
    val node: String,
    val status: String = "completed",
    @SerializedName("duration_ms") val durationMs: Double = 0.0,
    val engine: String? = null,
    val detail: String? = null,
)

data class ActionCardDto(
    val id: String,
    @SerializedName("action_id") val actionId: String? = null,
    val dependencies: List<String> = emptyList(),
    @SerializedName("evidence_summary") val evidenceSummary: List<String> = emptyList(),
    @SerializedName("card_type") val cardType: String,
    val title: String,
    val summary: String = "",
    val deadline: String? = null,
    @SerializedName("start_time") val startTime: String? = null,
    @SerializedName("end_time") val endTime: String? = null,
    val location: String? = null,
    val materials: List<String> = emptyList(),
    @SerializedName("submit_method") val submitMethod: String? = null,
    val priority: String = "normal",
    @SerializedName("priority_mode") val priorityMode: String = "adaptive",
    @SerializedName("priority_score") val priorityScore: Double = 50.0,
    @SerializedName("priority_reason") val priorityReason: String = "",
    @SerializedName("priority_updated_at") val priorityUpdatedAt: String? = null,
    @SerializedName("priority_locked") val priorityLocked: Boolean = false,
    @SerializedName("workspace_type") val workspaceType: String = "personal",
    @SerializedName("workspace_id") val workspaceId: String = "personal",
    @SerializedName("assignee_id") val assigneeId: String? = null,
    @SerializedName("participant_ids") val participantIds: List<String> = emptyList(),
    val deliverables: List<String> = emptyList(),
    @SerializedName("source_session_id") val sourceSessionId: String? = null,
    val tags: List<String> = emptyList(),
    val reminders: List<String> = emptyList(),
    @SerializedName("reminder_nodes") val reminderNodes: List<ReminderNodeDto> = emptyList(),
    @SerializedName("need_confirm") val needConfirm: List<String> = emptyList(),
    val status: String = "draft",
    @SerializedName("source_text") val sourceText: String = "",
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("goal_id") val goalId: String? = null,
    @SerializedName("milestone_id") val milestoneId: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
)

fun ActionCardDto.toDomain(): ActionCard = ActionCard(
    id = id,
    actionId = actionId,
    dependencies = dependencies,
    evidenceSummary = evidenceSummary,
    cardType = normalizeCardType(cardType),
    title = title,
    summary = TextIntegrity.chooseBetterSummary(
        EvidenceSummaryComposer.compose(
            title = title,
            deadline = deadline,
            startTime = startTime,
            location = location,
            materials = materials,
            submitMethod = submitMethod,
        ),
        summary,
    ),
    deadline = deadline,
    startTime = startTime,
    endTime = endTime,
    location = location,
    materials = materials,
    submitMethod = submitMethod,
    priority = priority,
    priorityMode = priorityMode,
    priorityScore = priorityScore,
    priorityReason = priorityReason,
    priorityUpdatedAt = priorityUpdatedAt,
    priorityLocked = priorityLocked,
    workspaceType = workspaceType,
    workspaceId = workspaceId,
    assigneeId = assigneeId,
    participantIds = participantIds,
    deliverables = deliverables,
    sourceSessionId = sourceSessionId,
    tags = tags,
    reminders = reminders,
    reminderNodes = reminderNodes.map { it.toDomain() },
    needConfirm = needConfirm,
    status = status,
    sourceText = sourceText,
    createdAt = createdAt,
    goalId = goalId,
    milestoneId = milestoneId,
    updatedAt = updatedAt,
)

fun ActionCard.toDto(): ActionCardDto = ActionCardDto(
    id = id,
    actionId = actionId,
    dependencies = dependencies,
    evidenceSummary = evidenceSummary,
    cardType = normalizeCardType(cardType),
    title = title,
    summary = summary,
    deadline = deadline,
    startTime = startTime,
    endTime = endTime,
    location = location,
    materials = materials,
    submitMethod = submitMethod,
    priority = priority,
    priorityMode = priorityMode,
    priorityScore = priorityScore,
    priorityReason = priorityReason,
    priorityUpdatedAt = priorityUpdatedAt,
    priorityLocked = priorityLocked,
    workspaceType = workspaceType,
    workspaceId = workspaceId,
    assigneeId = assigneeId,
    participantIds = participantIds,
    deliverables = deliverables,
    sourceSessionId = sourceSessionId,
    tags = tags,
    reminders = reminders,
    reminderNodes = reminderNodes.map { it.toDto() },
    needConfirm = needConfirm,
    status = status,
    sourceText = sourceText,
    createdAt = createdAt,
    goalId = goalId,
    milestoneId = milestoneId,
    updatedAt = updatedAt,
)

data class ReminderNodeDto(
    val id: String,
    val mode: String = "relative",
    @SerializedName("absolute_time") val absoluteTime: String? = null,
    @SerializedName("offset_minutes") val offsetMinutes: Long? = null,
    val enabled: Boolean = true,
    val source: String = "user",
    val revision: Int = 0,
    @SerializedName("legacy_label") val legacyLabel: String? = null,
)

private fun ReminderNodeDto.toDomain() = com.suishouban.app.data.model.ReminderNode(
    id = id,
    mode = mode,
    absoluteTime = absoluteTime,
    offsetMinutes = offsetMinutes,
    enabled = enabled,
    source = source,
    revision = revision,
    legacyLabel = legacyLabel,
)

private fun com.suishouban.app.data.model.ReminderNode.toDto() = ReminderNodeDto(
    id = id,
    mode = mode,
    absoluteTime = absoluteTime,
    offsetMinutes = offsetMinutes,
    enabled = enabled,
    source = source,
    revision = revision,
    legacyLabel = legacyLabel,
)

data class CardReplanRequestDto(
    @SerializedName("changed_fields") val changedFields: List<String>,
    @SerializedName("priority_mode") val priorityMode: String? = null,
    @SerializedName("manual_priority") val manualPriority: String? = null,
    val importance: Double = 0.5,
    @SerializedName("estimated_minutes") val estimatedMinutes: Int? = null,
    @SerializedName("blocked_dependents") val blockedDependents: Int = 0,
    @SerializedName("team_impact") val teamImpact: Double = 0.0,
)

data class CardReplanResponseDto(
    val card: ActionCardDto,
    val changed: Boolean,
    @SerializedName("verification_summary") val verificationSummary: String = "",
    val warnings: List<String> = emptyList(),
)

fun ProviderUsageDto.toDomain(): ProviderUsage = ProviderUsage(
    requestCountDelta = requestCountDelta,
    successCountDelta = successCountDelta,
    failureCountDelta = failureCountDelta,
    lastSuccessAt = lastSuccessAt,
    lastErrorType = lastErrorType,
    latencyMs = latencyMs,
    circuitOpen = circuitOpen,
)

fun UserProfileContext.toDto(): UserProfileContextDto = UserProfileContextDto(
    version = version,
    scenario = scenario,
    activePeriod = activePeriod,
    planningGranularity = planningGranularity,
    reminderStyle = reminderStyle,
    workRhythm = workRhythm,
    bufferPreference = bufferPreference,
    weekendPolicy = weekendPolicy,
    assistantTone = assistantTone,
    timezone = timezone,
)

fun PlanItemDto.toDomain(): PlanItem = PlanItem(
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

fun PlanItem.toDto(): PlanItemDto = PlanItemDto(
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

fun CardRefinementPlanDto.toDomain(): ActionPlan = ActionPlan(
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
    items = items.map(PlanItemDto::toDomain),
)

fun AttachmentDescriptorDto.toDomain(cardId: String, uri: String = ""): CardAttachment =
    CardAttachment(
        id = id,
        cardId = cardId,
        displayName = name,
        mimeType = mimeType,
        uri = uri,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        extractionStatus = extractionStatus,
        warning = warning,
    )

// Accept legacy workflow responses while stored data migrates from "note".
private fun normalizeCardType(value: String): String {
    return if (value == "note") CardTypes.COLLECTION else value
}

// --- Team collaboration (Phase 1) ---

data class UserRegisterRequestDto(
    val id: String,
    val nickname: String,
    @SerializedName("avatar_color") val avatarColor: String = "blue",
)

data class UserDto(
    val id: String,
    val nickname: String,
    @SerializedName("avatar_color") val avatarColor: String = "blue",
    @SerializedName("created_at") val createdAt: String = "",
)

data class TeamCreateRequestDto(
    val name: String,
)

data class TeamJoinRequestDto(
    @SerializedName("invite_code") val inviteCode: String,
)

data class TeamRenameRequestDto(
    val name: String,
)

data class TeamMemberDto(
    @SerializedName("user_id") val userId: String,
    val nickname: String = "",
    @SerializedName("avatar_color") val avatarColor: String = "blue",
    val role: String = "member",
    @SerializedName("joined_at") val joinedAt: String = "",
)

data class TeamDto(
    val id: String,
    val name: String,
    @SerializedName("invite_code") val inviteCode: String = "",
    @SerializedName("owner_id") val ownerId: String = "",
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("updated_at") val updatedAt: String = "",
    val members: List<TeamMemberDto> = emptyList(),
)

// --- Team collaboration (Phase 2: goals, decomposition, summary) ---

data class MilestoneDto(
    val id: String,
    @SerializedName("goal_id") val goalId: String = "",
    val title: String = "",
    @SerializedName("due_date") val dueDate: String? = null,
    @SerializedName("sort_order") val sortOrder: Int = 0,
)

data class TeamGoalDto(
    val id: String,
    @SerializedName("team_id") val teamId: String = "",
    val title: String = "",
    val description: String = "",
    @SerializedName("due_date") val dueDate: String? = null,
    val status: String = "active",
    @SerializedName("decompose_source") val decomposeSource: String = "template",
    @SerializedName("created_by") val createdBy: String = "",
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("updated_at") val updatedAt: String = "",
    val milestones: List<MilestoneDto> = emptyList(),
)

data class TeamGoalCreateRequestDto(
    val title: String,
    val description: String = "",
    @SerializedName("due_date") val dueDate: String? = null,
)

data class ProposedTaskDto(
    val title: String,
    val summary: String = "",
    @SerializedName("assignee_id") val assigneeId: String? = null,
    @SerializedName("milestone_id") val milestoneId: String? = null,
    @SerializedName("start_time") val startTime: String? = null,
    val deadline: String? = null,
    val deliverables: List<String> = emptyList(),
)

data class GoalDecompositionDto(
    val goal: TeamGoalDto,
    val tasks: List<ProposedTaskDto> = emptyList(),
    val warnings: List<String> = emptyList(),
)

data class GoalConfirmRequestDto(
    val tasks: List<ProposedTaskDto> = emptyList(),
)

data class GoalConfirmResponseDto(
    val goal: TeamGoalDto,
    val cards: List<ActionCardDto> = emptyList(),
)

data class MilestoneProgressDto(
    val milestone: MilestoneDto,
    val done: Int = 0,
    val total: Int = 0,
)

data class GoalProgressDto(
    val goal: TeamGoalDto,
    val done: Int = 0,
    val total: Int = 0,
    val milestones: List<MilestoneProgressDto> = emptyList(),
)

data class MemberStatDto(
    @SerializedName("user_id") val userId: String,
    val nickname: String = "",
    @SerializedName("avatar_color") val avatarColor: String = "blue",
    val role: String = "member",
    val done: Int = 0,
    val total: Int = 0,
)

data class TeamSummaryResponseDto(
    val team: TeamDto,
    val goals: List<GoalProgressDto> = emptyList(),
    @SerializedName("member_stats") val memberStats: List<MemberStatDto> = emptyList(),
    @SerializedName("changed_cards") val changedCards: List<ActionCardDto> = emptyList(),
    @SerializedName("server_time") val serverTime: String = "",
)

fun MilestoneDto.toDomain() = com.suishouban.app.data.model.TeamMilestone(
    id = id,
    title = title,
    dueDate = dueDate,
    sortOrder = sortOrder,
)

fun TeamGoalDto.toDomain() = com.suishouban.app.data.model.TeamGoalInfo(
    id = id,
    title = title,
    description = description,
    dueDate = dueDate,
    status = status,
    decomposeSource = decomposeSource,
    createdAt = createdAt,
    updatedAt = updatedAt,
    milestones = milestones.map(MilestoneDto::toDomain),
)

fun ProposedTaskDto.toDomain() = com.suishouban.app.data.model.ProposedTeamTask(
    title = title,
    summary = summary,
    assigneeId = assigneeId,
    milestoneId = milestoneId,
    startTime = startTime,
    deadline = deadline,
    deliverables = deliverables,
)

fun com.suishouban.app.data.model.ProposedTeamTask.toDto() = ProposedTaskDto(
    title = title,
    summary = summary,
    assigneeId = assigneeId,
    milestoneId = milestoneId,
    startTime = startTime,
    deadline = deadline,
    deliverables = deliverables,
)

fun GoalDecompositionDto.toDomain() = com.suishouban.app.data.model.TeamGoalPlan(
    goal = goal.toDomain(),
    tasks = tasks.map(ProposedTaskDto::toDomain),
    warnings = warnings,
)

fun MilestoneProgressDto.toDomain() = com.suishouban.app.data.model.TeamMilestoneProgress(
    milestone = milestone.toDomain(),
    done = done,
    total = total,
)

fun GoalProgressDto.toDomain() = com.suishouban.app.data.model.TeamGoalProgress(
    goal = goal.toDomain(),
    done = done,
    total = total,
    milestones = milestones.map(MilestoneProgressDto::toDomain),
)

fun MemberStatDto.toDomain() = com.suishouban.app.data.model.TeamMemberStat(
    userId = userId,
    nickname = nickname,
    avatarColor = avatarColor,
    role = role,
    done = done,
    total = total,
)

fun TeamSummaryResponseDto.toDomain() = com.suishouban.app.data.model.TeamDetailSummary(
    teamId = team.id,
    teamName = team.name,
    inviteCode = team.inviteCode,
    ownerId = team.ownerId,
    members = team.members.map { member ->
        com.suishouban.app.data.model.TeamMemberInfo(
            userId = member.userId,
            nickname = member.nickname,
            avatarColor = member.avatarColor,
            role = member.role,
        )
    },
    goals = goals.map(GoalProgressDto::toDomain),
    memberStats = memberStats.map(MemberStatDto::toDomain),
    serverTime = serverTime,
)
