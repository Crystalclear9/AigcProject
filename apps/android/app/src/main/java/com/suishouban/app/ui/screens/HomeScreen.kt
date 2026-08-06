package com.suishouban.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suishouban.app.AppUiState
import com.suishouban.app.data.model.CardStatus
import com.suishouban.app.data.model.Priority
import com.suishouban.app.data.model.WorkspaceTypes
import com.suishouban.app.data.model.primaryTime
import com.suishouban.app.domain.team.TeamWorkspacePolicy
import com.suishouban.app.ui.components.ActionCardItem
import com.suishouban.app.ui.components.HomeMofei
import com.suishouban.app.ui.components.HomeMofeiVariant
import com.suishouban.app.ui.components.SectionHeader
import com.suishouban.app.ui.components.brandGradient
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.EventBlue
import com.suishouban.app.ui.theme.Ink
import com.suishouban.app.ui.theme.Line
import com.suishouban.app.ui.theme.MistBlue
import com.suishouban.app.ui.theme.MofeiFocusCyan
import com.suishouban.app.ui.theme.Muted
import com.suishouban.app.ui.theme.PromiseOrange

@Composable
fun HomeScreen(
    state: AppUiState,
    onImportFromGallery: () -> Unit,
    onImportFromCamera: () -> Unit,
    onCards: () -> Unit,
    onComplete: (String) -> Unit,
    teamNames: Map<String, String> = emptyMap(),
) {
    var showImportOptions by rememberSaveable { mutableStateOf(false) }
    // 今日 keeps personal cards plus team cards assigned to ME; teammates' and unassigned team
    // tasks stay in team detail and the 卡片页 团队 filter.
    val activeCards = state.cards.filter {
        it.status != CardStatus.ARCHIVED && it.status != CardStatus.DONE &&
            TeamWorkspacePolicy.includeInTodayFocus(it.workspaceType, it.assigneeId, state.settings.localUserId)
    }
    val urgentCards = activeCards.filter { it.priority == Priority.HIGH }.take(3)
    val needConfirm = activeCards.count { it.needConfirm.isNotEmpty() }
    val reminders = activeCards.sumOf { it.reminders.size }
    val timedCards = activeCards.count { it.primaryTime() != null }
    val reduceMotion = state.settings.reduceMascotMotion

    LazyColumn(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            HomeHeroCard(
                reduceMotion = reduceMotion,
                onImport = { showImportOptions = true },
            )
        }

        item {
            WorkflowStripCard()
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
                    teamBadge = if (card.workspaceType == WorkspaceTypes.TEAM) {
                        teamNames[card.workspaceId]?.firstOrNull()?.toString() ?: "团"
                    } else {
                        null
                    },
                )
            }
            item {
                Button(
                    onClick = onCards,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                ) {
                    Icon(Icons.Outlined.Checklist, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("查看全部行动卡", fontWeight = FontWeight.SemiBold)
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
            .shadow(18.dp, RoundedCornerShape(28.dp), ambientColor = BrandBlue.copy(alpha = 0.38f), spotColor = BrandBlue.copy(alpha = 0.34f))
            .clip(RoundedCornerShape(28.dp))
            .background(brandGradient()),
    ) {
        val compact = maxWidth < 350.dp
        val mascotSize = if (compact) 172.dp else 208.dp
        // The mascot is anchored to the bottom-right and allowed to bleed off the edge, so it
        // reads as a character standing ON the card rather than a cutout dropped onto it.
        val mascotBottomInset = if (compact) (-6).dp else (-10).dp
        val contentWidthFraction = if (compact) 0.66f else 0.60f

        // Overhead spotlight cone: a soft white bloom high on the right that fades downward,
        // simulating a light source the mascot's glossy highlights already agree with.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 40.dp, y = (-70).dp)
                .size(260.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                    ),
                    CircleShape,
                ),
        )

        // Elliptical floor shadow under the mascot's feet — the single most important cue that
        // grounds the sprite in the scene instead of floating.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-26).dp, y = (-14).dp)
                .width(mascotSize * 0.62f)
                .height(20.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF0A2A6B).copy(alpha = 0.38f), Color.Transparent),
                    ),
                    CircleShape,
                ),
        )

        // Backlight halo hugging the mascot so its edges melt into light.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 2.dp, y = mascotBottomInset)
                .size(mascotSize + 40.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MofeiFocusCyan.copy(alpha = 0.34f),
                            MofeiFocusCyan.copy(alpha = 0.12f),
                            Color.Transparent,
                        ),
                    ),
                    CircleShape,
                ),
        )

        HomeMofei(
            variant = HomeMofeiVariant.HERO,
            reduceMotion = reduceMotion,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 12.dp, y = mascotBottomInset)
                .size(mascotSize),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth(contentWidthFraction)
                .padding(horizontal = if (compact) 20.dp else 24.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Eyebrow pill: small branded chip that gives the headline a premium anchor.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White.copy(alpha = 0.16f))
                    .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(percent = 50))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "截图变待办",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                "随手办",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                "先建议，后确认，再提醒。",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.92f),
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(Modifier.height(2.dp))
            Button(
                onClick = onImport,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                shape = RoundedCornerShape(14.dp),
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
private fun WorkflowStripCard() {
    val steps = listOf("识别", "候选", "确认", "提醒")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(28.dp), ambientColor = BrandBlue.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Line.copy(alpha = 0.7f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            steps.forEachIndexed { index, label ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    // Connector hairlines live at node-center height so the row reads as one
                    // continuous pipeline rather than four detached chips.
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(y = 15.dp)
                                .fillMaxWidth(0.5f)
                                .height(2.dp)
                                .background(BrandBlue.copy(alpha = 0.18f)),
                        )
                    }
                    if (index < steps.lastIndex) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(y = 15.dp)
                                .fillMaxWidth(0.5f)
                                .height(2.dp)
                                .background(BrandBlue.copy(alpha = 0.18f)),
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .background(brandGradient(), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = (index + 1).toString(),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(
                            label,
                            color = Ink,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
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
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "impact-chevron",
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(28.dp), ambientColor = Color(0xFF4265A8).copy(alpha = 0.10f)),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Line.copy(alpha = 0.7f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Whole header row toggles the card. A rotating chevron signals collapsed vs expanded,
            // and a compact summary chip stays visible when collapsed so the card still informs.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MistBlue),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Insights, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "行动状态",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (expanded) "共 $activeCards 项" else "进行中 $activeCards · 待确认 $needConfirm",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起行动状态" else "展开行动状态",
                    tint = Muted,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = chevronRotation },
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusMetric("进行中", activeCards.toString(), BrandBlue, Icons.Outlined.Bolt, active = false, modifier = Modifier.weight(1f))
                        StatusMetric("待确认", needConfirm.toString(), PromiseOrange, Icons.Outlined.PendingActions, active = needConfirm > 0, modifier = Modifier.weight(1f))
                        StatusMetric("有时间", timedCards.toString(), EventBlue, Icons.Outlined.Schedule, active = false, modifier = Modifier.weight(1f))
                    }
                    // Meta line: grouped by a hairline instead of two stacked gray sentences.
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = 0.7f)))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MetaLabel("识别方式", engine)
                        MetaLabel("已配置提醒", "$reminders")
                        MetaLabel("最近处理", if (workflowStatus.isNotBlank()) workflowStatusLabel(workflowStatus) else "暂无")
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaLabel(caption: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(caption, style = MaterialTheme.typography.labelSmall, color = Muted)
        Text(value, style = MaterialTheme.typography.labelLarge, color = Ink, fontWeight = FontWeight.SemiBold)
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
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    // One neutral surface for all three tiles (shape + fill locked); the accent only appears on
    // the icon chip and — when the metric actually needs attention — a hairline top accent.
    Box(
        modifier = modifier
            .heightIn(min = 104.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFF4F7FD))
            .then(
                if (active) Modifier.border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                else Modifier,
            )
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Ink,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun EmptyHomeCard(
    reduceMotion: Boolean,
    onImport: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(28.dp), ambientColor = BrandBlue.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Line.copy(alpha = 0.7f)),
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
                    // Soft bloom anchors the mascot + placeholder cards on a shared pool of light
                    // so the group reads as one lit vignette instead of stacked cutouts.
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = 6.dp)
                            .size(150.dp)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        MistBlue,
                                        MistBlue.copy(alpha = 0.4f),
                                        Color.Transparent,
                                    ),
                                ),
                                CircleShape,
                            ),
                    )
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
