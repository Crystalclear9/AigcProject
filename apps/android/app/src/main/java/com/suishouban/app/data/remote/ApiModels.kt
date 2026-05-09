package com.suishouban.app.data.remote

import com.google.gson.annotations.SerializedName
import com.suishouban.app.data.model.ActionCard

data class AnalyzeScreenshotTextRequest(
    val text: String,
    @SerializedName("screenshot_time") val screenshotTime: String? = null,
)

data class AnalyzeScreenshotTextResponse(
    @SerializedName("ocr_text") val ocrText: String,
    val cards: List<ActionCardDto>,
    @SerializedName("preview_actions") val previewActions: List<String>,
    val engine: String,
)

data class ActionCardDto(
    val id: String,
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
    cardType = cardType,
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
    cardType = cardType,
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
