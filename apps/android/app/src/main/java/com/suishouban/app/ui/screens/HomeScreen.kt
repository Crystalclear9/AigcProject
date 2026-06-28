package com.suishouban.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.suishouban.app.ui.theme.PromiseOrange
import com.suishouban.app.ui.theme.TaskRed

@Composable
fun HomeScreen(
    state: AppUiState,
    onImportFromGallery: () -> Unit,
    onImportFromCamera: () -> Unit,
    onCards: () -> Unit,
    onComplete: (String) -> Unit,
) {
    var showImportOptions by rememberSaveable { mutableStateOf(false) }
    val activeCards = state.cards.filter { it.status != CardStatus.ARCHIVED && it.status != CardStatus.DONE }
    val urgentCards = activeCards.filter { it.priority == Priority.HIGH }.take(3)
    val needConfirm = activeCards.count { it.needConfirm.isNotEmpty() }
    val reminders = activeCards.sumOf { it.reminders.size }
    val timedCards = activeCards.count { it.primaryTime() != null }

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
                        onClick = { showImportOptions = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = BrandBlue)
                        Spacer(Modifier.width(8.dp))
                        Text("导入截图", color = BrandBlue, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            ImpactDashboard(
                activeCards = activeCards.size,
                needConfirm = needConfirm,
                reminders = reminders,
                timedCards = timedCards,
                engine = displayEngineLabel(state.engine, state.settings.preferCloudModel),
                workflowStatus = state.workflowStatus,
            )
        }

        item {
            SectionHeader("今日关注", if (activeCards.isEmpty()) "暂无事项" else "${activeCards.size} 项")
        }

        if (activeCards.isEmpty()) {
            item {
                EmptyHomeCard(onImport = { showImportOptions = true })
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
            Spacer(Modifier.height(92.dp))
        }
    }

    if (showImportOptions) {
        ImportSourceDialog(
            onDismiss = { showImportOptions = false },
            onGallery = {
                showImportOptions = false
                onImportFromGallery()
            },
            onCamera = {
                showImportOptions = false
                onImportFromCamera()
            },
        )
    }
}

@Composable
private fun ImportSourceDialog(
    onDismiss: () -> Unit,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选择截图来源", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭")
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 入口保持在今日页内，只把来源选择交给系统相册或相机。
                Button(
                    onClick = onGallery,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Outlined.ImageSearch, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("从相册选择")
                }
                OutlinedButton(
                    onClick = onCamera,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("打开相机拍摄")
                }
            }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = Color.White,
    )
}

@Composable
private fun ImpactDashboard(
    activeCards: Int,
    needConfirm: Int,
    reminders: Int,
    timedCards: Int,
    engine: String,
    workflowStatus: String,
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
                Text("行动状态", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FlatMetric("进行中", activeCards.toString(), BrandBlue, Modifier.weight(1f))
                FlatMetric("待确认", needConfirm.toString(), PromiseOrange, Modifier.weight(1f))
                FlatMetric("有时间", timedCards.toString(), EventBlue, Modifier.weight(1f))
            }
            if (workflowStatus.isNotBlank()) {
                Text(
                    "最近处理：${workflowStatusLabel(workflowStatus)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "识别方式：$engine · 已配置提醒 $reminders",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun workflowStatusLabel(status: String): String = when (status) {
    "queued", "running" -> "正在分析"
    "awaiting_client_ocr" -> "等待文字识别"
    "awaiting_review" -> "等待确认"
    "completed" -> "已确认"
    "failed" -> "处理失败"
    "cancelled" -> "已取消"
    else -> status
}

private fun displayEngineLabel(engine: String, preferCloud: Boolean): String {
    val normalized = engine.lowercase()
    return when {
        normalized.contains("lanxin") || normalized.contains("model") || normalized.contains("expert") || preferCloud -> "AI 增强"
        normalized.contains("ocr") || normalized.contains("rules") || normalized.contains("mlkit") -> "手机端识别"
        engine.isNotBlank() -> "智能识别"
        else -> "手机端识别"
    }
}

@Composable
private fun FlatMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                "导入截图或粘贴通知文字，先生成候选卡，确认后才会保存、提醒或同步日历。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onImport, shape = RoundedCornerShape(16.dp)) {
                Text("导入截图")
            }
        }
    }
}
