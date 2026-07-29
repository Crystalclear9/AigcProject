package com.suishouban.app.data.repository

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
}
