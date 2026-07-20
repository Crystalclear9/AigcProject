package com.suishouban.app.mascot

import com.suishouban.app.mascot.action.MofeiAction
import com.suishouban.app.mascot.action.MofeiActionCommand

/** Persisted docking side for the system overlay. */
enum class OverlayDockSide { LEFT, RIGHT }

/** The overlay has one compact resting form and one intentionally small preview. */
enum class OverlayDisplayMode { COLLAPSED, EXPANDED }

/** Commands are platform independent so placement and gestures remain JVM-testable. */
enum class OverlayCommand { Expand, Collapse, ShowControls }

data class OverlayPlacement(
    val dockSide: OverlayDockSide,
    val verticalFraction: Float,
)

data class OverlayWindowPosition(val x: Int, val y: Int)

/**
 * Pure edge-overlay policy. Pixel conversion happens at the service boundary so the policy can
 * cover snap behavior without an Android [android.view.WindowManager] test fixture.
 */
class MascotOverlayController {
    fun canStart(
        enabled: Boolean,
        overlayPermissionGranted: Boolean,
        hiddenUntilMillis: Long,
        nowMillis: Long,
    ): Boolean = enabled && overlayPermissionGranted && hiddenUntilMillis <= nowMillis

    fun commandForTap(mode: OverlayDisplayMode): OverlayCommand = when (mode) {
        OverlayDisplayMode.COLLAPSED -> OverlayCommand.Expand
        OverlayDisplayMode.EXPANDED -> OverlayCommand.Collapse
    }

    fun commandForLongPress(): OverlayCommand = OverlayCommand.ShowControls

    fun commandForAction(action: MofeiAction, cardId: String?): MofeiActionCommand =
        MofeiActionCommand.forAction(action, cardId)

    fun shouldMirrorCompactRing(dockSide: OverlayDockSide): Boolean = dockSide == OverlayDockSide.RIGHT

    /**
     * Snaps to the nearest side and stores a normalized center position. The vertical range is
     * deliberately restricted, keeping the control away from system bars after a stale restore.
     */
    fun snapPlacement(
        releasedX: Int,
        releasedY: Int,
        screenWidthPx: Int,
        screenHeightPx: Int,
        density: Float,
    ): OverlayPlacement {
        val width = collapsedWidthPx(density)
        val height = collapsedHeightPx(density)
        val centerX = releasedX + width / 2f
        val dockSide = if (centerX < screenWidthPx / 2f) OverlayDockSide.LEFT else OverlayDockSide.RIGHT
        val availableHeight = (screenHeightPx - height).coerceAtLeast(1)
        val fraction = (releasedY.toFloat() / availableHeight).coerceIn(MIN_VERTICAL_FRACTION, MAX_VERTICAL_FRACTION)
        return OverlayPlacement(dockSide, fraction)
    }

    fun windowPosition(
        placement: OverlayPlacement,
        mode: OverlayDisplayMode,
        screenWidthPx: Int,
        screenHeightPx: Int,
        density: Float,
    ): OverlayWindowPosition {
        val width = if (mode == OverlayDisplayMode.COLLAPSED) collapsedWidthPx(density) else expandedWidthPx(density)
        val height = if (mode == OverlayDisplayMode.COLLAPSED) collapsedHeightPx(density) else expandedHeightPx(density)
        val fraction = placement.verticalFraction.coerceIn(MIN_VERTICAL_FRACTION, MAX_VERTICAL_FRACTION)
        val y = ((screenHeightPx - height).coerceAtLeast(0) * fraction).toInt()
        val x = when (placement.dockSide) {
            // Collapsed capsule keeps exactly 24dp exposed; preview stays entirely visible.
            OverlayDockSide.LEFT -> if (mode == OverlayDisplayMode.COLLAPSED) visibleCollapsedWidthPx(density) - width else 0
            OverlayDockSide.RIGHT -> if (mode == OverlayDisplayMode.COLLAPSED) screenWidthPx - visibleCollapsedWidthPx(density) else (screenWidthPx - width).coerceAtLeast(0)
        }
        return OverlayWindowPosition(x, y)
    }

    fun collapsedWidthPx(density: Float): Int = (COLLAPSED_WIDTH_DP * density).toInt()
    fun collapsedHeightPx(density: Float): Int = (COLLAPSED_HEIGHT_DP * density).toInt()
    fun expandedWidthPx(density: Float): Int = (EXPANDED_WIDTH_DP * density).toInt()
    fun expandedHeightPx(density: Float): Int = (EXPANDED_HEIGHT_DP * density).toInt()

    private fun visibleCollapsedWidthPx(density: Float): Int = (COLLAPSED_VISIBLE_WIDTH_DP * density).toInt()

    companion object {
        const val COLLAPSED_WIDTH_DP = 44
        const val COLLAPSED_HEIGHT_DP = 88
        const val COLLAPSED_VISIBLE_WIDTH_DP = 24
        const val EXPANDED_WIDTH_DP = MofeiSideArcGeometry.WIDTH_DP.toInt()
        const val EXPANDED_HEIGHT_DP = MofeiSideArcGeometry.HEIGHT_DP.toInt()
        const val MIN_VERTICAL_FRACTION = 0.1f
        const val MAX_VERTICAL_FRACTION = 0.9f
    }
}
