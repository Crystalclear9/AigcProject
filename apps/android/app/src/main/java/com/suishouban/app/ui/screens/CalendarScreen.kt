package com.suishouban.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.suishouban.app.AppUiState
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardStatus
import com.suishouban.app.data.model.primaryTime
import com.suishouban.app.ui.components.ActionCardItem
import com.suishouban.app.ui.components.SectionHeader
import com.suishouban.app.ui.components.formatDay
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.Line
import com.suishouban.app.ui.theme.MistBlue
import com.suishouban.app.ui.theme.Warning
import com.suishouban.app.ui.theme.visualForCardType
import java.time.LocalDate
import java.time.YearMonth
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CalendarScreen(
    state: AppUiState,
    onComplete: (String) -> Unit,
) {
    val active = state.cards.filter { it.status != CardStatus.ARCHIVED }
    val today = LocalDate.now()
    val cardsByDate = active.groupByDate()
    var visibleMonth by remember { mutableStateOf(YearMonth.from(today)) }
    var selectedDate by remember { mutableStateOf(today) }
    val selectedCards = cardsByDate[selectedDate].orEmpty()
    val undatedCards = active.filter { it.primaryLocalDate() == null }

    LazyColumn(
        modifier = Modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            SectionHeader("日历视图", "${active.size} 项")
        }
        item {
            MonthCalendarCard(
                month = visibleMonth,
                selectedDate = selectedDate,
                today = today,
                cardsByDate = cardsByDate,
                onPreviousMonth = {
                    visibleMonth = visibleMonth.minusMonths(1)
                    selectedDate = visibleMonth.atDay(1)
                },
                onNextMonth = {
                    visibleMonth = visibleMonth.plusMonths(1)
                    selectedDate = visibleMonth.atDay(1)
                },
                onSelectDate = { selectedDate = it },
            )
        }
        item {
            SelectedDaySection(
                selectedDate = selectedDate,
                cards = selectedCards,
                onComplete = onComplete,
            )
        }
        if (undatedCards.isNotEmpty()) {
            item {
                Text("未定日期", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            items(undatedCards, key = { it.id }) { card ->
                ActionCardItem(
                    card = card,
                    compact = true,
                    onComplete = if (card.status == CardStatus.DONE) null else ({ onComplete(card.id) }),
                )
            }
        }
        item {
            Spacer(Modifier.height(92.dp))
        }
    }
}

@Composable
private fun MonthCalendarCard(
    month: YearMonth,
    selectedDate: LocalDate,
    today: LocalDate,
    cardsByDate: Map<LocalDate, List<ActionCard>>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
) {
    val monthFormatter = remember { DateTimeFormatter.ofPattern("yyyy 年 M 月", Locale.CHINA) }
    val days = remember(month) { month.visibleCalendarDays() }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPreviousMonth) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "上个月")
                }
                Text(
                    text = month.format(monthFormatter),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = onNextMonth) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "下个月")
                }
            }

            WeekHeader()
            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = false,
            ) {
                items(days, key = { it.toString() }) { date ->
                    CalendarDayCell(
                        date = date,
                        inCurrentMonth = YearMonth.from(date) == month,
                        selected = date == selectedDate,
                        isToday = date == today,
                        cards = cardsByDate[date].orEmpty(),
                        onClick = { onSelectDate(date) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekHeader() {
    val weekDays = listOf("一", "二", "三", "四", "五", "六", "日")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        weekDays.forEach { day ->
            Text(
                text = day,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CalendarDayCell(
    date: LocalDate,
    inCurrentMonth: Boolean,
    selected: Boolean,
    isToday: Boolean,
    cards: List<ActionCard>,
    onClick: () -> Unit,
) {
    val borderColor = when {
        selected -> BrandBlue
        isToday -> BrandBlue.copy(alpha = 0.55f)
        else -> Line
    }
    val background = if (selected) MistBlue else Color.White

    Card(
        modifier = Modifier
            .aspectRatio(0.9f)
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = background),
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected || isToday) FontWeight.Bold else FontWeight.Medium,
                    color = if (inCurrentMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                )
                Spacer(Modifier.weight(1f))
                if (cards.isNotEmpty()) {
                    Text(
                        text = cards.size.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = BrandBlue,
                    )
                }
            }
            // 日期格只保留数量和类型色点，完整卡片交给下方详情区展示。
            if (cards.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(cards.take(4), key = { it.id }) { card ->
                        Box(
                            Modifier
                                .size(7.dp)
                                .background(visualForCardType(card.cardType).color, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedDaySection(
    selectedDate: LocalDate,
    cards: List<ActionCard>,
    onComplete: (String) -> Unit,
) {
    val selectedDateLabel = remember(selectedDate) {
        selectedDate.format(DateTimeFormatter.ofPattern("M 月 d 日", Locale.CHINA))
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                selectedDateLabel,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${cards.size} 项",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (cards.isEmpty()) {
            Card(
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Line),
                colors = CardDefaults.cardColors(containerColor = Color.White),
            ) {
                Text(
                    "当天暂无日程",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            cards.forEach { card ->
                ActionCardItem(
                    card = card,
                    compact = true,
                    onComplete = if (card.status == CardStatus.DONE) null else ({ onComplete(card.id) }),
                )
            }
        }
    }
}

@Composable
private fun TimelineCalendarMode(
    active: List<ActionCard>,
    onComplete: (String) -> Unit,
) {
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

private fun List<ActionCard>.groupByDate(): Map<LocalDate, List<ActionCard>> =
    mapNotNull { card -> card.primaryLocalDate()?.let { date -> date to card } }
        .groupBy({ it.first }, { it.second })

private fun ActionCard.primaryLocalDate(): LocalDate? {
    val value = primaryTime() ?: return null
    return runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
}

private fun YearMonth.visibleCalendarDays(): List<LocalDate> {
    val firstDay = atDay(1)
    val start = firstDay.minusDays((firstDay.dayOfWeek.value - 1).toLong())
    // 固定 6 行，避免不同月份切换时网格高度跳动。
    return (0 until 42).map { start.plusDays(it.toLong()) }
}
