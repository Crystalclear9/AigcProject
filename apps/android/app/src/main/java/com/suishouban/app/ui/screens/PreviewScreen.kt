package com.suishouban.app.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suishouban.app.AppUiState
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.ui.components.NeutralPill
import com.suishouban.app.ui.components.SectionHeader
import com.suishouban.app.ui.components.WorkflowStrip
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.Line
import com.suishouban.app.ui.theme.visualForCardType

@Composable
fun PreviewScreen(
    state: AppUiState,
    onUpdateDraft: (ActionCard) -> Unit,
    onRemoveDraft: (String) -> Unit,
    onConfirm: () -> Unit,
    onImport: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            SectionHeader("动作预览", if (state.engine.isBlank()) null else state.engine)
        }

        item {
            WorkflowStrip(currentStep = 2, modifier = Modifier.fillMaxWidth())
        }

        if (state.draftCards.isEmpty()) {
            item {
                EmptyPreviewCard(onImport)
            }
        } else {
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Line),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("即将执行", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        state.previewActions.forEach { action ->
                            Row {
                                Icon(Icons.Outlined.EventAvailable, contentDescription = null, tint = BrandBlue)
                                Spacer(Modifier.width(8.dp))
                                Text(action, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            items(state.draftCards, key = { it.id }) { card ->
                DraftEditor(
                    card = card,
                    onChange = onUpdateDraft,
                    onRemove = { onRemoveDraft(card.id) },
                )
            }

            item {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("确认创建提醒与行动卡")
                }
            }
        }

        item {
            Spacer(Modifier.height(92.dp))
        }
    }
}

@Composable
private fun DraftEditor(
    card: ActionCard,
    onChange: (ActionCard) -> Unit,
    onRemove: () -> Unit,
) {
    val visual = visualForCardType(card.cardType)
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.97f)),
        border = BorderStroke(1.dp, Line),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeutralPill(text = visual.label, selected = true)
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
            OutlinedTextField(
                value = card.deadline ?: card.startTime ?: "",
                onValueChange = {
                    onChange(
                        if (card.cardType == "event") card.copy(startTime = it.ifBlank { null })
                        else card.copy(deadline = it.ifBlank { null })
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("时间") },
                shape = RoundedCornerShape(16.dp),
            )
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
                onValueChange = { onChange(card.copy(materials = splitList(it))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("提交物/准备物") },
                shape = RoundedCornerShape(16.dp),
            )
            OutlinedTextField(
                value = card.reminders.joinToString("，"),
                onValueChange = { onChange(card.copy(reminders = splitList(it))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("提醒策略") },
                shape = RoundedCornerShape(16.dp),
            )
            OutlinedTextField(
                value = card.needConfirm.joinToString("，"),
                onValueChange = { onChange(card.copy(needConfirm = splitList(it))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("待确认字段") },
                shape = RoundedCornerShape(16.dp),
            )
        }
    }
}

@Composable
private fun EmptyPreviewCard(onImport: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("暂无预览", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onImport, shape = RoundedCornerShape(16.dp)) {
                Text("导入截图")
            }
        }
    }
}

private fun splitList(value: String): List<String> {
    return value.split("，", ",", "、")
        .map { it.trim() }
        .filter { it.isNotBlank() }
}
