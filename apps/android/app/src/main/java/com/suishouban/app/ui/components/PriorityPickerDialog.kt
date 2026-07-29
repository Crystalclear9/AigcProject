package com.suishouban.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.Priority
import com.suishouban.app.data.model.PriorityModes
import com.suishouban.app.ui.theme.visualForPriority

@Composable
fun PriorityPickerDialog(
    card: ActionCard,
    onDismiss: () -> Unit,
    onChange: (ActionCard) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置优先级", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Priority.HIGH, Priority.NORMAL, Priority.LOW).forEach { priority ->
                    val visual = visualForPriority(priority)
                    val selected = card.priority == priority &&
                        card.priorityMode == PriorityModes.MANUAL
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onChange(
                                    card.copy(
                                        priority = priority,
                                        priorityMode = PriorityModes.MANUAL,
                                        priorityLocked = true,
                                        priorityReason = "由你手动设置",
                                    )
                                )
                            },
                        color = visual.container,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            1.dp,
                            visual.accent.copy(alpha = if (selected) 0.8f else 0.28f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    visual.label,
                                    color = visual.content,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    when (priority) {
                                        Priority.HIGH -> "临近截止、阻塞他人或影响较大"
                                        Priority.LOW -> "可以稍后处理，不阻塞当前计划"
                                        else -> "按常规节奏推进"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = visual.content.copy(alpha = 0.76f),
                                )
                            }
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = "手动锁定",
                                tint = visual.content,
                            )
                        }
                    }
                }
                if (card.priorityMode == PriorityModes.MANUAL || card.priorityLocked) {
                    TextButton(
                        onClick = {
                            onChange(
                                card.copy(
                                    priorityMode = PriorityModes.ADAPTIVE,
                                    priorityLocked = false,
                                    priorityReason = "等待根据截止时间和任务变化自动调整",
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        Text("恢复自动调整", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        shape = RoundedCornerShape(24.dp),
    )
}
