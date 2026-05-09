package com.suishouban.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.Line

@Composable
fun Pill(
    text: String,
    color: Color = BrandBlue,
    soft: Color = Color(0xFFEAF2FF),
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val modifier = Modifier
        .heightIn(min = 32.dp)
        .background(if (selected) color else soft, CircleShape)
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
        .padding(horizontal = 12.dp, vertical = 7.dp)
    Box(modifier = modifier) {
        Text(
            text = text,
            color = if (selected) Color.White else color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun NeutralPill(text: String, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    Pill(
        text = text,
        color = if (selected) BrandBlue else MaterialTheme.colorScheme.onSurface,
        soft = if (selected) BrandBlue else Line.copy(alpha = 0.55f),
        selected = selected,
        onClick = onClick,
    )
}
