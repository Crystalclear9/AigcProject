package com.suishouban.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suishouban.app.AppUiState
import com.suishouban.app.TeamSummary
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.effectiveReminderNodes
import com.suishouban.app.data.model.mergeReminderLabels
import com.suishouban.app.domain.team.TeamMemberOption
import com.suishouban.app.domain.team.TeamWorkspacePolicy
import com.suishouban.app.ui.components.NeutralPill
import com.suishouban.app.ui.components.DateTimeWheelPickerDialog
import com.suishouban.app.ui.components.ReminderNodesEditor
import com.suishouban.app.ui.components.PriorityPickerDialog
import com.suishouban.app.ui.components.SectionHeader
import com.suishouban.app.ui.components.WorkflowStrip
import com.suishouban.app.ui.theme.AccentIconChip
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.DS
import com.suishouban.app.ui.theme.Ink
import com.suishouban.app.ui.theme.Line
import com.suishouban.app.ui.theme.MistBlue
import com.suishouban.app.ui.theme.Muted
import com.suishouban.app.ui.theme.SoftCard
import com.suishouban.app.ui.theme.visualForCardType
import com.suishouban.app.ui.theme.visualForPriority
import java.time.OffsetDateTime

@Composable
fun PreviewScreen(
    state: AppUiState,
    onUpdateDraft: (ActionCard) -> Unit,
    onRemoveDraft: (String) -> Unit,
    onToggleDraftSelection: (String) -> Unit,
    onSelectAllDrafts: () -> Unit,
    onConfirm: () -> Unit,
    onManualAdd: () -> Unit,
    onImport: () -> Unit,
    onAddMaterials: () -> Unit,
    teams: List<TeamSummary> = emptyList(),
    teamMembers: List<TeamMemberOption> = emptyList(),
    onSelectWorkspace: (String?) -> Unit = {},
) {
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    val localDraftValid = state.draftCards.all {
        it.title.isNotBlank() && (it.cardType != "promise" || it.deadline != null || it.startTime != null)
    }
    val selectedTeamMembers = state.draftTeamId
        ?.let { teamId -> teamMembers.filter { it.teamId == teamId } }
        .orEmpty()
    LazyColumn(
        modifier = Modifier.padding(horizontal = DS.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(DS.SectionGap),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            SectionHeader("发现可能行动事项", if (state.engine.isBlank()) null else state.engine, icon = Icons.Outlined.FactCheck)
        }

        item {
            WorkflowStrip(currentStep = 2, modifier = Modifier.fillMaxWidth())
        }

        if (state.loading && state.draftCards.isEmpty()) {
            item {
                LoadingPreviewCard()
            }
        } else if (state.draftCards.isEmpty()) {
            item {
                EmptyPreviewCard(
                    awaitingOcrReview = state.workflowStatus == "awaiting_ocr_review",
                    onImport = onImport,
                    onManualAdd = onManualAdd,
                )
            }
        } else {
            item {
                TextButton(onClick = onAddMaterials, enabled = !state.loading) {
                    Text("添加材料并继续完善")
                }
            }
            item {
                SoftCard {
                    Column(Modifier.padding(DS.CardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AccentIconChip(icon = Icons.Outlined.FactCheck, accent = BrandBlue, size = 30.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("确认前检查", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.workflowStatus.isNotBlank()) {
                                NeutralPill(
                                    text = when (state.workflowStatus) {
                                        "queued", "running" -> "正在校验"
                                        "awaiting_review" -> "等待人工确认"
                                        "awaiting_client_ocr" -> "等待本地 OCR"
                                        "completed" -> "工作流完成"
                                        else -> state.workflowStatus
                                    }
                                )
                            }
                            if (state.resultStage.isNotBlank()) {
                                NeutralPill(text = "可信度 ${(state.overallConfidence * 100).toInt()}%")
                            }
                            if (state.riskLevel != "low") {
                                NeutralPill(text = if (state.riskLevel == "high") "高风险" else "需留意")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (state.modelEnhancementStatus) {
                                "succeeded" -> NeutralPill(text = "\u4e91\u7aef\u6a21\u578b\u5df2\u53c2\u4e0e", selected = true)
                                "degraded" -> NeutralPill(text = "\u4e91\u7aef\u589e\u5f3a\u5df2\u964d\u7ea7")
                                "attempted" -> NeutralPill(text = "\u7b49\u5f85\u4e91\u7aef\u589e\u5f3a")
                            }
                            when (state.ocrEnhancementStatus) {
                                "succeeded" -> NeutralPill(text = "vivo OCR \u5df2\u53c2\u4e0e", selected = true)
                                "degraded" -> NeutralPill(text = "vivo OCR \u5df2\u964d\u7ea7")
                            }
                        }
                        val reviewItems = (
                            state.validationErrors +
                                state.fieldConflicts.mapNotNull { it["field"]?.toString() } +
                                state.draftCards.flatMap { it.needConfirm }
                            ).distinct()
                        if (state.screenshotPromptSummary != null || state.screenshotPrimaryEvidence.isNotEmpty()) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = BrandBlue.copy(alpha = 0.08f)
                                ),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    state.screenshotPromptSummary?.let {
                                        Text(it, fontWeight = FontWeight.Bold)
                                    }
                                    state.screenshotScenarioType?.let {
                                        Text(
                                            "识别场景：${scenarioLabel(it)}" +
                                                (state.screenshotConfidenceBand?.let { band -> " · ${confidenceLabel(band)}" } ?: ""),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    state.screenshotPrimaryEvidence.take(3).forEach { evidence ->
                                        Text(
                                            "• $evidence",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        if (reviewItems.isNotEmpty()) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                                ),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text("请重点确认", fontWeight = FontWeight.Bold)
                                    reviewItems.take(6).forEach { Text("• $it") }
                                }
                            }
                        }
                        if (state.fallbackReason != null || state.warnings.isNotEmpty()) {
                            Text(
                                (state.warnings + listOfNotNull(state.fallbackReason)).distinct().joinToString("；"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.previewActions.forEach { action ->
                            Row {
                                Icon(Icons.Outlined.EventAvailable, contentDescription = null, tint = BrandBlue)
                                Spacer(Modifier.width(8.dp))
                                Text(action, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                            Text(if (showDiagnostics) "收起诊断信息" else "查看诊断信息")
                        }
                        if (showDiagnostics) {
                            Text(
                                buildString {
                                    append("运行 ${state.traceId.take(8)} · ${state.route}")
                                    if (state.activeAgents.isNotEmpty()) append(" · ${state.activeAgents.size} 个任务")
                                    append(" · model=${state.modelEnhancementStatus}")
                                    append(" · ocr=${state.ocrEnhancementStatus}")
                                    state.timeToFirstDraftMs?.let { append(" · 首稿 ${it.toInt()} ms") }
                                    if (state.nodeTrace.isNotEmpty()) {
                                        append("\n")
                                        append(state.nodeTrace.joinToString(" → ") { it.node.replace("_", " ") })
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (teams.isNotEmpty()) {
                item {
                    WorkspaceChipRow(
                        teams = teams,
                        selectedTeamId = state.draftTeamId,
                        suggested = state.draftTeamSuggested,
                        onSelect = onSelectWorkspace,
                    )
                }
            }

            items(state.draftCards, key = { it.id }) { card ->
                DraftEditor(
                    card = card,
                    selected = card.id in state.selectedDraftIds,
                    assigneeLabel = if (state.draftTeamId != null) {
                        TeamWorkspacePolicy.matchAssignee(card.assigneeId, selectedTeamMembers)?.nickname
                            ?: card.assigneeId?.trim()?.takeIf(String::isNotBlank)
                    } else {
                        null
                    },
                    onSelectedChange = { onToggleDraftSelection(card.id) },
                    onChange = onUpdateDraft,
                    onRemove = { onRemoveDraft(card.id) },
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "已选择 ${state.selectedDraftIds.size}/${state.draftCards.size} 张",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(onClick = onSelectAllDrafts) {
                        Text("全选")
                    }
                }
                state.teamPushError?.let { pushError ->
                    Text(
                        pushError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Button(
                    onClick = onConfirm,
                    enabled = localDraftValid &&
                        state.selectedDraftIds.isNotEmpty() &&
                        !state.loading &&
                        state.workflowStatus !in setOf("queued", "running", "awaiting_ocr_review"),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(DS.RadiusButton),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                ) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            state.loading || state.workflowStatus in setOf("queued", "running") -> "等待分析完成"
                            state.workflowStatus == "awaiting_ocr_review" -> "先返回修正识别文字"
                            !localDraftValid -> "补全关键信息后继续"
                            else -> "确认并创建行动卡"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(92.dp))
        }
    }
}

private fun scenarioLabel(value: String): String = when (value) {
    "course_notice" -> "课程/作业通知"
    "chat_promise" -> "聊天承诺"
    "registration" -> "报名/提交"
    "meeting" -> "会议/汇报"
    "noise" -> "干扰内容"
    "own_app" -> "随手办界面"
    else -> "待确认"
}

private fun confidenceLabel(value: String): String = when (value) {
    "high" -> "高可信"
    "medium" -> "中可信"
    else -> "低可信"
}

/**
 * One quiet batch-level control: 归属 defaults to 个人 and can flow the whole batch into a team.
 * A tiny Muted "AI 建议" suffix marks an automatic preselection; any tap replaces it.
 */
@Composable
private fun WorkspaceChipRow(
    teams: List<TeamSummary>,
    selectedTeamId: String?,
    suggested: Boolean,
    onSelect: (String?) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val selectedLabel = teams.firstOrNull { it.id == selectedTeamId }?.name ?: "个人"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("归属", style = MaterialTheme.typography.labelMedium, color = Muted)
        Spacer(Modifier.width(8.dp))
        Box {
            NeutralPill(
                text = "$selectedLabel ▾",
                selected = selectedTeamId != null,
                onClick = { menuOpen = true },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("个人") },
                    onClick = {
                        menuOpen = false
                        onSelect(null)
                    },
                )
                teams.forEach { team ->
                    DropdownMenuItem(
                        text = { Text(team.name) },
                        onClick = {
                            menuOpen = false
                            onSelect(team.id)
                        },
                    )
                }
            }
        }
        if (suggested && selectedTeamId != null) {
            Spacer(Modifier.width(6.dp))
            Text("AI 建议", style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}

@Composable
private fun DraftEditor(
    card: ActionCard,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onChange: (ActionCard) -> Unit,
    onRemove: () -> Unit,
    assigneeLabel: String? = null,
) {
    val visual = visualForCardType(card.cardType)
    val priorityVisual = visualForPriority(card.priority)
    var pickerField by rememberSaveable(card.id) { mutableStateOf<String?>(null) }
    var showPriorityPicker by rememberSaveable(card.id) { mutableStateOf(false) }
    var timeError by rememberSaveable(card.id) { mutableStateOf<String?>(null) }
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = priorityVisual.container),
        border = BorderStroke(1.dp, priorityVisual.accent.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = onSelectedChange,
                )
                NeutralPill(text = visual.label, selected = true)
                NeutralPill(
                    text = priorityVisual.label,
                    selected = true,
                    onClick = { showPriorityPicker = true },
                )
                if (card.needConfirm.isNotEmpty()) {
                    NeutralPill(text = "待确认 ${card.needConfirm.size}")
                }
                assigneeLabel?.let { label ->
                    // Quiet member chip, same visual language as the team detail rows.
                    Text(
                        text = label,
                        color = BrandBlue,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(DS.RadiusChipBadge))
                            .background(MistBlue)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
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
            if (card.cardType == "event") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimeField(
                        value = card.startTime,
                        label = "开始时间",
                        onClick = { pickerField = "start" },
                        modifier = Modifier.weight(1f),
                    )
                    TimeField(
                        value = card.endTime,
                        label = "结束时间",
                        onClick = { pickerField = "end" },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                TimeField(
                    value = card.deadline,
                    label = if (card.deadline.isNullOrBlank()) "截止时间 · 待确认" else "截止时间",
                    onClick = { pickerField = "deadline" },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            timeError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
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
                onValueChange = { onChange(card.copy(materials = splitList(it))) },
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
                modifier = Modifier.fillMaxWidth(),
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
                timeError = null
                pickerField = null
            },
            onConfirm = { value ->
                val invalidEnd = field == "end" &&
                    card.startTime?.let { start ->
                        runCatching {
                            OffsetDateTime.parse(value) <= OffsetDateTime.parse(start)
                        }.getOrDefault(false)
                    } == true
                if (invalidEnd) {
                    timeError = "结束时间必须晚于开始时间"
                } else {
                    onChange(
                        when (field) {
                            "start" -> card.copy(startTime = value)
                            "end" -> card.copy(endTime = value)
                            else -> card.copy(deadline = value)
                        }
                    )
                    timeError = null
                    pickerField = null
                }
            },
        )
    }
    if (showPriorityPicker) {
        PriorityPickerDialog(
            card = card,
            onDismiss = { showPriorityPicker = false },
            onChange = {
                onChange(it)
                showPriorityPicker = false
            },
        )
    }
}

@Composable
private fun TimeField(
    value: String?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value.orEmpty(),
        onValueChange = {},
        readOnly = true,
        trailingIcon = {
            IconButton(onClick = onClick) {
                Icon(Icons.Outlined.Schedule, contentDescription = label)
            }
        },
        modifier = modifier.clickable(onClick = onClick),
        label = { Text(label) },
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
private fun LoadingPreviewCard() {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Line),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.width(28.dp), strokeWidth = 3.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("正在识别行动事项", style = MaterialTheme.typography.titleMedium)
                Text("正在检查文字质量、任务边界和时间信息。", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun EmptyPreviewCard(
    awaitingOcrReview: Boolean,
    onImport: () -> Unit,
    onManualAdd: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (awaitingOcrReview) "识别文字需要复核" else "暂无预览", style = MaterialTheme.typography.titleLarge)
            Text(
                if (awaitingOcrReview) {
                    "当前文字存在乱码、时间冲突或任务边界不清。返回导入页可修改识别文字后继续。"
                } else {
                    "没有识别到稳定行动事项。可以重新导入截图，也可以先手动创建一张候选卡再补全字段。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onImport, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                    Text(if (awaitingOcrReview) "修正识别文字" else "重新导入")
                }
                TextButton(onClick = onManualAdd, modifier = Modifier.weight(1f)) {
                    Text("手动添加")
                }
            }
        }
    }
}

private fun splitList(value: String): List<String> {
    return value.split("，", ",", "、")
        .map { it.trim() }
        .filter { it.isNotBlank() }
}
