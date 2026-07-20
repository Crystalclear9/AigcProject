package com.suishouban.app.mascot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MofeiSideArcGeometryTest {
    @Test
    fun sevenActionsStayInsideTheCompactSideArc() {
        val points = MofeiSideArcGeometry.actionCenters(OverlayDockSide.LEFT, count = 7)
        val halfAction = MofeiSideArcGeometry.ACTION_SIZE_DP / 2f

        assertEquals(7, points.size)
        points.forEach { point ->
            assertTrue(point.x >= halfAction)
            assertTrue(point.x <= MofeiSideArcGeometry.WIDTH_DP - halfAction)
            assertTrue(point.y >= halfAction)
            assertTrue(point.y <= MofeiSideArcGeometry.HEIGHT_DP - halfAction)
        }
    }

    @Test
    fun rightDockMirrorsTheLeftDockWithoutMovingVertically() {
        val left = MofeiSideArcGeometry.actionCenters(OverlayDockSide.LEFT, count = 7)
        val right = MofeiSideArcGeometry.actionCenters(OverlayDockSide.RIGHT, count = 7)

        left.zip(right).forEach { (leftPoint, rightPoint) ->
            assertEquals(MofeiSideArcGeometry.WIDTH_DP, leftPoint.x + rightPoint.x, 0.001f)
            assertEquals(leftPoint.y, rightPoint.y, 0.001f)
        }
    }
}
