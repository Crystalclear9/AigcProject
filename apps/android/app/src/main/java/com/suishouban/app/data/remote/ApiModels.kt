package com.suishouban.app.data.remote

import com.google.gson.annotations.SerializedName
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardTypes

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
    @SerializedName("cache_status") val cacheStatus: String = "bypass",
    @SerializedName("time_to_first_draft_ms") val timeToFirstDraftMs: Double? = null,
    @SerializedName("time_to_final_ms") val timeToFinalMs: Double? = null,
    @SerializedName("active_agents") val activeAgents: List<String> = emptyList(),
    @SerializedName("decision_reasons") val decisionReasons: List<String> = emptyList(),
    @SerializedName("risk_level") val riskLevel: String = "low",
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

// Accept legacy workflow responses while stored data migrates from "note".
private fun normalizeCardType(value: String): String {
    return if (value == "note") CardTypes.COLLECTION else value
}
