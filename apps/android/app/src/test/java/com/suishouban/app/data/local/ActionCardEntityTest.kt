package com.suishouban.app.data.local

import com.suishouban.app.data.model.ActionCard
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionCardEntityTest {
    @Test
    fun actionGraphEvidenceSurvivesLocalStorageMapping() {
        val card = ActionCard(
            id = "card-1",
            actionId = "action-1",
            dependencies = listOf("action-before"),
            evidenceSummary = listOf("证据：6月10日22:00前提交实验报告"),
            title = "提交实验报告",
            sourceText = "请在6月10日22:00前提交实验报告",
        )

        val restored = card.toEntity().toDomain()

        assertEquals("action-1", restored.actionId)
        assertEquals(listOf("action-before"), restored.dependencies)
        assertEquals(listOf("证据：6月10日22:00前提交实验报告"), restored.evidenceSummary)
    }
}
