package com.suishouban.app.mascot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A compact, mood-tinted Mofei strip: the animated sprite over a soft mood halo, the "墨斐" name, and
 * the contextual message. The resident [FloatingMascot] is the app's single roaming Mofei presence;
 * this strip is reserved for the Settings mascot card where a live preview of the current mood is
 * the point of the surface. Pages do not embed it, to avoid multiple mascots on one screen.
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
