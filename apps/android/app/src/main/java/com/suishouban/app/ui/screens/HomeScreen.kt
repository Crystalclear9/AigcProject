package com.suishouban.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suishouban.app.AppUiState
import com.suishouban.app.data.model.CardStatus
import com.suishouban.app.data.model.Priority
import com.suishouban.app.data.model.primaryTime
import com.suishouban.app.ui.components.ActionCardItem
import com.suishouban.app.ui.components.HomeMofei
import com.suishouban.app.ui.components.HomeMofeiVariant
import com.suishouban.app.ui.components.SectionHeader
import com.suishouban.app.ui.components.brandGradient
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.EventBlue
import com.suishouban.app.ui.theme.Line
import com.suishouban.app.ui.theme.PromiseOrange

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
    val reduceMotion = state.settings.reduceMascotMotion

    LazyColumn(
        modifier = Modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            HomeHeroCard(
                reduceMotion = reduceMotion,
                onImport = { showImportOptions = true },
            )
        }

        item {
            ImpactDashboard(
                activeCards = activeCards.size,
                needConfirm = needConfirm,
                reminders = reminders,
                timedCards = timedCards,
                engine = displayEngineLabel(state.engine, state.settings.preferCloudModel),
                workflowStatus = state.workflowStatus,
                reduceMotion = reduceMotion,
            )
        }

        item {
            SectionHeader("今日关注", if (activeCards.isEmpty()) "暂无事项" else "${activeCards.size} 项")
        }

        if (activeCards.isEmpty()) {
            item {
                EmptyHomeCard(
                    reduceMotion = reduceMotion,
                    onImport = { showImportOptions = true },
                )
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
private fun HomeHeroCard(
    reduceMotion: Boolean,
    onImport: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(30.dp), ambientColor = BrandBlue.copy(alpha = 0.18f))
            .clip(RoundedCornerShape(30.dp))
            .background(brandGradient()),
    ) {
        val compact = maxWidth < 350.dp
        val mascotSize = if (compact) 144.dp else 176.dp
        val textWidthFraction = if (compact) 0.76f else 0.68f

        // Oversized translucent circles create depth without turning the card into a poster image.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 52.dp, y = (-54).dp)
                .size(210.dp)
                .background(Color.White.copy(alpha = 0.08f), CircleShape),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 72.dp, y = 88.dp)
                .size(184.dp)
                .background(Color(0xFF85D9FF).copy(alpha = 0.12f), CircleShape),
        )

        HomeMofei(
            variant = HomeMofeiVariant.HERO,
            reduceMotion = reduceMotion,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 28.dp, y = if (compact) 58.dp else 48.dp)
                .size(mascotSize),
        )

        Column(
            modifier = Modifier.padding(horizontal = if (compact) 18.dp else 22.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(textWidthFraction),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "随手办",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    "先建议，后确认，再提醒",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.94f),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "截图只会生成候选事项；你确认后才保存卡片、安排提醒或同步日历。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.82f),
                )
            }

            Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
            WorkflowSteps()

            Button(
                onClick = onImport,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.heightIn(min = 50.dp),
            ) {
                Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = BrandBlue)
                Spacer(Modifier.width(8.dp))
                Text("导入截图", color = BrandBlue, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun WorkflowSteps() {
    val steps = listOf("识别", "候选", "确认", "提醒")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { index, label ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxWidth(0.5f)
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.28f)),
                    )
                }
                if (index < steps.lastIndex) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxWidth(0.5f)
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.28f)),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = (index + 1).toString(),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        label,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
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
    reduceMotion: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(26.dp), ambientColor = Color(0xFF4265A8).copy(alpha = 0.12f)),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.97f)),
        border = BorderStroke(1.dp, Line.copy(alpha = 0.8f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Insights, contentDescription = null, tint = BrandBlue)
                Spacer(Modifier.width(8.dp))
                Text(
                    "行动状态",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                HomeMofei(
                    variant = HomeMofeiVariant.STATUS,
                    reduceMotion = reduceMotion,
                    decorative = true,
                    modifier = Modifier
                        .offset(x = 8.dp, y = (-4).dp)
                        .size(76.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusMetric("进行中", activeCards.toString(), BrandBlue, Modifier.weight(1f))
                StatusMetric("待确认", needConfirm.toString(), PromiseOrange, Modifier.weight(1f))
                StatusMetric("有时间", timedCards.toString(), EventBlue, Modifier.weight(1f))
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
        normalized.contains("lanxin") || normalized.contains("model") ||
            normalized.contains("expert") || preferCloud -> "AI 增强"
        normalized.contains("ocr") || normalized.contains("rules") ||
            normalized.contains("mlkit") -> "手机端识别"
        engine.isNotBlank() -> "智能识别"
        else -> "手机端识别"
    }
}

@Composable
private fun StatusMetric(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = 88.dp)
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 13.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                color = color,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun EmptyHomeCard(
    reduceMotion: Boolean,
    onImport: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Line),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compact = maxWidth < 350.dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 20.dp, end = 12.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "从第一张截图开始",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "导入截图或粘贴通知文字，先生成候选卡，确认后才会保存、提醒或同步日历。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onImport,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                    ) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("导入截图", fontWeight = FontWeight.Bold)
                    }
                }

                Box(
                    modifier = Modifier
                        .width(if (compact) 112.dp else 136.dp)
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Native Compose cards stay sharp and can move independently from the raster mascot.
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(x = 16.dp, y = (-8).dp)
                            .size(width = 78.dp, height = 104.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFFEAF2FF)),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(x = 28.dp, y = 2.dp)
                            .size(width = 72.dp, height = 96.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(Color(0xFFD9E7FF).copy(alpha = 0.7f)),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = 10.dp)
                            .size(width = 94.dp, height = 26.dp)
                            .background(
                                color = Color(0xFF6FA7FF).copy(alpha = 0.14f),
                                shape = CircleShape,
                            ),
                    )
                    HomeMofei(
                        variant = HomeMofeiVariant.EMPTY,
                        reduceMotion = reduceMotion,
                        decorative = true,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(x = (-8).dp, y = 4.dp)
                            .size(if (compact) 124.dp else 148.dp),
                    )
                }
            }
        }
    }
}
