package com.suishouban.app.data.repository

import com.suishouban.app.data.local.ProfileSignalStatEntity
import com.suishouban.app.data.local.UserProfileDao
import com.suishouban.app.data.local.UserProfileEntity
import com.suishouban.app.data.local.toEntity
import com.suishouban.app.data.model.PlanningGranularity
import com.suishouban.app.data.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UserProfileRepositoryTest {
    @Test
    fun genericProfileKeepsLearningDisabled() {
        val profile = UserProfileRepository.genericProfile()
        assertFalse(profile.learningConsent)
        assertFalse(profile.questionnaireCompleted)
        assertEquals(PlanningGranularity.BALANCED, profile.planningGranularity)
    }

    @Test
    fun inferredPreferenceRequiresThreeConsistentSignals() = runBlocking {
        val dao = FakeUserProfileDao()
        val repository = UserProfileRepository(dao)
        repository.initializeIfNeeded()
        repository.setLearningConsent(true)

        repeat(2) {
            repository.recordSignal("planning_granularity", PlanningGranularity.DETAILED)
        }
        assertEquals(PlanningGranularity.BALANCED, repository.current().planningGranularity)

        repository.recordSignal("planning_granularity", PlanningGranularity.DETAILED)
        assertEquals(PlanningGranularity.DETAILED, repository.current().planningGranularity)
    }

    @Test
    fun explicitQuestionnaireValueIsNeverOverwrittenBySignals() = runBlocking {
        val dao = FakeUserProfileDao()
        val repository = UserProfileRepository(dao)
        repository.completeQuestionnaire(
            scenario = "study",
            activePeriod = "evening",
            planningGranularity = PlanningGranularity.CONCISE,
            reminderStyle = "light",
            workRhythm = "steady",
            bufferPreference = "generous",
            weekendPolicy = "avoid",
            assistantTone = "coach",
            learningConsent = true,
            explicitFields = setOf(
                "scenario",
                "active_period",
                "planning_granularity",
                "reminder_style",
                "work_rhythm",
                "buffer_preference",
                "weekend_policy",
                "assistant_tone",
            ),
        )

        repeat(5) {
            repository.recordSignal("planning_granularity", PlanningGranularity.DETAILED)
        }
        assertEquals(PlanningGranularity.CONCISE, repository.current().planningGranularity)
        assertEquals(0, dao.stats.size)
    }

    @Test
    fun skippedAnswersRemainDefaultsAndAreNotMarkedExplicit() = runBlocking {
        val repository = UserProfileRepository(FakeUserProfileDao())

        val profile = repository.completeQuestionnaire(
            scenario = "study",
            activePeriod = "unspecified",
            planningGranularity = PlanningGranularity.BALANCED,
            reminderStyle = "standard",
            workRhythm = "adaptive",
            bufferPreference = "standard",
            weekendPolicy = "flexible",
            assistantTone = "warm",
            learningConsent = false,
            explicitFields = setOf("scenario"),
        )

        assertEquals(setOf("scenario"), profile.explicitFields)
        assertEquals("unspecified", profile.activePeriod)
    }
}

private class FakeUserProfileDao : UserProfileDao {
    private val profileFlow = MutableStateFlow<UserProfileEntity?>(null)
    val stats = mutableListOf<ProfileSignalStatEntity>()

    override fun observe(id: String): Flow<UserProfileEntity?> = profileFlow

    override suspend fun find(id: String): UserProfileEntity? =
        profileFlow.value?.takeIf { it.id == id }

    override suspend fun upsert(profile: UserProfileEntity) {
        profileFlow.value = profile
    }

    override suspend fun deleteAllProfiles() {
        profileFlow.value = null
    }

    override suspend fun allStats(): List<ProfileSignalStatEntity> = stats.toList()

    override suspend fun findStat(metric: String, option: String): ProfileSignalStatEntity? =
        stats.firstOrNull { it.metric == metric && it.option == option }

    override suspend fun upsertStat(stat: ProfileSignalStatEntity) {
        stats.removeAll { it.metric == stat.metric && it.option == stat.option }
        stats += stat
    }

    override suspend fun clearStats() {
        stats.clear()
    }
}
