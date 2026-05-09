package com.suishouban.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suishouban.app.AppUiState
import com.suishouban.app.data.model.CardStatus
import com.suishouban.app.data.model.Priority
import com.suishouban.app.data.model.primaryTime
import com.suishouban.app.ui.components.ActionCardItem
import com.suishouban.app.ui.components.Pill
import com.suishouban.app.ui.components.SectionHeader
import com.suishouban.app.ui.components.brandGradient
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.EventBlue
import com.suishouban.app.ui.theme.Line
import com.suishouban.app.ui.theme.NoteGreen
import com.suishouban.app.ui.theme.PromiseOrange
import com.suishouban.app.ui.theme.TaskRed

@Composable
fun HomeScreen(
    state: AppUiState,
    onImport: () -> Unit,
    onCards: () -> Unit,
    onCalendar: () -> Unit,
    onComplete: (String) -> Unit,
) {
    val activeCards = state.cards.filter { it.status != CardStatus.ARCHIVED && it.status != CardStatus.DONE }
    val urgentCards = activeCards.filter { it.priority == Priority.HIGH }.take(3)
    val needConfirm = activeCards.count { it.needConfirm.isNotEmpty() }
    val reminders = state.cards.sumOf { it.reminders.size }
    val coverage = state.cards.map { it.cardType }.toSet().size
    val timedCards = state.cards.count { it.primaryTime() != null }
    val savedMinutes = (state.cards.size * 2.5).toInt()

    LazyColumn(
        modifier = Modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(brandGradient())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("随手办", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                Text(
                    "一截图，即执行",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.9f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onImport,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = BrandBlue)
                        Spacer(Modifier.width(8.dp))
                        Text("导入截图", color = BrandBlue, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onCalendar,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.18f)),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("日历")
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("待办", activeCards.size.toString(), TaskRed, Modifier.weight(1f))
                MetricTile("待确认", needConfirm.toString(), PromiseOrange, Modifier.weight(1f))
                MetricTile("高优先", urgentCards.size.toString(), EventBlue, Modifier.weight(1f))
            }
        }

        item {
            ImpactDashboard(
                savedMinutes = savedMinutes,
                reminders = reminders,
                coverage = coverage,
                timedCards = timedCards,
                engine = state.engine.ifBlank { if (state.settings.preferCloudModel) "云端优先" else "本地兜底" },
            )
        }

        item {
            SectionHeader("今日关注", if (activeCards.isEmpty()) "暂无事项" else "${activeCards.size} 项")
        }

        if (activeCards.isEmpty()) {
            item {
                EmptyHomeCard(onImport = onImport)
            }
        } else {
            items(urgentCards.ifEmpty { activeCards.take(3) }) { card ->
                ActionCardItem(
                    card = card,
                    compact = true,
                    onComplete = { onComplete(card.id) },
                )
            }
            item {
                Button(
                    onClick = onCards,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                ) {
                    Icon(Icons.Outlined.Checklist, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("查看全部行动卡")
                }
            }
        }

        item {
            SectionHeader("场景覆盖")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SceneTile("课程通知", TaskRed, Modifier.weight(1f))
                SceneTile("比赛报名", EventBlue, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SceneTile("社团活动", NoteGreen, Modifier.weight(1f))
                SceneTile("聊天承诺", PromiseOrange, Modifier.weight(1f))
            }
        }

        item {
            Spacer(Modifier.height(92.dp))
        }
    }
}

@Composable
private fun ImpactDashboard(
    savedMinutes: Int,
    reminders: Int,
    coverage: Int,
    timedCards: Int,
    engine: String,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Insights, contentDescription = null, tint = BrandBlue)
                Spacer(Modifier.width(8.dp))
                Text("AI 行动闭环看板", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("节省分钟", savedMinutes.toString(), BrandBlue, Modifier.weight(1f))
                MetricTile("提醒", reminders.toString(), PromiseOrange, Modifier.weight(1f))
                MetricTile("有时间", timedCards.toString(), EventBlue, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("OCR", color = BrandBlue)
                Pill("抽取", color = TaskRed, soft = Color(0xFFFFECEC))
                Pill("预览", color = PromiseOrange, soft = Color(0xFFFFF0E6))
                Pill("提醒", color = NoteGreen, soft = Color(0xFFEAF8F1))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Route, contentDescription = null, tint = NoteGreen)
                Spacer(Modifier.width(8.dp))
                Text(
                    "卡片类型覆盖 $coverage/4，当前引擎：$engine",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.92f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = color)
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SceneTile(label: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(72.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(
                modifier = Modifier
                    .width(5.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun EmptyHomeCard(onImport: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("从第一张截图开始", style = MaterialTheme.typography.titleLarge)
            Text(
                "课程通知、比赛海报、聊天约定都可以转成行动卡。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onImport, shape = RoundedCornerShape(16.dp)) {
                Text("导入截图")
            }
        }
    }
}
