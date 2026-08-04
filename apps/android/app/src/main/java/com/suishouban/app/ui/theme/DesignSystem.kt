package com.suishouban.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single source of truth for the app's visual language. Every screen pulls from these tokens so
 * cards, spacing, radii, and accents stay coordinated across Home / Cards / Calendar / Import /
 * Preview / Settings. Changing a token here restyles the whole product.
 */
object DS {
    // Corner-radius scale — locked. Big surfaces, inner tiles, pills, buttons.
    val RadiusCard: Dp = 28.dp
    val RadiusTile: Dp = 18.dp
    val RadiusButton: Dp = 14.dp
    val RadiusChipBadge: Dp = 12.dp

    // Spacing rhythm.
    val ScreenPadding: Dp = 20.dp
    val SectionGap: Dp = 20.dp
    val CardPadding: Dp = 20.dp
    val ItemGap: Dp = 12.dp

    // Neutral surfaces (cool-grey family — one palette, no warm drift).
    val TileNeutral = Color(0xFFF4F7FD)
    val CardWhite = Color.White
}

/** Soft brand-tinted shadow so cards feel lifted without harsh black drop shadows. */
fun Modifier.softCardShadow(radius: Dp = DS.RadiusCard): Modifier =
    this.shadow(10.dp, RoundedCornerShape(radius), ambientColor = BrandBlue.copy(alpha = 0.10f), spotColor = BrandBlue.copy(alpha = 0.10f))

/**
 * The one card style used across the app: white fill, hairline border, soft brand shadow, no
 * heavy Material elevation. Pass a custom radius only for special surfaces.
 */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    radius: Dp = DS.RadiusCard,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .softCardShadow(radius),
        shape = RoundedCornerShape(radius),
        colors = CardDefaults.cardColors(containerColor = DS.CardWhite),
        border = BorderStroke(1.dp, Line.copy(alpha = 0.7f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = { content() },
    )
}

/** Rounded-square icon chip with a tinted background — the app's signature small accent shape. */
@Composable
fun AccentIconChip(
    icon: ImageVector,
    accent: Color = BrandBlue,
    size: Dp = 30.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(DS.RadiusChipBadge))
            .background(accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(size * 0.58f))
    }
}

/**
 * Unified section header: an accent icon chip, a bold title, and an optional right-aligned count.
 * Replaces the old sparkle-prefixed row so every screen's headers match.
 */
@Composable
fun DsSectionHeader(
    title: String,
    icon: ImageVector,
    trailing: String? = null,
    accent: Color = BrandBlue,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccentIconChip(icon = icon, accent = accent, size = 30.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelMedium,
                color = Muted,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Thin grouping rule tinted to the neutral line color. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Line.copy(alpha = 0.7f)),
    )
}

/** Vertical page-title block used at the top of secondary screens for editorial hierarchy. */
@Composable
fun ScreenTitle(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
    ) {
        Text(
            eyebrow.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = BrandBlue,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = Ink,
        )
    }
}

/** Page background wash — a calm vertical gradient shared by every screen. */
fun screenBackgroundBrush(): Brush = Brush.verticalGradient(
    colors = listOf(MistBlue, Paper, Paper),
)
