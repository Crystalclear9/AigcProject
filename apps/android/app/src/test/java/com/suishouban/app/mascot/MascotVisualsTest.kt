package com.suishouban.app.mascot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MascotVisualsTest {
    @Test
    fun everyMoodHasAnAccessibleDistinctVisualProfile() {
        MascotMood.entries.forEach { mood ->
            val state = MascotState(
                mood = mood,
                userMessage = "状态消息",
                colorRole = MascotColorRole.DEFAULT,
                animationHint = MascotAnimationHint.BREATHE,
            )

            val visual = MascotVisuals.profileFor(state, reduceMotion = false)

            assertTrue("$mood should expose a color", visual.primaryArgb != 0L)
            assertTrue("$mood should name Mofei for accessibility", visual.contentDescription.startsWith("墨斐，"))
            assertTrue("$mood should preserve the actionable message", visual.message.contains("状态消息"))
        }
    }

    @Test
    fun urgentMoodUsesCoralAlertProfile() {
        val visual = MascotVisuals.profileFor(
            MascotState(
                mood = MascotMood.URGENT,
                userMessage = "报告已逾期",
                colorRole = MascotColorRole.URGENT,
                animationHint = MascotAnimationHint.ALERT_PULSE,
            ),
            reduceMotion = false,
        )

        assertEquals(MofeiPalette.CORAL_ALERT, visual.primaryArgb)
        assertEquals(MofeiMotion.ALERT_PULSE, visual.motion)
        assertTrue(visual.contentDescription.contains("紧急"))
    }

    @Test
    fun reducedMotionKeepsStateColorButStopsContinuousMotion() {
        val active = MascotState(
            mood = MascotMood.FOCUS,
            userMessage = "正在识别行动事项",
            colorRole = MascotColorRole.FOCUS,
            animationHint = MascotAnimationHint.SCAN,
        )

        val animated = MascotVisuals.profileFor(active, reduceMotion = false)
        val reduced = MascotVisuals.profileFor(active, reduceMotion = true)

        assertEquals(animated.primaryArgb, reduced.primaryArgb)
        assertEquals(MofeiMotion.STILL, reduced.motion)
        assertFalse(animated.motion == MofeiMotion.STILL)
    }
}
