package com.suishouban.app.reminder

import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardTypes
import java.time.Duration
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderSchedulerPolicyTest {
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-06-16T12:00:00+08:00")

    @Test
    fun deadlineMoreThanOneDayGetsThreeReminders() {
        val offsets = ReminderScheduler.smartDeadlineOffsets(now, now.plusDays(2))

        assertEquals(listOf(Duration.ofDays(1), Duration.ofHours(3), Duration.ofMinutes(30)), offsets)
    }

    @Test
    fun deadlineWithinOneDayGetsThreeHourAndThirtyMinuteReminders() {
        val offsets = ReminderScheduler.smartDeadlineOffsets(now, now.plusHours(12))

        assertEquals(listOf(Duration.ofHours(3), Duration.ofMinutes(30)), offsets)
    }

    @Test
    fun deadlineWithinThreeHoursGetsThirtyMinuteReminder() {
        val offsets = ReminderScheduler.smartDeadlineOffsets(now, now.plusHours(2))

        assertEquals(listOf(Duration.ofMinutes(30)), offsets)
    }

    @Test
    fun nearDeadlineGetsImmediateReminder() {
        val offsets = ReminderScheduler.smartDeadlineOffsets(now, now.plusMinutes(20))

        assertEquals(listOf(Duration.ZERO), offsets)
    }

    @Test
    fun existingDeadlineRemindersAreMergedAndDeduplicated() {
        val card = ActionCard(
            id = "card-1",
            cardType = CardTypes.TASK,
            title = "提交实验报告",
            deadline = now.plusDays(2).toString(),
            reminders = listOf("截止前 1 天", "截止前 3 小时"),
        )

        val offsets = ReminderScheduler.reminderOffsetsFor(card, now, now.plusDays(2))

        assertEquals(listOf(Duration.ofDays(1), Duration.ofHours(3), Duration.ofMinutes(30)), offsets)
    }

    @Test
    fun eventWithoutDeadlineKeepsEventReminderPolicy() {
        val card = ActionCard(
            id = "event-1",
            cardType = CardTypes.EVENT,
            title = "组会",
            startTime = now.plusHours(4).toString(),
        )

        val offsets = ReminderScheduler.reminderOffsetsFor(card, now, now.plusHours(4))

        assertTrue(Duration.ofMinutes(30) in offsets)
    }
}
