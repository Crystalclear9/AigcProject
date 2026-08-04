package com.suishouban.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.effectiveReminderNodes
import com.suishouban.app.data.model.CardTypes
import com.suishouban.app.data.model.mergeReminderLabels
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.Line
import com.suishouban.app.ui.theme.visualForCardType
import com.suishouban.app.ui.theme.visualForPriority

@Composable
fun PreviewActionsCard(
    previewActions: List<String>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("即将执行", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            previewActions.forEach { action ->
                Row {
                    Icon(Icons.Outlined.EventAvailable, contentDescription = null, tint = BrandBlue)
                    Spacer(Modifier.width(8.dp))
                    Text(action, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun DraftEditor(
    card: ActionCard,
    onChange: (ActionCard) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visual = visualForCardType(card.cardType)
    val priorityVisual = visualForPriority(card.priority)
    var pickerField by remember(card.id) { mutableStateOf<String?>(null) }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = priorityVisual.container),
        border = BorderStroke(1.5.dp, priorityVisual.accent),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeutralPill(text = visual.label, selected = true)
                NeutralPill(
                    text = if (card.priorityLocked) "${priorityVisual.label} · 已锁定" else priorityVisual.label,
                )
                if (card.needConfirm.isNotEmpty()) {
                    NeutralPill(text = "待确认 ${card.needConfirm.size}")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                }
            }

            OutlinedTextField(
                value = card.title,
                onValueChange = { onChange(card.copy(title = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("标题") },
                shape = RoundedCornerShape(16.dp),
            )
            OutlinedTextField(
                value = card.summary,
                onValueChange = { onChange(card.copy(summary = it)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text("摘要") },
                shape = RoundedCornerShape(16.dp),
            )
            if (card.cardType == CardTypes.EVENT) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DraftTimeField(
                        label = "开始时间",
                        value = card.startTime,
                        onClick = { pickerField = "start" },
                        modifier = Modifier.weight(1f),
                    )
                    DraftTimeField(
                        label = "结束时间",
                        value = card.endTime,
                        onClick = { pickerField = "end" },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                DraftTimeField(
                    label = "截止时间",
                    value = card.deadline,
                    onClick = { pickerField = "deadline" },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = card.location ?: "",
                    onValueChange = { onChange(card.copy(location = it.ifBlank { null })) },
                    modifier = Modifier.weight(1f),
                    label = { Text("地点/平台") },
                    shape = RoundedCornerShape(16.dp),
                )
                OutlinedTextField(
                    value = card.submitMethod ?: "",
                    onValueChange = { onChange(card.copy(submitMethod = it.ifBlank { null })) },
                    modifier = Modifier.weight(1f),
                    label = { Text("提交方式") },
                    shape = RoundedCornerShape(16.dp),
                )
            }
            OutlinedTextField(
                value = card.materials.joinToString("，"),
                onValueChange = { onChange(card.copy(materials = splitPreviewList(it))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("提交物/准备物") },
                shape = RoundedCornerShape(16.dp),
            )
            ReminderNodesEditor(
                nodes = card.effectiveReminderNodes(),
                deadline = card.deadline,
                onChange = { nodes ->
                    onChange(
                        card.copy(
                            reminders = nodes.map { it.displayLabel() },
                            reminderNodes = nodes,
                        )
                    )
                },
            )
            OutlinedTextField(
                value = card.needConfirm.joinToString("，"),
                onValueChange = { onChange(card.copy(needConfirm = splitPreviewList(it))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("待确认字段") },
                shape = RoundedCornerShape(16.dp),
            )
        }
    }
    pickerField?.let { field ->
        DateTimeWheelPickerDialog(
            initialValue = when (field) {
                "start" -> card.startTime
                "end" -> card.endTime
                else -> card.deadline
            },
            title = when (field) {
                "start" -> "选择开始时间"
                "end" -> "选择结束时间"
                else -> "选择截止时间"
            },
            onDismiss = { pickerField = null },
            onClear = {
                onChange(
                    when (field) {
                        "start" -> card.copy(startTime = null)
                        "end" -> card.copy(endTime = null)
                        else -> card.copy(deadline = null)
                    }
                )
                pickerField = null
            },
            onConfirm = { value ->
                onChange(
                    when (field) {
                        "start" -> card.copy(startTime = value)
                        "end" -> card.copy(endTime = value)
                        else -> card.copy(deadline = value)
                    }
                )
                pickerField = null
            },
        )
    }
}

@Composable
private fun DraftTimeField(
    label: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = formatSmartTime(value),
        onValueChange = {},
        readOnly = true,
        modifier = modifier,
        label = { Text(label) },
        trailingIcon = {
            IconButton(onClick = onClick) {
                Icon(Icons.Outlined.Schedule, contentDescription = "选择$label")
            }
        },
        shape = RoundedCornerShape(16.dp),
    )
}

private fun splitPreviewList(value: String): List<String> {
    return value.split("，", ",", "、")
        .map { it.trim() }
        .filter { it.isNotBlank() }
}
