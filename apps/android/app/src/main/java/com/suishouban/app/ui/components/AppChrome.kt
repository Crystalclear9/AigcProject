package com.suishouban.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.MistBlue
import com.suishouban.app.ui.theme.Paper

@Composable
fun GradientScreen(
    padding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(MistBlue, Paper, MaterialTheme.colorScheme.background)
                )
            )
            .padding(padding)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp))
        ) {
            content()
        }
    }
}

fun brandGradient(): Brush = Brush.linearGradient(
    colors = listOf(BrandBlue, androidx.compose.ui.graphics.Color(0xFF6A9BFF))
)
