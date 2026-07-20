package com.suishouban.app.mascot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.suishouban.app.mascot.action.MofeiAction
import com.suishouban.app.mascot.action.MofeiActionCommand

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
    fun firstTapExpandsAndOutsideTapCollapsesTheActionRing() {
        assertEquals(
            OverlayCommand.Expand,
            controller.commandForTap(OverlayDisplayMode.COLLAPSED),
        )
        assertEquals(
            OverlayCommand.Collapse,
            controller.commandForTap(OverlayDisplayMode.EXPANDED),
        )
    }

    @Test
    fun compactArcUsesNarrowEdgeWindowAndMirrorsAtRightEdge() {
        assertEquals(176, controller.expandedWidthPx(1f))
        assertEquals(276, controller.expandedHeightPx(1f))
        assertFalse(controller.shouldMirrorCompactRing(OverlayDockSide.LEFT))
        assertTrue(controller.shouldMirrorCompactRing(OverlayDockSide.RIGHT))
        assertEquals(
            224,
            controller.windowPosition(
                OverlayPlacement(OverlayDockSide.RIGHT, 0.5f),
                OverlayDisplayMode.EXPANDED,
                400,
                800,
                1f,
            ).x,
        )
    }

    @Test
    fun overlayActionUsesTheSharedCommandMapping() {
        assertEquals(
            MofeiActionCommand.RequestScreenCapture,
            controller.commandForAction(MofeiAction.CAPTURE_CURRENT_SCREEN, null),
        )
        assertEquals(
            MofeiActionCommand.OpenCard("card-9"),
            controller.commandForAction(MofeiAction.OPEN_CURRENT_CARD, "card-9"),
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
