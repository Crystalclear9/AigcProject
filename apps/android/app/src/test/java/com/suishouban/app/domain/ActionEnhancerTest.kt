package com.suishouban.app.domain

import com.suishouban.app.data.model.CardTypes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionEnhancerTest {
    private val enhancer: ActionEnhancer = LocalRuleActionEnhancer()

    @Test
    fun localRuleEnhancerKeepsOfflineWorkflowUsable() = runBlocking {
        val result = enhancer.enhance(
            ActionEnhancementInput(
                ocrText = "请在 2026 / 06 / 21 18 : 30 前上传 PPT 和团队信息表，提交至官网报名通道。",
                source = "mlkit",
            )
        )

        assertEquals(1, result.cards.size)
        assertEquals(CardTypes.TASK, result.cards.first().cardType)
        assertTrue(result.cards.first().deadline?.startsWith("2026-06-21T18:30") == true)
        assertTrue(result.cards.first().materials.contains("团队信息表"))
    }
}
