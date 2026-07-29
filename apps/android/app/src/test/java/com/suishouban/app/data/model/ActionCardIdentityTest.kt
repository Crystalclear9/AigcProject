package com.suishouban.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ActionCardIdentityTest {
    @Test
    fun `provider revision keeps candidate identity when transport id changes`() {
        val first = card(id = "draft-1")
        val enhanced = card(id = "provider-99")

        assertEquals(first.candidateIdentity(), enhanced.candidateIdentity())
    }

    @Test
    fun `different deadline produces a different candidate identity`() {
        val first = card(id = "draft-1")
        val changed = card(id = "draft-2", deadline = "2026-08-11T22:00:00+08:00")

        assertNotEquals(first.candidateIdentity(), changed.candidateIdentity())
    }

    private fun card(
        id: String,
        deadline: String = "2026-08-10T22:00:00+08:00",
    ) = ActionCard(
        id = id,
        cardType = "task",
        title = "提交实验报告",
        deadline = deadline,
        location = "学习通",
        sourceText = "请在8月10日22:00前通过学习通提交实验报告",
    )
}
