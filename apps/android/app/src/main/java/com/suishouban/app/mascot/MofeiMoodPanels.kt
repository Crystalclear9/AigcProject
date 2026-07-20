package com.suishouban.app.mascot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A shared, mood-tinted Mofei panel that pages embed to give the mascot a real presence in their
 * layout instead of a bare row. It reuses the same [MascotState] pipeline for color and copy, so
 * every screen reacts to the same contextual mood. Two layouts are offered:
 *
 * - [MofeiMoodHero]: a large centered hero for empty states — sprite over a soft mood halo, a title,
 *   and the contextual message, optionally followed by page-supplied actions.
 * - [MofeiMoodBanner]: a compact horizontal strip (sprite + name + message) for in-flow placement,
 *   replacing the hand-rolled companion rows the screens used to build individually.
 */
@Composable
fun MofeiMoodHero(
    state: MascotState,
    title: String,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    spriteSize: Dp = 128.dp,
    message: String? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val profile = MascotVisuals.profileFor(state, reduceMotion)
    val accent = Color(profile.primaryArgb)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.14f), Color.White.copy(alpha = 0.86f)),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(28.dp))
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(spriteSize + 24.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.28f), Color.Transparent),
                        ),
                    ),
            )
            MofeiPetSprite(
                state = state,
                reduceMotion = reduceMotion,
                modifier = Modifier.size(spriteSize),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            message ?: profile.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actions != null) actions()
    }
}

/**
 * Compact in-flow mood strip: the animated sprite, the "墨斐" name, and the contextual message. This
 * is the consistent replacement for the per-screen companion rows so every page tints and phrases
 * the mascot the same way.
 */
@Composable
fun MofeiMoodBanner(
    state: MascotState,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    spriteSize: Dp = 60.dp,
    title: String = "墨斐",
    message: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val profile = MascotVisuals.profileFor(state, reduceMotion)
    val accent = Color(profile.primaryArgb)
    val base = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(20.dp))
        .background(
            Brush.horizontalGradient(
                listOf(accent.copy(alpha = 0.14f), Color.White.copy(alpha = 0.9f)),
            ),
        )
        .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
    val clickable = if (onClick == null) base else base.clickable(onClick = onClick)
    Row(
        modifier = modifier
            .then(clickable)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(spriteSize + 10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.radialGradient(listOf(accent.copy(alpha = 0.24f), Color.Transparent)),
                    ),
            )
            MofeiPetSprite(
                state = state,
                reduceMotion = reduceMotion,
                modifier = Modifier.size(spriteSize),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                message ?: profile.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
