package com.suishouban.app.mascot

import kotlin.math.cos
import kotlin.math.sin

data class MofeiArcPoint(val x: Float, val y: Float)

/** Shared dp-space geometry for the compact in-app and WindowManager side arcs. */
object MofeiSideArcGeometry {
    // The track itself is narrow and centered on Mofei. Extra transparent width is reserved for
    // the one-at-a-time action hint so labels do not push the arc away from the mascot.
    const val WIDTH_DP = 210f
    const val TRACK_WIDTH_DP = 132f
    const val HEIGHT_DP = 190f
    const val ACTION_SIZE_DP = 38f
    const val MASCOT_SIZE_DP = 64f
    const val MASCOT_CENTER_X_DP = MASCOT_SIZE_DP / 2f
    const val ARC_CENTER_X_DP = MASCOT_CENTER_X_DP
    const val CENTER_Y_DP = HEIGHT_DP / 2f
    const val RADIUS_DP = 76f

    fun actionCenters(dockSide: OverlayDockSide, count: Int): List<MofeiArcPoint> {
        if (count <= 0) return emptyList()
        return List(count) { index ->
            val progress = if (count == 1) 0.5 else index.toDouble() / (count - 1)
            // A full 180-degree fan keeps seven 38dp targets separate at this close radius.
            val angleDegrees = -90.0 + 180.0 * progress
            val angle = Math.toRadians(angleDegrees)
            val leftX = ARC_CENTER_X_DP + cos(angle).toFloat() * RADIUS_DP
            MofeiArcPoint(
                x = if (dockSide == OverlayDockSide.LEFT) leftX else WIDTH_DP - leftX,
                y = CENTER_Y_DP + sin(angle).toFloat() * RADIUS_DP,
            )
        }
    }

    fun mascotCenter(dockSide: OverlayDockSide): MofeiArcPoint = MofeiArcPoint(
        x = if (dockSide == OverlayDockSide.LEFT) MASCOT_CENTER_X_DP else WIDTH_DP - MASCOT_CENTER_X_DP,
        y = CENTER_Y_DP,
    )
}
