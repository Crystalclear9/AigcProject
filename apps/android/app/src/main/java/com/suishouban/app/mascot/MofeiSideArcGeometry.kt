package com.suishouban.app.mascot

import kotlin.math.cos
import kotlin.math.sin

data class MofeiArcPoint(val x: Float, val y: Float)

/** Shared dp-space geometry for the compact in-app and WindowManager side arcs. */
object MofeiSideArcGeometry {
    const val WIDTH_DP = 184f
    const val HEIGHT_DP = 276f
    const val ACTION_SIZE_DP = 42f
    const val MASCOT_SIZE_DP = 64f
    const val MASCOT_CENTER_X_DP = MASCOT_SIZE_DP / 2f
    const val ARC_CENTER_X_DP = 55f
    const val CENTER_Y_DP = HEIGHT_DP / 2f
    const val RADIUS_DP = 108f

    fun actionCenters(dockSide: OverlayDockSide, count: Int): List<MofeiArcPoint> {
        if (count <= 0) return emptyList()
        return List(count) { index ->
            val progress = if (count == 1) 0.5 else index.toDouble() / (count - 1)
            // Coordinates align icon hit targets with the restrained generated glass sockets.
            val angleDegrees = -68.0 + 136.0 * progress
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
