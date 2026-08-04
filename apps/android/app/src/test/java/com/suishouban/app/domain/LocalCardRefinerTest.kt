package com.suishouban.app.domain

import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.PlanningGranularity
import com.suishouban.app.data.model.ProfileScenarios
import com.suishouban.app.data.model.UserProfile
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCardRefinerTest {
    @Test
    fun noDeadlineCreatesRelativePlanWithoutReminderOrBlockingConflict() {
        val plan = LocalCardRefiner.refine(
            card = card(deadline = null),
            options = LocalRefinementOptions(),
            profile = null,
        )

        assertTrue(plan.items.isNotEmpty())
        assertTrue(plan.items.all { it.startTime == null && it.deadline == null })
        assertTrue(plan.items.all { !it.reminderEnabled })
        assertTrue(plan.items.all { it.needConfirm.isEmpty() })
        assertTrue(plan.warnings.any { it.contains("截止时间") })
    }

    @Test
    fun profileChangesGranularityWithoutChangingParentFacts() {
        val parent = card(OffsetDateTime.now().plusDays(4).toString())
        val concise = LocalCardRefiner.refine(
            parent,
            LocalRefinementOptions(),
            UserProfile(
                scenario = ProfileScenarios.STUDY,
                planningGranularity = PlanningGranularity.CONCISE,
            ),
        )
        val detailed = LocalCardRefiner.refine(
            parent,
            LocalRefinementOptions(),
            UserProfile(
                scenario = ProfileScenarios.STUDY,
                planningGranularity = PlanningGranularity.DETAILED,
            ),
        )

        assertTrue(detailed.items.size > concise.items.size)
        assertEquals(parent.id, concise.parentCardId)
        assertEquals(parent.id, detailed.parentCardId)
        assertEquals(parent.title, concise.objective)
        assertEquals(parent.title, detailed.objective)
        assertFalse(detailed.items.any { it.deadline?.let { value -> value > parent.deadline.orEmpty() } == true })
    }

    @Test
    fun attachmentEvidenceIsRecordedButDoesNotReplaceObjective() {
        val parent = card(OffsetDateTime.now().plusDays(2).toString())
        val plan = LocalCardRefiner.refine(
            parent,
            LocalRefinementOptions(),
            null,
            attachmentEvidence = "评分标准要求提交 PDF 和数据表",
        )

        assertEquals(parent.title, plan.objective)
        assertTrue(plan.evidenceSummary.any { it.contains("评分标准") })
        assertTrue(plan.items.first().description.contains("附件要点"))
    }

    private fun card(deadline: String?) = ActionCard(
        id = "card-refinement-unit",
        cardType = "task",
        title = "提交课程研究报告",
        summary = "完成报告并上传学习通",
        deadline = deadline,
        location = "学习通",
        materials = listOf("研究报告 PDF", "数据表"),
        submitMethod = "学习通",
        sourceText = "课程通知",
    )
}
