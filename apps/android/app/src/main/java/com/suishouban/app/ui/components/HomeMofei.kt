package com.suishouban.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.suishouban.app.R

enum class HomeMofeiVariant(
    @DrawableRes val drawableRes: Int,
    val contentDescription: String,
    val restingRotation: Float,
    val bobDistanceDp: Float,
) {
    HERO(
        drawableRes = R.drawable.mofei_home_hero,
        contentDescription = "开心欢迎你的莫斐",
        restingRotation = 0f,
        bobDistanceDp = 5f,
    ),
    STATUS(
        drawableRes = R.drawable.mofei_home_status,
        contentDescription = "正在查看行动状态的莫斐",
        restingRotation = -2f,
        bobDistanceDp = 3f,
    ),
    EMPTY(
        drawableRes = R.drawable.mofei_home_empty,
        contentDescription = "等待识别第一张截图的莫斐",
        restingRotation = 2f,
        bobDistanceDp = 4f,
    ),
}

/**
 * Home-only branded artwork. This component intentionally does not reuse the draggable floating
 * mascot because these three images are part of each card's composition, not independent controls.
 */
@Composable
fun HomeMofei(
    variant: HomeMofeiVariant,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    decorative: Boolean = false,
) {
    val verticalOffsetDp = if (reduceMotion) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "home-mofei-${variant.name.lowercase()}")
        val animatedOffset by transition.animateFloat(
            initialValue = -variant.bobDistanceDp,
            targetValue = variant.bobDistanceDp,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3_600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "home-mofei-bob",
        )
        animatedOffset
    }

    Image(
        painter = painterResource(variant.drawableRes),
        contentDescription = if (decorative) null else variant.contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier.graphicsLayer {
            // Reduced-motion keeps the expressive resting pose but removes continuous movement.
            translationY = verticalOffsetDp.dp.toPx()
            rotationZ = variant.restingRotation
        },
    )
}
