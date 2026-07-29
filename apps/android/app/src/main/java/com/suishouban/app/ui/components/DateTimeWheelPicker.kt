package com.suishouban.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.suishouban.app.data.model.ReminderModes
import com.suishouban.app.data.model.ReminderNode
import com.suishouban.app.data.model.ReminderSources
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private const val WHEEL_ROWS = 5
private val wheelRowHeight = 44.dp
private val displayDateFormatter = DateTimeFormatter.ofPattern("M月d日 E")

/**
 * Compatibility entry point used by existing screens. The implementation is a
 * center-snapping wheel and never writes its suggested initial value until confirm.
 */
@Composable
fun DateTimeWheelPickerDialog(
    initialValue: String?,
    title: String = "选择时间",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val zone = remember { ZoneId.systemDefault() }
    val now = remember { OffsetDateTime.now(zone) }
    val parsedInitial = remember(initialValue, zone) {
        runCatching {
            OffsetDateTime.parse(initialValue)
                .atZoneSameInstant(zone)
                .toOffsetDateTime()
        }.getOrNull()
    }
    val visualInitial = parsedInitial ?: now
        .plusMinutes(1)
        .withSecond(0)
        .withNano(0)
    val firstDate = remember(now) { now.toLocalDate().minusDays(30) }
    val dates = remember(firstDate) {
        (0..760).map { firstDate.plusDays(it.toLong()) }
    }
    var dateIndex by remember {
        mutableIntStateOf(
            (visualInitial.toLocalDate().toEpochDay() - firstDate.toEpochDay())
                .toInt()
                .coerceIn(dates.indices)
        )
    }
    var hour by remember { mutableIntStateOf(visualInitial.hour) }
    var minute by remember { mutableIntStateOf(visualInitial.minute) }
    val minuteValues = remember { (0 until 60).toList() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    "时区 ${zone.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WheelColumn(
                        values = dates.map { it.format(displayDateFormatter) },
                        selectedIndex = dateIndex,
                        onSelected = { dateIndex = it },
                        modifier = Modifier.weight(1.8f),
                        contentDescription = "日期",
                    )
                    WheelColumn(
                        values = (0..23).map { "%02d".format(it) },
                        selectedIndex = hour,
                        onSelected = { hour = it },
                        modifier = Modifier.weight(0.75f),
                        contentDescription = "小时",
                    )
                    WheelColumn(
                        values = minuteValues.map { "%02d".format(it) },
                        selectedIndex = minuteValues.indexOf(minute).coerceAtLeast(0),
                        onSelected = { minute = minuteValues[it] },
                        modifier = Modifier.weight(0.75f),
                        contentDescription = "分钟",
                    )
                }
                Text(
                    "精确到分钟",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        dates[dateIndex]
                            .atTime(hour, minute)
                            .atZone(zone)
                            .toOffsetDateTime()
                            .toString()
                    )
                },
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            Row {
                if (onClear != null) {
                    TextButton(onClick = onClear) { Text("清除时间") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        shape = RoundedCornerShape(24.dp),
    )
}

@Composable
fun ReminderNodesEditor(
    nodes: List<ReminderNode>,
    deadline: String?,
    onChange: (List<ReminderNode>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingIndex by remember { mutableIntStateOf(-2) }
    var editingAbsoluteIndex by remember { mutableIntStateOf(-2) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("提醒节点", style = MaterialTheme.typography.labelLarge)
        if (nodes.isEmpty()) {
            Text(
                "暂无提醒。智能建议会在确认后才创建。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            nodes.forEachIndexed { index, node ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            node.displayLabel(),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    if (node.mode == ReminderModes.ABSOLUTE) {
                                        editingAbsoluteIndex = index
                                    } else {
                                        editingIndex = index
                                    }
                                },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            checked = node.enabled,
                            onCheckedChange = { enabled ->
                                onChange(nodes.toMutableList().apply {
                                    this[index] = node.copy(
                                        enabled = enabled,
                                        revision = node.revision + 1,
                                    )
                                })
                            },
                        )
                        TextButton(onClick = {
                            onChange(nodes.filterIndexed { itemIndex, _ -> itemIndex != index })
                        }) { Text("删除") }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { editingIndex = -1 },
                enabled = !deadline.isNullOrBlank(),
            ) {
                Text("截止前")
            }
            OutlinedButton(onClick = { editingAbsoluteIndex = -1 }) {
                Text("指定时间")
            }
        }
        if (deadline.isNullOrBlank()) {
            Text(
                "设置截止时间后才能添加相对提醒，也可以直接指定提醒时刻。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (nodes.count { it.enabled } > 4) {
            Text(
                "提醒较多，建议只保留真正需要行动的节点。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (editingIndex >= -1) {
        ReminderOffsetWheelPickerDialog(
            initialMinutes = nodes.getOrNull(editingIndex)?.offsetMinutes,
            onDismiss = { editingIndex = -2 },
            onConfirm = { offset ->
                val updated = if (editingIndex >= 0) {
                    nodes.toMutableList().apply {
                        this[editingIndex] = this[editingIndex].copy(
                            mode = ReminderModes.RELATIVE,
                            offsetMinutes = offset,
                            absoluteTime = null,
                            enabled = true,
                            source = ReminderSources.USER,
                            revision = this[editingIndex].revision + 1,
                        )
                    }
                } else {
                    (nodes + ReminderNode(offsetMinutes = offset)).toMutableList()
                }
                onChange(
                    updated
                        .distinctBy { it.mode to (it.offsetMinutes ?: it.absoluteTime) }
                        .sortedByDescending { it.offsetMinutes ?: Long.MIN_VALUE }
                )
                editingIndex = -2
            },
        )
    }
    if (editingAbsoluteIndex >= -1) {
        DateTimeWheelPickerDialog(
            initialValue = nodes.getOrNull(editingAbsoluteIndex)?.absoluteTime,
            title = "指定提醒时间",
            onDismiss = { editingAbsoluteIndex = -2 },
            onConfirm = { instant ->
                val parsed = runCatching { OffsetDateTime.parse(instant) }.getOrNull()
                val parsedDeadline = deadline?.let {
                    runCatching { OffsetDateTime.parse(it) }.getOrNull()
                }
                val valid = parsed?.isAfter(OffsetDateTime.now()) == true &&
                    (parsedDeadline == null || !parsed.isAfter(parsedDeadline))
                if (valid) {
                    val updated = if (editingAbsoluteIndex >= 0) {
                        nodes.toMutableList().apply {
                            val current = this[editingAbsoluteIndex]
                            this[editingAbsoluteIndex] = current.copy(
                                mode = ReminderModes.ABSOLUTE,
                                absoluteTime = instant,
                                offsetMinutes = null,
                                enabled = true,
                                source = ReminderSources.USER,
                                revision = current.revision + 1,
                            )
                        }
                    } else {
                        nodes + ReminderNode(
                            mode = ReminderModes.ABSOLUTE,
                            absoluteTime = instant,
                        )
                    }
                    onChange(updated.distinctBy { it.mode to (it.absoluteTime ?: it.offsetMinutes) })
                    editingAbsoluteIndex = -2
                }
            },
        )
    }
}

@Composable
private fun ReminderOffsetWheelPickerDialog(
    initialMinutes: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val safeInitial = initialMinutes ?: 30L
    var day by remember { mutableIntStateOf((safeInitial / 1440).toInt().coerceIn(0, 30)) }
    var hour by remember { mutableIntStateOf(((safeInitial % 1440) / 60).toInt().coerceIn(0, 23)) }
    var minute by remember { mutableIntStateOf((safeInitial % 60).toInt().coerceIn(0, 59)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("截止前提醒", fontWeight = FontWeight.SemiBold) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                WheelColumn(
                    values = (0..30).map { "${it}天" },
                    selectedIndex = day,
                    onSelected = { day = it },
                    modifier = Modifier.weight(1f),
                    contentDescription = "提前天数",
                )
                WheelColumn(
                    values = (0..23).map { "${it}小时" },
                    selectedIndex = hour,
                    onSelected = { hour = it },
                    modifier = Modifier.weight(1f),
                    contentDescription = "提前小时",
                )
                WheelColumn(
                    values = (0..59).map { "${it}分钟" },
                    selectedIndex = minute,
                    onSelected = { minute = it },
                    modifier = Modifier.weight(1f),
                    contentDescription = "提前分钟",
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val offset = day * 1440L + hour * 60L + minute
                    onConfirm(offset.coerceAtLeast(1L))
                },
                shape = RoundedCornerShape(14.dp),
            ) { Text(if (initialMinutes == null) "添加" else "更新") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        shape = RoundedCornerShape(24.dp),
    )
}

@Composable
private fun WheelColumn(
    values: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String,
) {
    val safeIndex = selectedIndex.coerceIn(values.indices)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = safeIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = state)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val centeredIndex by remember(state, values.size) {
        derivedStateOf {
            val layout = state.layoutInfo
            val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            layout.visibleItemsInfo.minByOrNull { item ->
                abs((item.offset + item.size / 2) - center)
            }?.index?.coerceIn(values.indices) ?: safeIndex
        }
    }
    LaunchedEffect(state, values.size) {
        snapshotFlow { centeredIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (index != selectedIndex) {
                    onSelected(index)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
    }
    LaunchedEffect(safeIndex) {
        if (!state.isScrollInProgress && centeredIndex != safeIndex) {
            state.scrollToItem(safeIndex)
        }
    }
    Box(
        modifier = modifier
            .height(wheelRowHeight * WHEEL_ROWS)
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.32f to Color.Black,
                        0.68f to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        LazyColumn(
            state = state,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = wheelRowHeight * 2),
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(values.size) { index ->
                val selected = index == centeredIndex
                val emphasis by animateFloatAsState(
                    targetValue = if (selected) 1f else 0.62f,
                    label = "wheel-emphasis",
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(wheelRowHeight)
                        .semantics {
                            this.selected = selected
                        }
                        .clickable(role = Role.Button) {
                            scope.launch { state.animateScrollToItem(index) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        values[index],
                        style = if (selected) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = emphasis),
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(wheelRowHeight)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

internal fun normalizedReminderMinutes(value: String): Int {
    if (value.isBlank()) return 180
    if (listOf("尽快", "马上", "现在").any(value::contains)) return 0
    val days = Regex("""(\d+)\s*(?:天|日)""").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val hours = Regex("""(\d+)\s*(?:小时|时)""").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val minutes = Regex("""(\d+)\s*(?:分钟|分)""").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return days * 1440 + hours * 60 + minutes
}
