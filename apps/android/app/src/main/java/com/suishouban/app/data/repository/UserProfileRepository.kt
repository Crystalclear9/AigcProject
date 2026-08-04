package com.suishouban.app.data.repository

import com.suishouban.app.data.local.ProfileSignalStatEntity
import com.suishouban.app.data.local.UserProfileDao
import com.suishouban.app.data.local.toDomain
import com.suishouban.app.data.local.toEntity
import com.suishouban.app.data.model.DEFAULT_PROFILE_ID
import com.suishouban.app.data.model.CURRENT_QUESTIONNAIRE_VERSION
import com.suishouban.app.data.model.PlanningGranularity
import com.suishouban.app.data.model.ProfileScenarios
import com.suishouban.app.data.model.UserProfile
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class UserProfileRepository(private val dao: UserProfileDao) {
    fun observe(): Flow<UserProfile> = dao.observe(DEFAULT_PROFILE_ID).map { entity ->
        entity?.toDomain() ?: genericProfile()
    }

    suspend fun current(): UserProfile {
        val existing = dao.find(DEFAULT_PROFILE_ID)?.toDomain()
        if (existing != null) return existing
        return genericProfile().also { dao.upsert(it.toEntity()) }
    }

    suspend fun initializeIfNeeded(): UserProfile = current()

    suspend fun saveExplicit(profile: UserProfile): UserProfile {
        val normalized = profile.copy(
            id = DEFAULT_PROFILE_ID,
            version = profile.version + 1,
            updatedAt = OffsetDateTime.now().toString(),
        )
        dao.upsert(normalized.toEntity())
        return normalized
    }

    suspend fun completeQuestionnaire(
        scenario: String,
        activePeriod: String,
        planningGranularity: String,
        reminderStyle: String,
        workRhythm: String,
        bufferPreference: String,
        weekendPolicy: String,
        assistantTone: String,
        learningConsent: Boolean,
        explicitFields: Set<String>,
    ): UserProfile {
        val current = current()
        val allowedExplicitFields = setOf(
            "scenario",
            "active_period",
            "planning_granularity",
            "reminder_style",
            "work_rhythm",
            "buffer_preference",
            "weekend_policy",
            "assistant_tone",
        )
        return saveExplicit(
            current.copy(
                scenario = scenario,
                activePeriod = activePeriod,
                planningGranularity = planningGranularity,
                reminderStyle = reminderStyle,
                workRhythm = workRhythm,
                bufferPreference = bufferPreference,
                weekendPolicy = weekendPolicy,
                assistantTone = assistantTone,
                questionnaireVersion = CURRENT_QUESTIONNAIRE_VERSION,
                questionnaireCompleted = true,
                learningConsent = learningConsent,
                explicitFields = current.explicitFields + (explicitFields intersect allowedExplicitFields),
            )
        )
    }

    suspend fun setLearningConsent(enabled: Boolean): UserProfile =
        saveExplicit(current().copy(learningConsent = enabled))

    suspend fun recordSignal(metric: String, option: String, weight: Double = 1.0) {
        val profile = current()
        if (!profile.learningConsent || metric in profile.explicitFields) return
        val existing = dao.findStat(metric, option)
        val updated = ProfileSignalStatEntity(
            metric = metric,
            option = option,
            count = (existing?.count ?: 0) + 1,
            score = (existing?.score ?: 0.0) + weight.coerceIn(0.0, 1.0),
            updatedAt = OffsetDateTime.now().toString(),
        )
        dao.upsertStat(updated)
        if (updated.count < MIN_SIGNALS || updated.score < MIN_SCORE) return
        val candidates = dao.allStats()
            .filter { it.metric == metric }
            .sortedWith(
                compareByDescending<ProfileSignalStatEntity> { it.score }
                    .thenByDescending { it.count }
            )
        if (candidates.firstOrNull()?.option != option) return
        val inferred = when (metric) {
            "planning_granularity" -> profile.copy(planningGranularity = option)
            "reminder_style" -> profile.copy(reminderStyle = option)
            "active_period" -> profile.copy(activePeriod = option)
            "work_rhythm" -> profile.copy(workRhythm = option)
            "buffer_preference" -> profile.copy(bufferPreference = option)
            "weekend_policy" -> profile.copy(weekendPolicy = option)
            "assistant_tone" -> profile.copy(assistantTone = option)
            else -> profile
        }
        if (inferred != profile) saveInferred(inferred)
    }

    suspend fun clearInferred(): UserProfile {
        dao.clearStats()
        val current = current()
        val generic = genericProfile()
        val reset = current.copy(
            scenario = if ("scenario" in current.explicitFields) current.scenario else generic.scenario,
            activePeriod = if ("active_period" in current.explicitFields) current.activePeriod else generic.activePeriod,
            planningGranularity = if ("planning_granularity" in current.explicitFields) {
                current.planningGranularity
            } else {
                generic.planningGranularity
            },
            reminderStyle = if ("reminder_style" in current.explicitFields) current.reminderStyle else generic.reminderStyle,
            workRhythm = if ("work_rhythm" in current.explicitFields) current.workRhythm else generic.workRhythm,
            bufferPreference = if ("buffer_preference" in current.explicitFields) {
                current.bufferPreference
            } else {
                generic.bufferPreference
            },
            weekendPolicy = if ("weekend_policy" in current.explicitFields) {
                current.weekendPolicy
            } else {
                generic.weekendPolicy
            },
            assistantTone = if ("assistant_tone" in current.explicitFields) {
                current.assistantTone
            } else {
                generic.assistantTone
            },
        )
        return saveInferred(reset)
    }

    suspend fun resetAll(): UserProfile {
        dao.clearStats()
        dao.deleteAllProfiles()
        return genericProfile().also { dao.upsert(it.toEntity()) }
    }

    private suspend fun saveInferred(profile: UserProfile): UserProfile {
        val updated = profile.copy(
            version = profile.version + 1,
            updatedAt = OffsetDateTime.now().toString(),
        )
        dao.upsert(updated.toEntity())
        return updated
    }

    companion object {
        const val MIN_SIGNALS = 3
        const val MIN_SCORE = 2.4

        fun genericProfile(): UserProfile = UserProfile(
            scenario = ProfileScenarios.UNSPECIFIED,
            activePeriod = "unspecified",
            planningGranularity = PlanningGranularity.BALANCED,
            reminderStyle = "standard",
            workRhythm = "adaptive",
            timezone = ZoneId.systemDefault().id,
            questionnaireCompleted = false,
            learningConsent = false,
        )
    }
}
