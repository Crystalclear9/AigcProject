package com.suishouban.app.data.model

import com.google.gson.annotations.SerializedName
import java.time.OffsetDateTime
import java.time.ZoneId

object ProfileScenarios {
    const val UNSPECIFIED = "unspecified"
    const val STUDY = "study"
    const val OFFICE = "office"
    const val FREELANCE = "freelance"
    const val LIFE = "life"
    const val MIXED = "mixed"
}

object PlanningGranularity {
    const val CONCISE = "concise"
    const val BALANCED = "balanced"
    const val DETAILED = "detailed"
}

object ProfileBufferPreference {
    const val COMPACT = "compact"
    const val STANDARD = "standard"
    const val GENEROUS = "generous"
}

object ProfileWeekendPolicy {
    const val AVOID = "avoid"
    const val ALLOW = "allow"
    const val FLEXIBLE = "flexible"
}

object ProfileAssistantTone {
    const val CONCISE = "concise"
    const val WARM = "warm"
    const val COACH = "coach"
}

data class UserProfile(
    val id: String = DEFAULT_PROFILE_ID,
    val version: Int = 1,
    val scenario: String = ProfileScenarios.UNSPECIFIED,
    val activePeriod: String = "unspecified",
    val planningGranularity: String = PlanningGranularity.BALANCED,
    val reminderStyle: String = "standard",
    val workRhythm: String = "adaptive",
    val bufferPreference: String = ProfileBufferPreference.STANDARD,
    val weekendPolicy: String = ProfileWeekendPolicy.FLEXIBLE,
    val assistantTone: String = ProfileAssistantTone.WARM,
    val timezone: String = ZoneId.systemDefault().id,
    val questionnaireVersion: Int = 0,
    val questionnaireCompleted: Boolean = false,
    val learningConsent: Boolean = false,
    val explicitFields: Set<String> = emptySet(),
    val updatedAt: String = OffsetDateTime.now().toString(),
)

data class ProfileSignalStat(
    val metric: String,
    val option: String,
    val count: Int,
    val score: Double,
    val updatedAt: String = OffsetDateTime.now().toString(),
)

data class UserProfileContext(
    val version: Int,
    @SerializedName("consent_granted") val learningConsent: Boolean,
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

fun UserProfile.toContext(): UserProfileContext = UserProfileContext(
    version = version,
    learningConsent = learningConsent,
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

const val DEFAULT_PROFILE_ID = "local-user"
const val CURRENT_QUESTIONNAIRE_VERSION = 2
