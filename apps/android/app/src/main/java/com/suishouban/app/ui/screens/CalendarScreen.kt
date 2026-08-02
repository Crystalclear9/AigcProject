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
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventBusy
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.suishouban.app.AppUiState
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.ActionPlan
import com.suishouban.app.data.model.CardStatus
import com.suishouban.app.data.model.PlanItem
import com.suishouban.app.data.model.PlanItemKinds
import com.suishouban.app.data.model.primaryTime
import com.suishouban.app.ui.components.ActionCardItem
import com.suishouban.app.ui.components.SectionHeader
import com.suishouban.app.ui.components.formatDay
import com.suishouban.app.ui.components.formatSmartTime
import com.suishouban.app.ui.theme.AccentIconChip
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.DS
import com.suishouban.app.ui.theme.Ink
import com.suishouban.app.ui.theme.Line
import com.suishouban.app.ui.theme.MistBlue
import com.suishouban.app.ui.theme.Muted
import com.suishouban.app.ui.theme.SoftCard
import com.suishouban.app.ui.theme.softCardShadow
import com.suishouban.app.ui.theme.Warning
import com.suishouban.app.ui.theme.visualForCardType
import com.suishouban.app.ui.theme.visualForPriority
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
    val workBlocks = state.actionPlans.flatMap { plan ->
        plan.items
            .filter { it.kind == PlanItemKinds.WORK_BLOCK && it.startTime != null }
            .map { plan to it }
    }
    val workBlocksByDate = workBlocks.groupBy { (_, item) -> item.primaryLocalDate() }
        .filterKeys { it != null }
        .mapKeys { (key, _) -> requireNotNull(key) }
    var visibleMonth by remember { mutableStateOf(YearMonth.from(today)) }
    var selectedDate by remember { mutableStateOf(today) }
    val selectedCards = cardsByDate[selectedDate].orEmpty()
    val selectedWorkBlocks = workBlocksByDate[selectedDate].orEmpty()
    val undatedCards = active.filter { it.primaryLocalDate() == null }

    LazyColumn(
        modifier = Modifier.padding(horizontal = DS.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(DS.SectionGap),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            SectionHeader(
                "日历视图",
                "${active.size} 张卡 · ${workBlocks.size} 个时间块",
                icon = Icons.Outlined.CalendarMonth,
            )
        }
        item {
            MonthCalendarCard(
                month = visibleMonth,
                selectedDate = selectedDate,
                today = today,
                cardsByDate = cardsByDate,
                workBlocksByDate = workBlocksByDate,
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
                workBlockCount = selectedWorkBlocks.size,
                onComplete = onComplete,
            )
        }
        if (selectedWorkBlocks.isNotEmpty()) {
            item {
                WorkBlockSection(
                    selectedDate = selectedDate,
                    workBlocks = selectedWorkBlocks,
                    cards = state.cards,
                )
            }
        }
        if (undatedCards.isNotEmpty()) {
            item {
                Text("未定日期", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
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
    workBlocksByDate: Map<LocalDate, List<Pair<ActionPlan, PlanItem>>>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
) {
    val monthFormatter = remember { DateTimeFormatter.ofPattern("yyyy 年 M 月", Locale.CHINA) }
    val days = remember(month) { month.visibleCalendarDays() }

    Card(
        modifier = Modifier.fillMaxWidth().softCardShadow(),
        shape = RoundedCornerShape(DS.RadiusCard),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Line.copy(alpha = 0.7f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPreviousMonth) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "上个月", tint = BrandBlue)
                }
                Text(
                    text = month.format(monthFormatter),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = Ink,
                )
                IconButton(onClick = onNextMonth) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "下个月", tint = BrandBlue)
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
                        workBlockCount = workBlocksByDate[date].orEmpty().size,
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
    workBlockCount: Int,
    onClick: () -> Unit,
) {
    val borderColor = when {
        selected -> BrandBlue
        isToday -> BrandBlue.copy(alpha = 0.55f)
        else -> Line
    }
    // Selected day fills with the brand accent (white text) for a strong, unmistakable state;
    // today gets a soft tint; others stay white.
    val background = when {
        selected -> BrandBlue
        isToday -> MistBlue
        else -> Color.White
    }
    val dayColor = when {
        selected -> Color.White
        !inCurrentMonth -> Muted.copy(alpha = 0.5f)
        else -> Ink
    }

    Card(
        modifier = Modifier
            .aspectRatio(0.9f)
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(DS.RadiusButton),
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
                    color = dayColor,
                )
                Spacer(Modifier.weight(1f))
                if (cards.isNotEmpty() || workBlockCount > 0) {
                    Text(
                        text = (cards.size + workBlockCount).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) Color.White else BrandBlue,
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
                                .background(
                                    if (selected) Color.White else visualForPriority(card.priority).accent,
                                    CircleShape,
                                )
                        )
                    }
                }
            }
            if (workBlockCount > 0) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(
                            if (selected) Color.White else Color(0xFF805AD5),
                            CircleShape,
                        ),
                )
            }
        }
    }
}

@Composable
private fun WorkBlockSection(
    selectedDate: LocalDate,
    workBlocks: List<Pair<ActionPlan, PlanItem>>,
    cards: List<ActionCard>,
) {
    val label = remember(selectedDate) {
        selectedDate.format(DateTimeFormatter.ofPattern("M 月 d 日", Locale.CHINA))
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "$label 的计划时间块",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
        workBlocks.forEach { (plan, item) ->
            val parentTitle = cards.firstOrNull { it.id == plan.parentCardId }?.title ?: plan.objective
            SoftCard {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(item.title, fontWeight = FontWeight.Bold, color = Ink)
                    Text(
                        formatSmartTime(item.startTime),
                        style = MaterialTheme.typography.bodyMedium,
                        color = BrandBlue,
                    )
                    Text(
                        "来自：$parentTitle" +
                            item.estimatedMinutes?.let { " · 预计 $it 分钟" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedDaySection(
    selectedDate: LocalDate,
    cards: List<ActionCard>,
    workBlockCount: Int,
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
                color = Ink,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${cards.size + workBlockCount} 项",
                style = MaterialTheme.typography.labelMedium,
                color = Muted,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (cards.isEmpty() && workBlockCount == 0) {
            SoftCard {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AccentIconChip(icon = Icons.Outlined.EventBusy, accent = BrandBlue, size = 40.dp)
                    Text(
                        "当天暂无日程",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Muted,
                    )
                }
            }
        } else if (cards.isNotEmpty()) {
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
        modifier = Modifier.padding(horizontal = DS.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(DS.SectionGap),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            SectionHeader("日历视图", "${active.size} 项", icon = Icons.Outlined.CalendarMonth)
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
                        TimelineMarker(color = visualForPriority(card.priority).accent)
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

private fun PlanItem.primaryLocalDate(): LocalDate? {
    val value = startTime ?: deadline ?: return null
    return runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
}

private fun YearMonth.visibleCalendarDays(): List<LocalDate> {
    val firstDay = atDay(1)
    val start = firstDay.minusDays((firstDay.dayOfWeek.value - 1).toLong())
    // 固定 6 行，避免不同月份切换时网格高度跳动。
    return (0 until 42).map { start.plusDays(it.toLong()) }
}
