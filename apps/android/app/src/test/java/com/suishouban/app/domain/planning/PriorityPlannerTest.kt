package com.suishouban.app.domain.planning

import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.Priority
import com.suishouban.app.data.model.PriorityModes
import com.suishouban.app.data.model.WorkspaceTypes
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityPlannerTest {
    private val now = OffsetDateTime.parse("2026-07-29T10:00:00+08:00")

    @Test
    fun manualPriorityIsNeverOverwritten() {
        val result = PriorityPlanner.calibrate(
            ActionCard(
                title = "手动低优先级",
                deadline = "2026-07-29T11:00:00+08:00",
                priority = Priority.LOW,
                priorityMode = PriorityModes.MANUAL,
                priorityLocked = true,
            ),
            now,
        )

        assertEquals(Priority.LOW, result.priority)
        assertEquals(PriorityModes.MANUAL, result.priorityMode)
        assertTrue(result.priorityLocked)
    }

    @Test
    fun imminentTeamDeliveryBecomesHighPriorityWithReason() {
        val result = PriorityPlanner.calibrate(
            ActionCard(
                title = "提交团队方案",
                deadline = "2026-07-29T14:00:00+08:00",
                workspaceType = WorkspaceTypes.TEAM,
                dependencies = listOf("collect-data"),
                deliverables = listOf("最终方案.pdf"),
            ),
            now,
        )

        assertEquals(Priority.HIGH, result.priority)
        assertTrue(result.priorityScore >= 70)
        assertTrue(result.priorityReason.contains("团队"))
        assertFalse(result.priorityLocked)
    }
}
