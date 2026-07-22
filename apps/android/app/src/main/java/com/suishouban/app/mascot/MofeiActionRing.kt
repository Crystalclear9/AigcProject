package com.suishouban.app.mascot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.suishouban.app.mascot.action.MofeiAction
import com.suishouban.app.mascot.action.MofeiActionAvailability
import com.suishouban.app.mascot.action.MofeiActionItem
import com.suishouban.app.mascot.action.MofeiSurface
import kotlinx.coroutines.delay

internal const val MOFEI_ACTION_RING_IDLE_TIMEOUT_MS = 5_000L

/**
 * Compact action center that opens only toward screen content from the docked Mofei.
 * The live mascot is rendered by the owning surface at the arc's edge-center anchor.
 */
@Composable
fun MofeiActionRing(
    surface: MofeiSurface,
    items: List<MofeiActionItem>,
    expanded: Boolean,
    reduceMotion: Boolean,
    onAction: (MofeiAction) -> Unit,
    onDismiss: () -> Unit,
    revealedActionOverride: MofeiAction? = null,
    onActionPreview: (MofeiAction) -> Unit = {},
    dockSide: OverlayDockSide = OverlayDockSide.RIGHT,
    modifier: Modifier = Modifier,
) {
    var revealedAction by remember { mutableStateOf<MofeiAction?>(null) }
    val effectiveRevealedAction = revealedActionOverride ?: revealedAction
    var interactionVersion by remember { mutableStateOf(0) }
    LaunchedEffect(expanded) {
        if (!expanded) revealedAction = null
    }
    LaunchedEffect(expanded, interactionVersion) {
        if (expanded) {
            delay(MOFEI_ACTION_RING_IDLE_TIMEOUT_MS)
            onDismiss()
        }
    }
    val transition = if (reduceMotion) {
        fadeIn() to fadeOut()
    } else {
        (fadeIn() + scaleIn(initialScale = 0.84f)) to
            (fadeOut() + scaleOut(targetScale = 0.84f))
    }

    Box(modifier = modifier.testTag("mofei-action-ring"), contentAlignment = Alignment.Center) {
        if (!expanded) {
            Image(
                painter = painterResource(MofeiActionAssets.seal),
                contentDescription = "收起墨斐能力环",
                modifier = Modifier
                    .size(54.dp)
                    .testTag("mofei-action-dismiss")
                    .semantics { role = Role.Button }
                    .clickable(onClick = onDismiss),
            )
            return@Box
        }

        AnimatedVisibility(visible = true, enter = transition.first, exit = transition.second) {
            Box(
                modifier = Modifier
                    .size(MofeiSideArcGeometry.WIDTH_DP.dp, MofeiSideArcGeometry.HEIGHT_DP.dp)
                    .testTag("mofei-side-arc-${dockSide.name.lowercase()}"),
            ) {
                Image(
                    painter = painterResource(MofeiActionAssets.sideArc),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .align(
                            if (dockSide == OverlayDockSide.LEFT) {
                                Alignment.CenterStart
                            } else {
                                Alignment.CenterEnd
                            },
                        )
                        .size(
                            MofeiSideArcGeometry.TRACK_WIDTH_DP.dp,
                            MofeiSideArcGeometry.HEIGHT_DP.dp,
                        )
                        .alpha(0.82f)
                        .graphicsLayer {
                            // The generated source opens from the right edge; mirror for left dock.
                            scaleX = if (dockSide == OverlayDockSide.LEFT) -1f else 1f
                        },
                )
                val centers = MofeiSideArcGeometry.actionCenters(dockSide, items.size)
                // Draw the selected pill last and on a higher layer so nearby orbs never cover it.
                items.zip(centers)
                    .sortedBy { (item, _) -> item.action == effectiveRevealedAction }
                    .forEach { (item, center) ->
                        val selected = effectiveRevealedAction == item.action
                        val orbWidth = if (selected) SELECTED_ACTION_WIDTH_DP else MofeiSideArcGeometry.ACTION_SIZE_DP
                        val orbX = if (selected && dockSide == OverlayDockSide.RIGHT) {
                            center.x - orbWidth + MofeiSideArcGeometry.ACTION_SIZE_DP / 2f
                        } else {
                            center.x - MofeiSideArcGeometry.ACTION_SIZE_DP / 2f
                        }
                        MofeiActionOrb(
                            item = item,
                            surface = surface,
                            selected = selected,
                            dockSide = dockSide,
                            onClick = {
                                interactionVersion += 1
                                if (effectiveRevealedAction == item.action) {
                                    revealedAction = null
                                    onAction(item.action)
                                } else {
                                    // First tap explains the icon; a second tap confirms execution.
                                    revealedAction = item.action
                                    onActionPreview(item.action)
                                }
                            },
                            modifier = Modifier.offset(
                                orbX.dp,
                                (center.y - MofeiSideArcGeometry.ACTION_SIZE_DP / 2f).dp,
                            ).zIndex(if (selected) 10f else 0f),
                        )
                    }
            }
        }
    }
}

@Composable
private fun MofeiActionOrb(
    item: MofeiActionItem,
    surface: MofeiSurface,
    selected: Boolean,
    dockSide: OverlayDockSide,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = item.availability != MofeiActionAvailability.UNSUPPORTED &&
        item.availability != MofeiActionAvailability.BUSY
    val label = actionLabel(surface, item.action)
    val stateHint = when (item.availability) {
        MofeiActionAvailability.READY -> ""
        MofeiActionAvailability.NEEDS_PERMISSION -> "，需要权限"
        MofeiActionAvailability.UNSUPPORTED -> "，当前设备不支持"
        MofeiActionAvailability.BUSY -> "，处理中"
    }

    Box(
        modifier = modifier
            .size(
                width = (if (selected) SELECTED_ACTION_WIDTH_DP else MofeiSideArcGeometry.ACTION_SIZE_DP).dp,
                height = MofeiSideArcGeometry.ACTION_SIZE_DP.dp,
            )
            .then(
                if (selected) {
                    Modifier.background(Color(0xEB142B50), RoundedCornerShape(19.dp))
                } else {
                    Modifier
                },
            )
            .testTag("mofei-action-item")
            .semantics {
                contentDescription = label + stateHint
                role = Role.Button
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(MofeiActionAssets.glyphs.getValue(item.action)),
            contentDescription = null,
            modifier = Modifier
                .align(
                    if (!selected) Alignment.Center else if (dockSide == OverlayDockSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd,
                )
                .size(MofeiSideArcGeometry.ACTION_SIZE_DP.dp)
                .alpha(if (enabled) 1f else 0.46f)
                .testTag("mofei-action-${actionSlug(item.action)}")
                .clickable(enabled = enabled, onClick = onClick),
        )
        if (selected) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .align(if (dockSide == OverlayDockSide.LEFT) Alignment.CenterEnd else Alignment.CenterStart)
                    .width((SELECTED_ACTION_WIDTH_DP - MofeiSideArcGeometry.ACTION_SIZE_DP).dp)
                    .padding(horizontal = 3.dp)
                    .testTag("mofei-action-hint"),
            )
        }
        if (item.availability == MofeiActionAvailability.NEEDS_PERMISSION) {
            Image(
                painter = painterResource(MofeiActionAssets.seal),
                contentDescription = null,
                modifier = Modifier.align(Alignment.BottomEnd).size(16.dp),
            )
        }
        if (item.badgeCount > 0) {
            Text(
                text = item.badgeCount.coerceAtMost(99).toString(),
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(Color(0xFF6A3CE0), CircleShape)
                    .widthIn(min = 16.dp),
            )
        }
    }
}

private fun actionLabel(surface: MofeiSurface, action: MofeiAction): String = when (action) {
    MofeiAction.CAPTURE_CURRENT_SCREEN -> if (surface == MofeiSurface.OVERLAY) "截屏" else "识别当前屏"
    MofeiAction.ANALYZE_LATEST_SCREENSHOT -> "最近截图"
    MofeiAction.PICK_IMAGE -> "相册导入"
    MofeiAction.TAKE_PHOTO -> "拍照识别"
    MofeiAction.REVIEW_NOTIFICATION_DRAFTS -> "通知草稿"
    MofeiAction.OPEN_CURRENT_CARD -> "当前事项"
    MofeiAction.OPEN_SETTINGS -> "能力设置"
}

private fun actionSlug(action: MofeiAction): String = action.name.lowercase().replace('_', '-')

private const val SELECTED_ACTION_WIDTH_DP = 104f
