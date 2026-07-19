package com.suishouban.app.mascot

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import kotlin.math.min

/** Stable ARGB tokens used by both the Canvas renderer and future asset catalog. */
object MofeiPalette {
    const val GLASS = 0xC9EAF5FFL
    const val VISOR = 0xFF0B2B68L
    const val ICE_BLUE = 0xFF5AA9FFL
    const val ELECTRIC_CYAN = 0xFF27D6E8L
    const val VIOLET_CONFIRM = 0xFF9A79FFL
    const val AMBER_REMINDER = 0xFFFFC247L
    const val ORANGE_DUE_SOON = 0xFFFF9238L
    const val CORAL_ALERT = 0xFFFF5E66L
    const val MINT_SUCCESS = 0xFF43E0BDL
    const val REST_BLUE = 0xFF8EA6C9L
    const val MUTED = 0xFF8391A8L
}

/** Motion stays semantic so the same state can later select a WebP action without changing UI callers. */
enum class MofeiMotion {
    STILL,
    BREATHE,
    SCAN,
    PEEK,
    NUDGE,
    WARNING_PULSE,
    ALERT_PULSE,
    CELEBRATE,
    SETTLE,
    DIM,
}

data class MofeiVisualProfile(
    val primaryArgb: Long,
    val contentDescription: String,
    val message: String,
    val motion: MofeiMotion,
)

/**
 * Converts the shared mascot state into visual semantics without depending on a particular screen
 * or overlay. Reduced motion intentionally keeps the urgency color and message while stopping loops.
 */
object MascotVisuals {
    fun profileFor(state: MascotState, reduceMotion: Boolean): MofeiVisualProfile {
        val definition = definitionFor(state.mood)
        val message = state.userMessage.ifBlank { definition.defaultMessage }
        return MofeiVisualProfile(
            primaryArgb = definition.primaryArgb,
            contentDescription = "墨斐，${definition.accessibilityLabel}。$message",
            message = message,
            motion = if (reduceMotion) MofeiMotion.STILL else definition.motion,
        )
    }

    private fun definitionFor(mood: MascotMood): MofeiVisualDefinition = when (mood) {
        MascotMood.IDLE -> MofeiVisualDefinition(MofeiPalette.ICE_BLUE, "待命", "墨斐正在待命", MofeiMotion.BREATHE)
        MascotMood.FOCUS -> MofeiVisualDefinition(MofeiPalette.ELECTRIC_CYAN, "正在识别", "正在识别行动事项", MofeiMotion.SCAN)
        MascotMood.CONFIRM -> MofeiVisualDefinition(MofeiPalette.VIOLET_CONFIRM, "等待确认", "有事项等待确认", MofeiMotion.PEEK)
        MascotMood.REMINDER -> MofeiVisualDefinition(MofeiPalette.AMBER_REMINDER, "轻提醒", "有一条提醒需要查看", MofeiMotion.NUDGE)
        MascotMood.DUE_SOON -> MofeiVisualDefinition(MofeiPalette.ORANGE_DUE_SOON, "即将到期", "有事项即将到期", MofeiMotion.WARNING_PULSE)
        MascotMood.URGENT -> MofeiVisualDefinition(MofeiPalette.CORAL_ALERT, "紧急提醒", "有紧急事项需要处理", MofeiMotion.ALERT_PULSE)
        MascotMood.COMPLETE -> MofeiVisualDefinition(MofeiPalette.MINT_SUCCESS, "任务完成", "任务已完成", MofeiMotion.CELEBRATE)
        MascotMood.REST -> MofeiVisualDefinition(MofeiPalette.REST_BLUE, "安静休息", "墨斐正在安静待命", MofeiMotion.SETTLE)
        MascotMood.UNAVAILABLE -> MofeiVisualDefinition(MofeiPalette.MUTED, "服务暂不可用", "识别服务暂不可用", MofeiMotion.DIM)
    }
}

private data class MofeiVisualDefinition(
    val primaryArgb: Long,
    val accessibilityLabel: String,
    val defaultMessage: String,
    val motion: MofeiMotion,
)

/**
 * In-app companion with the v6 silhouette: a balanced glass oval, oversized visor, capture brackets,
 * and an orbit ring. The overlay service can reuse [MofeiVisual] without its explanatory message.
 */
@Composable
fun MascotCompanion(
    state: MascotState,
    modifier: Modifier = Modifier,
    mascotSize: Dp = 112.dp,
    reduceMotion: Boolean = false,
    showMessage: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val profile = MascotVisuals.profileFor(state, reduceMotion)
    val interactionModifier = if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick)
    Column(
        modifier = modifier
            .widthIn(max = 188.dp)
            .semantics {
                contentDescription = profile.contentDescription
                if (onClick != null) role = Role.Button
            }
            .then(interactionModifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MofeiVisual(
            state = state,
            modifier = Modifier
                .size(mascotSize)
                .testTag("mofei-visual"),
            reduceMotion = reduceMotion,
        )
        if (showMessage) {
            Text(
                text = profile.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Draw-only form for compact overlay or screen-specific placement. */
@Composable
fun MofeiVisual(
    state: MascotState,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
) {
    val profile = MascotVisuals.profileFor(state, reduceMotion)
    val transition = rememberInfiniteTransition(label = "mofei-motion")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = motionDuration(profile.motion), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "mofei-motion-progress",
    )
    val motion = motionTransform(profile.motion, if (reduceMotion) 0f else progress)
    Canvas(
        modifier = modifier.graphicsLayer {
            translationY = motion.verticalShiftDp
            scaleX = motion.scale
            scaleY = motion.scale
            alpha = motion.alpha
        },
    ) {
        drawMofei(
            primary = Color(profile.primaryArgb),
            mood = state.mood,
            orbitProgress = motion.orbitProgress,
            scanProgress = motion.scanProgress,
        )
    }
}

private data class MofeiMotionTransform(
    val verticalShiftDp: Float = 0f,
    val scale: Float = 1f,
    val alpha: Float = 1f,
    val orbitProgress: Float = 0f,
    val scanProgress: Float = 0f,
)

private fun motionDuration(motion: MofeiMotion): Int = when (motion) {
    MofeiMotion.ALERT_PULSE -> 680
    MofeiMotion.WARNING_PULSE -> 1_000
    MofeiMotion.CELEBRATE -> 1_160
    MofeiMotion.SCAN -> 1_500
    else -> 2_600
}

private fun motionTransform(motion: MofeiMotion, progress: Float): MofeiMotionTransform = when (motion) {
    MofeiMotion.BREATHE -> MofeiMotionTransform(
        verticalShiftDp = -1.5f * kotlin.math.sin(progress * Math.PI).toFloat(),
        scale = 1f + 0.018f * kotlin.math.sin(progress * Math.PI).toFloat(),
        orbitProgress = progress,
    )
    MofeiMotion.SCAN -> MofeiMotionTransform(orbitProgress = progress, scanProgress = progress)
    MofeiMotion.PEEK, MofeiMotion.NUDGE -> MofeiMotionTransform(
        verticalShiftDp = -2.2f * kotlin.math.sin(progress * Math.PI).toFloat(),
        scale = 1f + 0.024f * kotlin.math.sin(progress * Math.PI).toFloat(),
        orbitProgress = progress * 0.45f,
    )
    MofeiMotion.WARNING_PULSE -> MofeiMotionTransform(
        scale = 1f + 0.032f * kotlin.math.sin(progress * Math.PI).toFloat(),
        orbitProgress = progress,
    )
    MofeiMotion.ALERT_PULSE -> MofeiMotionTransform(
        scale = 1f + 0.055f * kotlin.math.sin(progress * Math.PI).toFloat(),
        alpha = 0.86f + 0.14f * kotlin.math.sin(progress * Math.PI).toFloat(),
        orbitProgress = progress,
    )
    MofeiMotion.CELEBRATE -> MofeiMotionTransform(
        verticalShiftDp = -3f * kotlin.math.sin(progress * Math.PI).toFloat(),
        scale = 1f + 0.05f * kotlin.math.sin(progress * Math.PI).toFloat(),
        orbitProgress = progress * 1.6f,
    )
    MofeiMotion.SETTLE -> MofeiMotionTransform(alpha = 0.78f, orbitProgress = progress * 0.25f)
    MofeiMotion.DIM -> MofeiMotionTransform(alpha = 0.62f)
    MofeiMotion.STILL -> MofeiMotionTransform()
}

private fun DrawScope.drawMofei(
    primary: Color,
    mood: MascotMood,
    orbitProgress: Float,
    scanProgress: Float,
) {
    val unit = min(size.width, size.height)
    val body = Rect(
        left = size.width * 0.15f,
        top = size.height * 0.25f,
        right = size.width * 0.85f,
        bottom = size.height * 0.77f,
    )
    val visor = Rect(
        left = size.width * 0.27f,
        top = size.height * 0.35f,
        right = size.width * 0.73f,
        bottom = size.height * 0.64f,
    )
    val accent = primary.copy(alpha = 0.92f)

    // A diffuse internal glow retains a thin-glass feel instead of a plastic shell.
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.34f), Color.Transparent),
            center = body.center,
            radius = body.width * 0.72f,
        ),
        topLeft = Offset(body.left - unit * 0.05f, body.top - unit * 0.05f),
        size = Size(body.width + unit * 0.1f, body.height + unit * 0.1f),
    )
    drawOval(
        brush = Brush.linearGradient(
            colors = listOf(Color(MofeiPalette.GLASS), primary.copy(alpha = 0.22f), Color(MofeiPalette.GLASS).copy(alpha = 0.62f)),
            start = Offset(body.left, body.top),
            end = Offset(body.right, body.bottom),
        ),
        topLeft = body.topLeft,
        size = body.size,
    )
    drawOval(
        color = Color.White.copy(alpha = 0.62f),
        topLeft = Offset(body.left + unit * 0.08f, body.top + unit * 0.055f),
        size = Size(body.width * 0.31f, body.height * 0.12f),
    )
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(MofeiPalette.VISOR), Color(0xFF123E8AL), Color(MofeiPalette.VISOR))),
        topLeft = visor.topLeft,
        size = visor.size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 0.13f),
    )
    drawRoundRect(
        color = accent.copy(alpha = 0.66f),
        topLeft = visor.topLeft,
        size = visor.size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 0.13f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = unit * 0.012f),
    )
    drawEyes(visor, primary, mood, unit)
    drawCaptureBrackets(primary, unit)
    drawOrbit(primary, unit, orbitProgress)
    if (scanProgress > 0f) {
        val scanY = visor.top + visor.height * scanProgress
        drawLine(
            color = primary.copy(alpha = 0.7f),
            start = Offset(visor.left + unit * 0.04f, scanY),
            end = Offset(visor.right - unit * 0.04f, scanY),
            strokeWidth = unit * 0.012f,
            cap = StrokeCap.Round,
        )
    }
    if (mood == MascotMood.COMPLETE) drawCelebration(primary, unit, orbitProgress)
}

private fun DrawScope.drawEyes(visor: Rect, primary: Color, mood: MascotMood, unit: Float) {
    val eyeColor = Color.White.copy(alpha = 0.96f)
    val eyeWidth = unit * 0.045f
    val eyeHeight = when (mood) {
        MascotMood.REST, MascotMood.UNAVAILABLE -> unit * 0.024f
        else -> unit * 0.115f
    }
    val y = visor.center.y - eyeHeight / 2f
    val leftX = visor.center.x - unit * 0.10f - eyeWidth / 2f
    val rightX = visor.center.x + unit * 0.10f - eyeWidth / 2f
    val eyeRadius = androidx.compose.ui.geometry.CornerRadius(eyeWidth, eyeWidth)
    listOf(leftX, rightX).forEach { x ->
        drawRoundRect(primary.copy(alpha = 0.24f), Offset(x - unit * 0.022f, y - unit * 0.025f), Size(eyeWidth + unit * 0.044f, eyeHeight + unit * 0.05f), eyeRadius)
        drawRoundRect(eyeColor, Offset(x, y), Size(eyeWidth, eyeHeight), eyeRadius)
    }
    if (mood == MascotMood.COMPLETE) {
        val checkY = visor.center.y + unit * 0.08f
        drawLine(primary.copy(alpha = 0.9f), Offset(visor.center.x - unit * 0.07f, checkY), Offset(visor.center.x - unit * 0.015f, checkY + unit * 0.035f), unit * 0.018f, StrokeCap.Round)
        drawLine(primary.copy(alpha = 0.9f), Offset(visor.center.x - unit * 0.015f, checkY + unit * 0.035f), Offset(visor.center.x + unit * 0.09f, checkY - unit * 0.045f), unit * 0.018f, StrokeCap.Round)
    }
}

private fun DrawScope.drawCaptureBrackets(primary: Color, unit: Float) {
    val stroke = unit * 0.018f
    val left = size.width * 0.08f
    val right = size.width * 0.92f
    val top = size.height * 0.28f
    val bottom = size.height * 0.73f
    val length = unit * 0.13f
    val color = primary.copy(alpha = 0.88f)
    fun corner(start: Offset, horizontal: Float, vertical: Float) {
        drawLine(color, start, Offset(start.x + horizontal * length, start.y), stroke, StrokeCap.Round)
        drawLine(color, start, Offset(start.x, start.y + vertical * length), stroke, StrokeCap.Round)
    }
    corner(Offset(left, top), 1f, 1f)
    corner(Offset(right, top), -1f, 1f)
    corner(Offset(left, bottom), 1f, -1f)
    corner(Offset(right, bottom), -1f, -1f)
}

private fun DrawScope.drawOrbit(primary: Color, unit: Float, progress: Float) {
    val orbit = Rect(size.width * 0.12f, size.height * 0.52f, size.width * 0.88f, size.height * 0.87f)
    rotate(degrees = -10f, pivot = orbit.center) {
        drawArc(
            color = primary.copy(alpha = 0.34f),
            startAngle = 192f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = orbit.topLeft,
            size = orbit.size,
            style = androidx.compose.ui.graphics.drawscope.Stroke(unit * 0.014f, cap = StrokeCap.Round),
        )
        val angle = Math.toRadians((205f + progress * 300f).toDouble())
        val x = orbit.center.x + orbit.width * 0.43f * kotlin.math.cos(angle).toFloat()
        val y = orbit.center.y + orbit.height * 0.40f * kotlin.math.sin(angle).toFloat()
        drawCircle(primary.copy(alpha = 0.96f), radius = unit * 0.025f, center = Offset(x, y))
    }
}

private fun DrawScope.drawCelebration(primary: Color, unit: Float, progress: Float) {
    val center = Offset(size.width / 2f, size.height * 0.18f)
    repeat(4) { index ->
        val angle = Math.toRadians((progress * 360f + index * 90f).toDouble())
        val radius = unit * (0.12f + 0.04f * progress)
        drawCircle(
            color = primary.copy(alpha = 0.78f),
            radius = unit * 0.016f,
            center = Offset(center.x + kotlin.math.cos(angle).toFloat() * radius, center.y + kotlin.math.sin(angle).toFloat() * radius),
        )
    }
}
