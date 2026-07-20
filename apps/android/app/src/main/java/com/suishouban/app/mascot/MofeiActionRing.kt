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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suishouban.app.mascot.action.MofeiAction
import com.suishouban.app.mascot.action.MofeiActionAvailability
import com.suishouban.app.mascot.action.MofeiActionItem
import com.suishouban.app.mascot.action.MofeiSurface
import kotlin.math.cos
import kotlin.math.sin

private val FULL_RING_SIZE = 340.dp
private val COMPACT_RING_SIZE = 276.dp
private val ACTION_ORB_SIZE = 58.dp

/**
 * Shared visual action center for the in-app companion and the system overlay.
 *
 * Android permission launchers stay outside this composable: locked actions remain clickable so
 * their owning Activity or Service can explain and request the relevant special access.
 */
@Composable
fun MofeiActionRing(
    surface: MofeiSurface,
    items: List<MofeiActionItem>,
    expanded: Boolean,
    reduceMotion: Boolean,
    onAction: (MofeiAction) -> Unit,
    onDismiss: () -> Unit,
    mirrorCompact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ringSize = if (surface == MofeiSurface.IN_APP) FULL_RING_SIZE else COMPACT_RING_SIZE
    val transition = if (reduceMotion) {
        fadeIn() to fadeOut()
    } else {
        (fadeIn() + scaleIn(initialScale = 0.72f)) to
            (fadeOut() + scaleOut(targetScale = 0.72f))
    }

    Box(modifier = modifier.testTag("mofei-action-ring"), contentAlignment = Alignment.Center) {
        if (!expanded) {
            Image(
                painter = painterResource(MofeiActionAssets.seal),
                contentDescription = "收起墨斐能力环",
                modifier = Modifier
                    .size(58.dp)
                    .testTag("mofei-action-dismiss")
                    .semantics { role = Role.Button }
                    .clickable(onClick = onDismiss),
            )
            return@Box
        }

        AnimatedVisibility(visible = true, enter = transition.first, exit = transition.second) {
            Box(modifier = Modifier.size(ringSize)) {
                Image(
                    painter = painterResource(
                        if (surface == MofeiSurface.IN_APP) {
                            MofeiActionAssets.fullRing
                        } else {
                            MofeiActionAssets.compactRing
                        },
                    ),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = if (surface == MofeiSurface.OVERLAY && mirrorCompact) -1f else 1f
                        },
                )

                items.forEachIndexed { index, item ->
                    val position = actionPosition(surface, index, items.size, ringSize, mirrorCompact)
                    MofeiActionOrb(
                        item = item,
                        onClick = { onAction(item.action) },
                        modifier = Modifier.offset(position.first, position.second),
                    )
                }

                Image(
                    painter = painterResource(MofeiActionAssets.seal),
                    contentDescription = "收起墨斐能力环",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(58.dp)
                        .testTag("mofei-action-dismiss")
                        .semantics { role = Role.Button }
                        .clickable(onClick = onDismiss),
                )

                if (items.any { it.availability == MofeiActionAvailability.NEEDS_PERMISSION }) {
                    Text(
                        text = "需要通知读取权限",
                        color = Color(0xFFE3FAFF),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .background(Color(0xD90A2350), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
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

    Box(modifier = modifier.size(72.dp).testTag("mofei-action-item"), contentAlignment = Alignment.TopCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.TopEnd) {
                Image(
                    painter = painterResource(MofeiActionAssets.glyphs.getValue(item.action)),
                    contentDescription = null,
                    modifier = Modifier
                        .size(ACTION_ORB_SIZE)
                        .alpha(if (enabled) 1f else 0.48f)
                        .testTag("mofei-action-${actionSlug(item.action)}")
                        .semantics {
                            contentDescription = label + stateHint
                            role = Role.Button
                        }
                        .clickable(enabled = enabled, onClick = onClick),
                )
                if (item.badgeCount > 0) {
                    Text(
                        text = item.badgeCount.coerceAtMost(99).toString(),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .background(Color(0xFF7A42F4), CircleShape)
                            .widthIn(min = 18.dp)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                text = label,
                color = Color(0xFFE8FBFF),
                fontSize = 9.sp,
                lineHeight = 10.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .graphicsLayer { translationY = (-3).dp.toPx() }
                    .background(Color(0xC908214B), RoundedCornerShape(8.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/** Top-left offsets align action hit targets with the generated ring sockets. */
private fun actionPosition(
    surface: MofeiSurface,
    index: Int,
    count: Int,
    ringSize: Dp,
    mirrorCompact: Boolean,
): Pair<Dp, Dp> {
    val angles = if (surface == MofeiSurface.OVERLAY) {
        listOf(-90.0, -135.0, 180.0, 135.0, 90.0)
    } else {
        List(count.coerceAtLeast(1)) { -90.0 + 360.0 * it / count.coerceAtLeast(1) }
    }
    val angle = Math.toRadians(angles[index.coerceAtMost(angles.lastIndex)])
    val center = ringSize.value / 2f
    val radius = ringSize.value * if (surface == MofeiSurface.IN_APP) 0.405f else 0.39f
    val halfOrb = 36f
    val rawX = center + cos(angle).toFloat() * radius - halfOrb
    val x = if (surface == MofeiSurface.OVERLAY && mirrorCompact) {
        ringSize.value - rawX - halfOrb * 2f
    } else {
        rawX
    }
    return x.dp to
        (center + sin(angle).toFloat() * radius - halfOrb).dp
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
