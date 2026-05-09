package com.suishouban.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.suishouban.app.data.model.ActionCard

@Entity(tableName = "cards")
data class ActionCardEntity(
    @PrimaryKey val id: String,
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

fun ActionCard.toEntity(): ActionCardEntity = ActionCardEntity(
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
