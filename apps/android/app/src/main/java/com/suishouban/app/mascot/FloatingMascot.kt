package com.suishouban.app.mascot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.suishouban.app.mascot.action.MofeiAction
import com.suishouban.app.mascot.action.MofeiActionItem
import com.suishouban.app.mascot.action.MofeiSurface
import com.suishouban.app.notification.NotificationCandidateUiModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val PET_SIZE = 76.dp
private val EDGE_MARGIN = 8.dp
private val BUBBLE_GAP = 10.dp

/**
 * A resident, draggable, tappable in-app pet. Unlike the system overlay it needs no permission and
 * stays visible across every screen, riding above page content and below dialogs. It reuses the
 * shared [MascotState] pipeline for mood, color, and copy, and persists its dock position through
 * the same [OverlayDockSide] / vertical-fraction settings the overlay already stores.
 *
 * @param onOpenCurrentAction invoked with the state's action card id when the user taps 查看事项.
 * @param onOpenSettings invoked from the long-press menu.
 * @param completionSignal increments to trigger a one-shot celebration burst (feed it a counter
 *   derived from [com.suishouban.app.AppViewModel.mascotInteractions]).
 * @param actionItems actions exposed around the live sprite; an empty list preserves the legacy
 *   speech-bubble tap behavior while the owning screen is still loading capability state.
 */
@Composable
fun FloatingMascot(
    state: MascotState,
    dockSide: OverlayDockSide,
    verticalFraction: Float,
    reduceMotion: Boolean,
    completionSignal: Int,
    onOpenCurrentAction: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onDismissForNow: () -> Unit,
    onPlacementChange: (OverlayDockSide, Float) -> Unit,
    actionItems: List<MofeiActionItem> = emptyList(),
    onAction: (MofeiAction) -> Unit = {},
    onActionCenterOpen: () -> Unit = {},
    notificationCandidates: List<NotificationCandidateUiModel> = emptyList(),
    onOpenNotificationCandidate: (String) -> Unit = {},
    onRejectNotificationCandidate: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val controller = remember { FloatingMascotController() }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var trackSize by remember { mutableStateOf(IntOffset.Zero) }
    var bubbleOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var actionRingOpen by remember { mutableStateOf(false) }

    val petPx = with(density) { PET_SIZE.toPx() }
    val marginPx = with(density) { EDGE_MARGIN.toPx() }

    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }

    // Re-dock whenever the container is measured or the persisted placement changes.
    LaunchedEffect(trackSize, dockSide, verticalFraction) {
        val width = trackSize.x
        val height = trackSize.y
        if (width <= 0 || height <= 0) return@LaunchedEffect
        val trackH = (height - petPx).roundToInt().coerceAtLeast(0)
        val targetX = controller.restingXPx(dockSide, (width - petPx).roundToInt(), marginPx)
        val targetY = controller.restingYPx(verticalFraction, trackH)
        offsetX.snapTo(targetX)
        offsetY.snapTo(targetY)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { trackSize = IntOffset(it.width, it.height) },
    ) {
        if (trackSize.x <= 0) return@Box

        val profile = MascotVisuals.profileFor(state, reduceMotion)

        if (actionRingOpen && actionItems.isNotEmpty()) {
            val ringPx = with(density) { 340.dp.toPx() }
            val ringX = (offsetX.value + petPx / 2f - ringPx / 2f)
                .coerceIn(0f, (trackSize.x - ringPx).coerceAtLeast(0f))
            val ringY = (offsetY.value + petPx / 2f - ringPx / 2f)
                .coerceIn(0f, (trackSize.y - ringPx).coerceAtLeast(0f))
            MofeiActionRing(
                surface = MofeiSurface.IN_APP,
                items = actionItems,
                expanded = true,
                reduceMotion = reduceMotion,
                onAction = {
                    actionRingOpen = false
                    onAction(it)
                },
                onDismiss = { actionRingOpen = false },
                modifier = Modifier.graphicsLayer {
                    translationX = ringX
                    translationY = ringY
                },
            )
        }

        // The pet cluster (sprite + halo + celebration) lives at the animated offset. The bubble is
        // sibling-positioned so it can grow toward screen center without being clipped by the pet box.
        MofeiPet(
            state = state,
            profile = profile,
            reduceMotion = reduceMotion,
            completionSignal = completionSignal,
            modifier = Modifier
                .graphicsLayer {
                    translationX = offsetX.value
                    translationY = offsetY.value
                }
                .size(PET_SIZE)
                .semantics { contentDescription = profile.contentDescription }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            menuOpen = false
                            if (actionItems.isEmpty()) {
                                bubbleOpen = !bubbleOpen
                            } else {
                                bubbleOpen = false
                                if (!actionRingOpen) onActionCenterOpen()
                                actionRingOpen = !actionRingOpen
                            }
                        },
                        onLongPress = {
                            bubbleOpen = false
                            actionRingOpen = false
                            menuOpen = !menuOpen
                        },
                    )
                }
                .pointerInput(trackSize) {
                    detectDragGestures(
                        onDragEnd = {
                            val center = offsetX.value + petPx / 2f
                            val side = controller.snapDockSide(center, trackSize.x)
                            val trackH = (trackSize.y - petPx).coerceAtLeast(1f)
                            val fraction = controller.verticalFraction(offsetY.value, trackH.roundToInt())
                            val targetX = controller.restingXPx(side, (trackSize.x - petPx).roundToInt(), marginPx)
                            scope.launch {
                                offsetX.animateTo(targetX, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                            onPlacementChange(side, fraction)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                val maxX = (trackSize.x - petPx).coerceAtLeast(0f)
                                val maxY = (trackSize.y - petPx).coerceAtLeast(0f)
                                offsetX.snapTo((offsetX.value + dragAmount.x).coerceIn(0f, maxX))
                                offsetY.snapTo((offsetY.value + dragAmount.y).coerceIn(0f, maxY))
                            }
                            bubbleOpen = false
                            actionRingOpen = false
                            menuOpen = false
                        },
                    )
                },
        )

        if (!actionRingOpen && notificationCandidates.isNotEmpty()) {
            val firefliesWidth = with(density) { 270.dp.toPx() }
            val firefliesHeight = with(density) { 150.dp.toPx() }
            val fireflyX = if (dockSide == OverlayDockSide.LEFT) {
                offsetX.value + petPx * 0.72f
            } else {
                offsetX.value - firefliesWidth + petPx * 0.28f
            }.coerceIn(0f, (trackSize.x - firefliesWidth).coerceAtLeast(0f))
            val fireflyY = (offsetY.value - firefliesHeight * 0.72f)
                .coerceIn(0f, (trackSize.y - firefliesHeight).coerceAtLeast(0f))
            MofeiNotificationFireflies(
                candidates = notificationCandidates,
                onOpen = onOpenNotificationCandidate,
                onReject = onRejectNotificationCandidate,
                modifier = Modifier.graphicsLayer {
                    translationX = fireflyX
                    translationY = fireflyY
                },
            )
        }

        val bubbleWidthPx = with(density) { 200.dp.toPx() }
        val bubbleX = controller.bubbleXPx(
            dockSide = dockSide,
            petXPx = offsetX.value,
            petWidthPx = petPx,
            bubbleWidthPx = bubbleWidthPx,
            gapPx = with(density) { BUBBLE_GAP.toPx() },
        ).coerceIn(marginPx, (trackSize.x - bubbleWidthPx - marginPx).coerceAtLeast(marginPx))

        MascotBubble(
            visible = bubbleOpen,
            message = profile.message,
            showOpenAction = controller.showsOpenAction(state),
            primaryArgb = profile.primaryArgb,
            modifier = Modifier.graphicsLayer {
                translationX = bubbleX
                translationY = (offsetY.value - with(density) { 6.dp.toPx() }).coerceAtLeast(0f)
            },
            onOpen = {
                bubbleOpen = false
                onOpenCurrentAction(state.actionCardId)
            },
            onDismiss = { bubbleOpen = false },
        )

        MascotMiniMenu(
            visible = menuOpen,
            modifier = Modifier.graphicsLayer {
                translationX = bubbleX
                translationY = (offsetY.value + petPx).coerceAtMost((trackSize.y - with(density) { 96.dp.toPx() }).coerceAtLeast(0f))
            },
            onHide = {
                menuOpen = false
                onDismissForNow()
            },
            onSettings = {
                menuOpen = false
                onOpenSettings()
            },
        )
    }
}

/**
 * The pet cluster: a mood-tinted halo, gentle idle breathing/peeking, the eight-frame sprite, and a
 * one-shot celebration burst. Idle micro-motion and the halo pulse both honor reduce-motion.
 */
@Composable
private fun MofeiPet(
    state: MascotState,
    profile: MofeiVisualProfile,
    reduceMotion: Boolean,
    completionSignal: Int,
    modifier: Modifier = Modifier,
) {
    val accent = Color(profile.primaryArgb)

    // Idle "peek": a slow vertical bob plus a hair of scale, layered over the sprite loop so the pet
    // feels alive even when its mood art is quiet. Reduce-motion pins it to rest.
    val idle = rememberInfiniteTransition(label = "mofei-idle")
    val bob by if (reduceMotion) {
        remember { mutableFloatStateOf(0f) }
    } else {
        idle.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "mofei-bob",
        )
    }
    val haloPulse by if (reduceMotion) {
        remember { mutableFloatStateOf(0.5f) }
    } else {
        idle.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.75f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = motionPulseMillis(profile.motion), easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "mofei-halo",
        )
    }

    // Celebration burst: a short-lived progress ramp keyed to the completion signal.
    var burst by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(completionSignal) {
        if (completionSignal <= 0 || reduceMotion) return@LaunchedEffect
        val steps = 24
        for (i in 0..steps) {
            burst = i / steps.toFloat()
            delay(24L)
        }
        burst = 0f
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Soft mood halo + drop shadow so the pet reads as a physical object over any screen.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = haloPulse * 0.5f), Color.Transparent),
                    center = center,
                    radius = r * 1.15f,
                ),
                radius = r * 1.15f,
                center = center,
            )
            if (burst > 0f) drawCelebrationBurst(accent, burst)
        }
        MofeiPetSprite(
            state = state,
            reduceMotion = reduceMotion,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val shift = if (reduceMotion) 0f else (bob - 0.5f) * 6.dp.toPx()
                    translationY = shift
                    val s = if (reduceMotion) 1f else 1f + (bob - 0.5f) * 0.03f
                    scaleX = s
                    scaleY = s
                },
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCelebrationBurst(accent: Color, progress: Float) {
    val count = 8
    val ring = size.minDimension * (0.2f + 0.5f * progress)
    repeat(count) { i ->
        val angle = (i / count.toFloat()) * 2f * PI.toFloat()
        val cx = center.x + cos(angle) * ring
        val cy = center.y + sin(angle) * ring
        drawCircle(
            color = accent.copy(alpha = (1f - progress) * 0.9f),
            radius = size.minDimension * 0.05f * (1f - progress * 0.5f),
            center = Offset(cx, cy),
        )
    }
}

private fun motionPulseMillis(motion: MofeiMotion): Int = when (motion) {
    MofeiMotion.ALERT_PULSE -> 700
    MofeiMotion.WARNING_PULSE -> 1000
    MofeiMotion.CELEBRATE -> 900
    MofeiMotion.SCAN -> 1400
    else -> 2400
}

/** A glass speech bubble with the mood message and, when actionable, a 查看事项 shortcut. */
@Composable
private fun MascotBubble(
    visible: Boolean,
    message: String,
    showOpenAction: Boolean,
    primaryArgb: Long,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.85f),
        modifier = modifier.testTag("mofei-bubble"),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 200.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.96f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(primaryArgb)),
                )
                Text("墨斐", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showOpenAction) {
                    TextButton(onClick = onOpen) { Text("查看事项") }
                }
                TextButton(onClick = onDismiss) { Text("收起") }
            }
        }
    }
}

/** Long-press mini menu: hide for now, or jump to settings. */
@Composable
private fun MascotMiniMenu(
    visible: Boolean,
    modifier: Modifier = Modifier,
    onHide: () -> Unit,
    onSettings: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.85f),
        modifier = modifier.testTag("mofei-mini-menu"),
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.96f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            TextButton(onClick = onHide) { Text("本次隐藏") }
            TextButton(onClick = onSettings) { Text("打开设置") }
        }
    }
}
