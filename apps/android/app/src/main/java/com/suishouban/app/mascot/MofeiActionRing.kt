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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.suishouban.app.mascot.action.MofeiAction
import com.suishouban.app.mascot.action.MofeiActionAvailability
import com.suishouban.app.mascot.action.MofeiActionItem
import com.suishouban.app.mascot.action.MofeiSurface

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
    dockSide: OverlayDockSide = OverlayDockSide.RIGHT,
    modifier: Modifier = Modifier,
) {
    var revealedAction by remember { mutableStateOf<MofeiAction?>(null) }
    LaunchedEffect(expanded) {
        if (!expanded) revealedAction = null
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
                        .fillMaxSize()
                        .alpha(0.82f)
                        .graphicsLayer {
                            // The generated source opens from the right edge; mirror for left dock.
                            scaleX = if (dockSide == OverlayDockSide.LEFT) -1f else 1f
                        },
                )
                val centers = MofeiSideArcGeometry.actionCenters(dockSide, items.size)
                items.zip(centers).forEach { (item, center) ->
                    MofeiActionOrb(
                        item = item,
                        onClick = {
                            if (revealedAction == item.action) {
                                revealedAction = null
                                onAction(item.action)
                            } else {
                                // First tap explains the icon; a second tap confirms execution.
                                revealedAction = item.action
                            }
                        },
                        modifier = Modifier.offset(
                            (center.x - MofeiSideArcGeometry.ACTION_SIZE_DP / 2f).dp,
                            (center.y - MofeiSideArcGeometry.ACTION_SIZE_DP / 2f).dp,
                        ),
                    )
                }
                revealedAction?.let { selected ->
                    val index = items.indexOfFirst { it.action == selected }
                    if (index >= 0 && index < centers.size) {
                        MofeiActionHint(
                            text = actionLabel(selected),
                            center = centers[index],
                            dockSide = dockSide,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MofeiActionHint(
    text: String,
    center: MofeiArcPoint,
    dockSide: OverlayDockSide,
) {
    val width = 78f
    val height = 24f
    val x = if (dockSide == OverlayDockSide.LEFT) {
        (center.x + 24f).coerceAtMost(MofeiSideArcGeometry.WIDTH_DP - width)
    } else {
        (center.x - width - 24f).coerceAtLeast(0f)
    }
    val y = (center.y - height / 2f).coerceIn(0f, MofeiSideArcGeometry.HEIGHT_DP - height)

    Text(
        text = text,
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier
            .offset(x.dp, y.dp)
            .widthIn(min = width.dp, max = width.dp)
            .background(Color(0xE61A315D), RoundedCornerShape(12.dp))
            .padding(horizontal = 7.dp, vertical = 5.dp)
            .testTag("mofei-action-hint"),
    )
}

@Composable
private fun MofeiActionOrb(
    item: MofeiActionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = item.availability != MofeiActionAvailability.UNSUPPORTED &&
        item.availability != MofeiActionAvailability.BUSY
    val label = actionLabel(item.action)
    val stateHint = when (item.availability) {
        MofeiActionAvailability.READY -> ""
        MofeiActionAvailability.NEEDS_PERMISSION -> "，需要权限"
        MofeiActionAvailability.UNSUPPORTED -> "，当前设备不支持"
        MofeiActionAvailability.BUSY -> "，处理中"
    }

    Box(
        modifier = modifier
            .size(MofeiSideArcGeometry.ACTION_SIZE_DP.dp)
            .testTag("mofei-action-item"),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(MofeiActionAssets.glyphs.getValue(item.action)),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (enabled) 1f else 0.46f)
                .testTag("mofei-action-${actionSlug(item.action)}")
                .semantics {
                    contentDescription = label + stateHint
                    role = Role.Button
                }
                .clickable(enabled = enabled, onClick = onClick),
        )
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

private fun actionLabel(action: MofeiAction): String = when (action) {
    MofeiAction.CAPTURE_CURRENT_SCREEN -> "识别当前屏"
    MofeiAction.ANALYZE_LATEST_SCREENSHOT -> "最近截图"
    MofeiAction.PICK_IMAGE -> "相册导入"
    MofeiAction.TAKE_PHOTO -> "拍照识别"
    MofeiAction.REVIEW_NOTIFICATION_DRAFTS -> "通知草稿"
    MofeiAction.OPEN_CURRENT_CARD -> "当前事项"
    MofeiAction.OPEN_SETTINGS -> "能力设置"
}

private fun actionSlug(action: MofeiAction): String = action.name.lowercase().replace('_', '-')
