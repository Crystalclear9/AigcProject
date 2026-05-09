package com.suishouban.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suishouban.app.AppUiState
import com.suishouban.app.data.model.CardStatus
import com.suishouban.app.data.model.primaryTime
import com.suishouban.app.ui.components.ActionCardItem
import com.suishouban.app.ui.components.SectionHeader
import com.suishouban.app.ui.components.formatDay
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.Line
import com.suishouban.app.ui.theme.Warning
import com.suishouban.app.ui.theme.visualForCardType

@Composable
fun CalendarScreen(
    state: AppUiState,
    onComplete: (String) -> Unit,
) {
    val active = state.cards.filter { it.status != CardStatus.ARCHIVED }
    val groups = active.groupBy { formatDay(it.primaryTime()) }.toSortedMap()
    val conflicts = active.groupBy { it.primaryTime() }.filter { (time, cards) -> time != null && cards.size > 1 }

    LazyColumn(
        modifier = Modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            SectionHeader("日历视图", "${active.size} 项")
        }
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Line),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = if (conflicts.isEmpty()) BrandBlue else Warning)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (conflicts.isEmpty()) "暂无时间冲突" else "发现 ${conflicts.size} 处时间重叠",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        "任务截止点、事件时间段和承诺提醒统一进入时间线。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (groups.isEmpty()) {
            item {
                Text("暂无日程", style = MaterialTheme.typography.titleLarge)
            }
        } else {
            groups.forEach { (day, cards) ->
                item {
                    Text(day, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                items(cards, key = { it.id }) { card ->
                    Row {
                        TimelineMarker(color = visualForCardType(card.cardType).color)
                        Spacer(Modifier.width(10.dp))
                        ActionCardItem(
                            card = card,
                            modifier = Modifier.weight(1f),
                            compact = true,
                            onComplete = if (card.status == CardStatus.DONE) null else ({ onComplete(card.id) }),
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(92.dp))
        }
    }
}

@Composable
private fun TimelineMarker(color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(14.dp)
                .background(color, CircleShape)
        )
        Box(
            Modifier
                .width(2.dp)
                .height(150.dp)
                .background(color.copy(alpha = 0.22f))
        )
    }
}
