package com.suishouban.app.mascot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MascotOverlayControllerTest {
    private val controller = MascotOverlayController()

    @Test
    fun collapsedPlacementLeavesOnlyTwentyFourPixelsVisibleAtTheLeftEdge() {
        val placement = OverlayPlacement(OverlayDockSide.LEFT, verticalFraction = 0.5f)

        val position = controller.windowPosition(
            placement = placement,
            mode = OverlayDisplayMode.COLLAPSED,
            screenWidthPx = 400,
            screenHeightPx = 800,
            density = 1f,
        )

        assertEquals(-20, position.x)
        assertEquals(356, position.y)
    }

    @Test
    fun snappingUsesNearestEdgeAndClampsTheVerticalPosition() {
        val placement = controller.snapPlacement(
            releasedX = 290,
            releasedY = -200,
            screenWidthPx = 360,
            screenHeightPx = 720,
            density = 1f,
        )

        assertEquals(OverlayDockSide.RIGHT, placement.dockSide)
        assertEquals(0.1f, placement.verticalFraction)
    }

    @Test
    fun firstTapExpandsAndSecondTapRequestsActionNavigation() {
        assertEquals(
            OverlayCommand.Expand,
            controller.commandForTap(OverlayDisplayMode.COLLAPSED),
        )
        assertEquals(
            OverlayCommand.OpenCurrentAction,
            controller.commandForTap(OverlayDisplayMode.EXPANDED),
        )
    }

    @Test
    fun overlayCanStartOnlyWhenExplicitlyEnabledPermittedAndNotTemporarilyHidden() {
        assertTrue(controller.canStart(enabled = true, overlayPermissionGranted = true, hiddenUntilMillis = 0L, nowMillis = 1L))
        assertFalse(controller.canStart(enabled = false, overlayPermissionGranted = true, hiddenUntilMillis = 0L, nowMillis = 1L))
        assertFalse(controller.canStart(enabled = true, overlayPermissionGranted = false, hiddenUntilMillis = 0L, nowMillis = 1L))
        assertFalse(controller.canStart(enabled = true, overlayPermissionGranted = true, hiddenUntilMillis = 2L, nowMillis = 1L))
    }
}
