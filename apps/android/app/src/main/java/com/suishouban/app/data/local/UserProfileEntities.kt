package com.suishouban.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import com.suishouban.app.data.model.ProfileSignalStat
import com.suishouban.app.data.model.UserProfile

@Entity(tableName = "user_profiles", primaryKeys = ["id"])
data class UserProfileEntity(
    val id: String,
    val version: Int,
    val scenario: String,
    @ColumnInfo(name = "active_period") val activePeriod: String,
    @ColumnInfo(name = "planning_granularity") val planningGranularity: String,
    @ColumnInfo(name = "reminder_style") val reminderStyle: String,
    @ColumnInfo(name = "work_rhythm") val workRhythm: String,
    @ColumnInfo(name = "buffer_preference") val bufferPreference: String,
    @ColumnInfo(name = "weekend_policy") val weekendPolicy: String,
    @ColumnInfo(name = "assistant_tone") val assistantTone: String,
    val timezone: String,
    @ColumnInfo(name = "questionnaire_version") val questionnaireVersion: Int,
    @ColumnInfo(name = "questionnaire_completed") val questionnaireCompleted: Boolean,
    @ColumnInfo(name = "learning_consent") val learningConsent: Boolean,
    @ColumnInfo(name = "explicit_fields") val explicitFields: List<String>,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

@Entity(tableName = "profile_signal_stats", primaryKeys = ["metric", "option_value"])
data class ProfileSignalStatEntity(
    val metric: String,
    @ColumnInfo(name = "option_value") val option: String,
    val count: Int,
    val score: Double,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

fun UserProfileEntity.toDomain(): UserProfile = UserProfile(
    id = id,
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
    questionnaireVersion = questionnaireVersion,
    questionnaireCompleted = questionnaireCompleted,
    learningConsent = learningConsent,
    explicitFields = explicitFields.toSet(),
    updatedAt = updatedAt,
)

fun UserProfile.toEntity(): UserProfileEntity = UserProfileEntity(
    id = id,
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
    questionnaireVersion = questionnaireVersion,
    questionnaireCompleted = questionnaireCompleted,
    learningConsent = learningConsent,
    explicitFields = explicitFields.toList().sorted(),
    updatedAt = updatedAt,
)

fun ProfileSignalStatEntity.toDomain(): ProfileSignalStat = ProfileSignalStat(
    metric = metric,
    option = option,
    count = count,
    score = score,
    updatedAt = updatedAt,
)
