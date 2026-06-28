package com.suishouban.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardTypes

@Entity(tableName = "cards")
data class ActionCardEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "action_id") val actionId: String?,
    val dependencies: List<String>,
    @ColumnInfo(name = "evidence_summary") val evidenceSummary: List<String>,
    @ColumnInfo(name = "card_type") val cardType: String,
    val title: String,
    val summary: String,
    val deadline: String?,
    @ColumnInfo(name = "start_time") val startTime: String?,
    @ColumnInfo(name = "end_time") val endTime: String?,
    val location: String?,
    val materials: List<String>,
    @ColumnInfo(name = "submit_method") val submitMethod: String?,
    val priority: String,
    val tags: List<String>,
    val reminders: List<String>,
    @ColumnInfo(name = "need_confirm") val needConfirm: List<String>,
    val status: String,
    @ColumnInfo(name = "source_text") val sourceText: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
)

fun ActionCardEntity.toDomain(): ActionCard = ActionCard(
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

fun ActionCard.toEntity(): ActionCardEntity = ActionCardEntity(
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

// Older prototype builds stored fallback cards as "note"; keep them readable after the 5-type migration.
private fun normalizeCardType(value: String): String {
    return if (value == "note") CardTypes.COLLECTION else value
}
