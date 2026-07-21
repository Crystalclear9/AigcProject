package com.suishouban.app.mascot

/** A quick action offered inside the floating pet's speech bubble. */
enum class PetBubbleAction { OpenCurrentAction, Dismiss }

/** A command from the pet's long-press mini menu. */
enum class PetMenuCommand { HideForNow, OpenSettings }

/**
 * Pure, JVM-testable policy for the in-app floating pet. Pixel math lives here so drag snapping,
 * docking, and bubble affordances can be covered without a Compose or Android fixture. Density is
 * applied at the composable boundary; this class works entirely in already-resolved pixels.
 */
class FloatingMascotController {

    /** Nearest-edge docking from the pet's current center, matching the persisted overlay model. */
    fun snapDockSide(petCenterXPx: Float, screenWidthPx: Int): OverlayDockSide =
        if (petCenterXPx < screenWidthPx / 2f) OverlayDockSide.LEFT else OverlayDockSide.RIGHT

    /** Normalized vertical rest position, clamped away from the status bar and navigation bar. */
    fun verticalFraction(releasedYPx: Float, trackHeightPx: Int): Float {
        if (trackHeightPx <= 0) return DEFAULT_VERTICAL_FRACTION
        return (releasedYPx / trackHeightPx).coerceIn(MIN_VERTICAL_FRACTION, MAX_VERTICAL_FRACTION)
    }

    /** Resting left coordinate for the pet given its docked side. */
    fun restingXPx(dockSide: OverlayDockSide, trackWidthPx: Int, marginPx: Float): Float = when (dockSide) {
        OverlayDockSide.LEFT -> marginPx
        OverlayDockSide.RIGHT -> (trackWidthPx - marginPx).coerceAtLeast(marginPx)
    }

    /** Resting top coordinate from a normalized fraction over the usable track height. */
    fun restingYPx(fraction: Float, trackHeightPx: Int): Float =
        (trackHeightPx.coerceAtLeast(0) * fraction.coerceIn(MIN_VERTICAL_FRACTION, MAX_VERTICAL_FRACTION))

    /**
     * The bubble grows toward screen center so it never runs off the docked edge. Returns the
     * bubble's left coordinate relative to the same track the pet is positioned in.
     */
    fun bubbleXPx(
        dockSide: OverlayDockSide,
        petXPx: Float,
        petWidthPx: Float,
        bubbleWidthPx: Float,
        gapPx: Float,
    ): Float = when (dockSide) {
        OverlayDockSide.LEFT -> petXPx + petWidthPx + gapPx
        OverlayDockSide.RIGHT -> petXPx - bubbleWidthPx - gapPx
    }

    /** "查看事项" is only meaningful when the current state points at a concrete card. */
    fun showsOpenAction(state: MascotState): Boolean = !state.actionCardId.isNullOrBlank()

    companion object {
        const val MIN_VERTICAL_FRACTION = 0.08f
        const val MAX_VERTICAL_FRACTION = 0.82f
        const val DEFAULT_VERTICAL_FRACTION = 0.62f
    }
}
