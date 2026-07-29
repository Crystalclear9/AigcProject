package com.suishouban.app.domain.workflow

import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.Priority
import com.suishouban.app.data.model.PriorityModes
import com.suishouban.app.data.model.ReminderModes
import com.suishouban.app.data.model.ReminderNode
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowCoordinatorsTest {
    @Test
    fun `manual priority survives remote merge`() {
        val local = ActionCard(
            id = "card-1",
            title = "提交报告",
            priority = Priority.HIGH,
            priorityMode = PriorityModes.MANUAL,
            priorityLocked = true,
        )
        val remote = local.copy(
            priority = Priority.LOW,
            priorityMode = PriorityModes.ADAPTIVE,
            priorityLocked = false,
        )

        val merged = PriorityCoordinator.mergeRemote(local, remote)

        assertEquals(Priority.HIGH, merged.priority)
        assertTrue(merged.priorityLocked)
    }

    @Test
    fun `confirmation blocks invalid temporal ordering and reminders`() {
        val now = OffsetDateTime.parse("2026-07-30T10:00:00+08:00")
        val card = ActionCard(
            title = "提交报告",
            startTime = "2026-07-30T11:02:00+08:00",
            endTime = "2026-07-30T11:01:00+08:00",
            deadline = "2026-07-30T12:00:00+08:00",
            reminderNodes = listOf(
                ReminderNode(
                    mode = ReminderModes.ABSOLUTE,
                    absoluteTime = "2026-07-30T12:01:00+08:00",
                )
            ),
        )

        val errors = TemporalCoordinator.validationErrors(card, now)

        assertTrue(errors.any { "结束时间" in it })
        assertTrue(errors.any { "晚于截止时间" in it })
    }
}
