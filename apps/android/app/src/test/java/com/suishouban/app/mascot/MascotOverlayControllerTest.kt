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
    fun collapsedPlacementLeavesExactlyHalfOfMofeiVisibleAtTheLeftEdge() {
        val placement = OverlayPlacement(OverlayDockSide.LEFT, verticalFraction = 0.5f)

        val position = controller.windowPosition(
            placement = placement,
            mode = OverlayDisplayMode.COLLAPSED,
            screenWidthPx = 400,
            screenHeightPx = 800,
            density = 1f,
        )

        assertEquals(64, controller.collapsedWidthPx(1f))
        assertEquals(64, controller.collapsedHeightPx(1f))
        assertEquals(-32, position.x)
        assertEquals(368, position.y)
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
    fun doubleTapArbiterDefersSingleAndOpensAppOnSecondTap() {
        val arbiter = OverlayTapArbiter(doubleTapTimeoutMillis = 280)

        assertEquals(OverlayTapDisposition.DeferSingle, arbiter.registerTap(1_000))
        assertFalse(arbiter.consumeSingle(1_200))
        assertEquals(OverlayTapDisposition.OpenApp, arbiter.registerTap(1_220))
        assertFalse(arbiter.consumeSingle(1_600))
    }

    @Test
    fun doubleTapArbiterConsumesSingleAfterTimeoutAndCancelsForDrag() {
        val arbiter = OverlayTapArbiter(doubleTapTimeoutMillis = 280)

        arbiter.registerTap(2_000)
        assertTrue(arbiter.consumeSingle(2_281))
        arbiter.registerTap(3_000)
        arbiter.cancel()
        assertFalse(arbiter.consumeSingle(3_500))
    }

    @Test
    fun rootDragLayerClaimsTheCollapsedWindowAndOnlyExpandedMofeiBody() {
        assertTrue(
            controller.shouldCaptureRootGesture(
                mode = OverlayDisplayMode.COLLAPSED,
                dockSide = OverlayDockSide.LEFT,
                localX = 8f,
                localY = 8f,
                windowWidthPx = 64,
                windowHeightPx = 64,
                density = 1f,
            ),
        )
        assertTrue(
            controller.shouldCaptureRootGesture(
                mode = OverlayDisplayMode.EXPANDED,
                dockSide = OverlayDockSide.LEFT,
                localX = 32f,
                localY = 95f,
                windowWidthPx = 210,
                windowHeightPx = 190,
                density = 1f,
            ),
        )
        assertFalse(
            controller.shouldCaptureRootGesture(
                mode = OverlayDisplayMode.EXPANDED,
                dockSide = OverlayDockSide.LEFT,
                localX = 108f,
                localY = 95f,
                windowWidthPx = 210,
                windowHeightPx = 190,
                density = 1f,
            ),
        )
    }

    @Test
    fun compactArcUsesNarrowEdgeWindowAndMirrorsAtRightEdge() {
        assertEquals(210, controller.expandedWidthPx(1f))
        assertEquals(190, controller.expandedHeightPx(1f))
        assertFalse(controller.shouldMirrorCompactRing(OverlayDockSide.LEFT))
        assertTrue(controller.shouldMirrorCompactRing(OverlayDockSide.RIGHT))
        assertEquals(
            190,
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
