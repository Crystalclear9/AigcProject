package com.suishouban.app.data.remote

import com.google.gson.annotations.SerializedName
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardTypes
import com.suishouban.app.data.model.ProviderUsage

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
    @SerializedName("image_generation_status") val imageGenerationStatus: String = "not_configured",
    @SerializedName("react_suggestions") val reactSuggestions: List<String> = emptyList(),
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
)

data class OcrCandidateRequest(
    val text: String,
    val engine: String = "mlkit",
    val confidence: Double = 0.8,
)

data class ConfirmWorkflowRequest(val revision: Int)

data class WorkflowReactRequest(
    @SerializedName("base_revision") val baseRevision: Int,
    val instruction: String = "",
    @SerializedName("selected_card_ids") val selectedCardIds: List<String> = emptyList(),
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
    val tags: List<String> = emptyList(),
    val reminders: List<String> = emptyList(),
    @SerializedName("need_confirm") val needConfirm: List<String> = emptyList(),
    val status: String = "draft",
    @SerializedName("source_text") val sourceText: String = "",
    @SerializedName("created_at") val createdAt: String,
)

fun ActionCardDto.toDomain(): ActionCard = ActionCard(
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
    tags = tags,
    reminders = reminders,
    needConfirm = needConfirm,
    status = status,
    sourceText = sourceText,
    createdAt = createdAt,
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
    tags = tags,
    reminders = reminders,
    needConfirm = needConfirm,
    status = status,
    sourceText = sourceText,
    createdAt = createdAt,
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

// Accept legacy workflow responses while stored data migrates from "note".
private fun normalizeCardType(value: String): String {
    return if (value == "note") CardTypes.COLLECTION else value
}
