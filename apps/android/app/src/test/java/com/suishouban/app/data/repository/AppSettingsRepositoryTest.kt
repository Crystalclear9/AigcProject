package com.suishouban.app.data.repository

import com.suishouban.app.data.model.AiConnectionMode
import com.suishouban.app.data.model.AutoReactPolicy
import com.suishouban.app.data.model.OcrEnhancementPolicy
import com.suishouban.app.data.model.ReminderPreset
import com.suishouban.app.data.model.WorkflowDepthPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsRepositoryTest {
    @Test
    fun `stale cloud preference cannot enable cloud mode without a gateway`() {
        assertFalse(isCloudModeEnabled("", true))
    }

    @Test
    fun `accepted public https gateway enables cloud mode`() {
        assertTrue(isCloudModeEnabled("https://workflow.example.com/", true))
    }

    @Test
    fun `provider endpoint and disabled preference cannot enable cloud mode`() {
        assertFalse(
            isCloudModeEnabled(
                "https://api-ai.vivo.com.cn/v1/chat/completions",
                true,
            )
        )
        assertFalse(isCloudModeEnabled("https://workflow.example.com/", false))
    }

    @Test
    fun `advanced settings round trip without storing provider secret`() {
        val preferences = InMemorySharedPreferences()
        val repository = AppSettingsRepository(preferences)
        repository.update(
            repository.settings.value.copy(
                aiConnectionMode = AiConnectionMode.DIRECT_API,
                reminderPreset = ReminderPreset.MULTI_STAGE,
                maxSuggestedReminders = 5,
                ocrEnhancementPolicy = OcrEnhancementPolicy.ALWAYS_COMPARE,
                workflowDepthPolicy = WorkflowDepthPolicy.DEEP,
                autoReactPolicy = AutoReactPolicy.COMPLEX_TASKS,
                webRetrievalEnabled = true,
                originalImageRetentionDays = 7,
            )
        )

        val restored = AppSettingsRepository(preferences).settings.value
        assertEquals(AiConnectionMode.DIRECT_API, restored.aiConnectionMode)
        assertEquals(ReminderPreset.MULTI_STAGE, restored.reminderPreset)
        assertEquals(5, restored.maxSuggestedReminders)
        assertEquals(OcrEnhancementPolicy.ALWAYS_COMPARE, restored.ocrEnhancementPolicy)
        assertEquals(WorkflowDepthPolicy.DEEP, restored.workflowDepthPolicy)
        assertEquals(AutoReactPolicy.COMPLEX_TASKS, restored.autoReactPolicy)
        assertTrue(restored.webRetrievalEnabled)
        assertEquals(7, restored.originalImageRetentionDays)
        assertFalse(preferences.all.keys.any { it.contains("key", ignoreCase = true) })
    }

    @Test
    fun `repository normalizes coupled screenshot settings`() {
        val repository = AppSettingsRepository(InMemorySharedPreferences())
        repository.update(
            repository.settings.value.copy(
                autoDetectScreenshots = true,
                importSources = repository.settings.value.importSources.copy(screenshots = false),
                keepOriginalScreenshot = true,
                originalImageRetentionDays = 0,
            )
        )

        val normalized = repository.settings.value
        assertFalse(normalized.autoDetectScreenshots)
        assertFalse(normalized.keepOriginalScreenshot)
    }
}
