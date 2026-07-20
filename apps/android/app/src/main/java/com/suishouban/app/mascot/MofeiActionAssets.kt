package com.suishouban.app.mascot

import androidx.annotation.DrawableRes
import com.suishouban.app.R
import com.suishouban.app.mascot.action.MofeiAction

/** Single source of truth for generated Mofei action-center artwork. */
object MofeiActionAssets {
    @DrawableRes
    val fullRing: Int = R.drawable.mofei_action_ring_full

    @DrawableRes
    val compactRing: Int = R.drawable.mofei_action_ring_compact

    @DrawableRes
    val seal: Int = R.drawable.mofei_action_seal

    @DrawableRes
    val sideArc: Int = R.drawable.mofei_action_side_arc

    /** Keep semantic action-to-art mapping out of composables and overlay services. */
    val glyphs: Map<MofeiAction, Int> = mapOf(
        MofeiAction.CAPTURE_CURRENT_SCREEN to R.drawable.mofei_action_capture_current_screen,
        MofeiAction.ANALYZE_LATEST_SCREENSHOT to R.drawable.mofei_action_latest_screenshot,
        MofeiAction.PICK_IMAGE to R.drawable.mofei_action_pick_image,
        MofeiAction.TAKE_PHOTO to R.drawable.mofei_action_take_photo,
        MofeiAction.REVIEW_NOTIFICATION_DRAFTS to R.drawable.mofei_action_notification_drafts,
        MofeiAction.OPEN_CURRENT_CARD to R.drawable.mofei_action_open_current_card,
        MofeiAction.OPEN_SETTINGS to R.drawable.mofei_action_open_settings,
    )
}
