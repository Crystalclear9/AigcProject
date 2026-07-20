package com.suishouban.app.mascot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingMascotControllerTest {
    private val controller = FloatingMascotController()

    @Test
    fun snapsToNearestEdgeFromCenter() {
        assertEquals(OverlayDockSide.LEFT, controller.snapDockSide(petCenterXPx = 100f, screenWidthPx = 1080))
        assertEquals(OverlayDockSide.RIGHT, controller.snapDockSide(petCenterXPx = 900f, screenWidthPx = 1080))
    }

    @Test
    fun verticalFractionIsClampedAwayFromBars() {
        assertEquals(FloatingMascotController.MIN_VERTICAL_FRACTION, controller.verticalFraction(-50f, 2000), 0.0001f)
        assertEquals(FloatingMascotController.MAX_VERTICAL_FRACTION, controller.verticalFraction(9999f, 2000), 0.0001f)
        assertEquals(0.5f, controller.verticalFraction(1000f, 2000), 0.0001f)
    }

    @Test
    fun verticalFractionFallsBackWhenTrackIsUnmeasured() {
        assertEquals(FloatingMascotController.DEFAULT_VERTICAL_FRACTION, controller.verticalFraction(100f, 0), 0.0001f)
    }

    @Test
    fun restingXHugsTheDockedEdge() {
        assertEquals(8f, controller.restingXPx(OverlayDockSide.LEFT, trackWidthPx = 1000, marginPx = 8f), 0.0001f)
        assertEquals(992f, controller.restingXPx(OverlayDockSide.RIGHT, trackWidthPx = 1000, marginPx = 8f), 0.0001f)
    }

    @Test
    fun bubbleGrowsTowardScreenCenter() {
        // Docked right: bubble sits to the LEFT of the pet.
        val rightBubble = controller.bubbleXPx(OverlayDockSide.RIGHT, petXPx = 900f, petWidthPx = 76f, bubbleWidthPx = 200f, gapPx = 10f)
        assertTrue("right-docked bubble opens leftward", rightBubble < 900f)
        // Docked left: bubble sits to the RIGHT of the pet.
        val leftBubble = controller.bubbleXPx(OverlayDockSide.LEFT, petXPx = 8f, petWidthPx = 76f, bubbleWidthPx = 200f, gapPx = 10f)
        assertEquals(8f + 76f + 10f, leftBubble, 0.0001f)
    }

    @Test
    fun openActionOnlyWhenStatePointsAtACard() {
        val withCard = MascotState(
            mood = MascotMood.URGENT,
            actionCardId = "card-1",
            userMessage = "报告将到期",
            colorRole = MascotColorRole.URGENT,
            animationHint = MascotAnimationHint.ALERT_PULSE,
        )
        val withoutCard = withCard.copy(actionCardId = null)
        assertTrue(controller.showsOpenAction(withCard))
        assertFalse(controller.showsOpenAction(withoutCard))
    }
}
