package com.suishouban.app.ui.components

import com.suishouban.app.data.model.Priority
import com.suishouban.app.data.model.reminderNodeFromLegacy
import com.suishouban.app.ui.theme.visualForPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TemporalAndPriorityModelTest {
    @Test
    fun `combined reminder wheel value preserves every unit`() {
        assertEquals(1 * 1440 + 3 * 60 + 30, normalizedReminderMinutes("截止前1天3小时30分钟"))
    }

    @Test
    fun `legacy reminder keeps exact adjacent minutes`() {
        assertEquals(1L, reminderNodeFromLegacy("截止前1分钟").offsetMinutes)
        assertEquals(2L, reminderNodeFromLegacy("截止前2分钟").offsetMinutes)
    }

    @Test
    fun `priority levels have distinct card containers and accents`() {
        val high = visualForPriority(Priority.HIGH)
        val normal = visualForPriority(Priority.NORMAL)
        val low = visualForPriority(Priority.LOW)

        assertNotEquals(high.container, normal.container)
        assertNotEquals(normal.container, low.container)
        assertNotEquals(high.accent, low.accent)
    }
}
