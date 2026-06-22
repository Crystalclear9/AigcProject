package com.suishouban.app

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.domain.screenshot.ScreenshotWorkflowStage
import com.suishouban.app.reminder.ScreenshotMonitorService
import com.suishouban.app.ui.components.DraftEditor
import com.suishouban.app.ui.components.PreviewActionsCard
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.Line
import com.suishouban.app.ui.theme.SuiShouBanTheme
import androidx.compose.ui.graphics.Color as ComposeColor

class ScreenshotPreviewActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        configureFloatingWindow()
        viewModel.beginFreshScreenshotPrompt()

        val recoveredIntent = ScreenshotMonitorService.consumePendingPreviewIntent(this)
        val sourceIntent = recoveredIntent ?: intent
        val screenshotUri = sourceIntent.data
        val ocrText = sourceIntent.getStringExtra(EXTRA_OCR_TEXT)
            ?: sourceIntent.getStringExtra(EXTRA_OCR_TEXT_BASE64)?.let(::decodeUtf8Base64)
        val gateReason = sourceIntent.getStringExtra(EXTRA_GATE_REASON)
        val deadlineHint = sourceIntent.getStringExtra(EXTRA_DEADLINE_HINT)
        val promptSummary = sourceIntent.getStringExtra(EXTRA_PROMPT_SUMMARY)
        val confidenceBand = sourceIntent.getStringExtra(EXTRA_CONFIDENCE_BAND)
        val scenarioType = sourceIntent.getStringExtra(EXTRA_SCENARIO_TYPE)
        val primaryEvidence = sourceIntent.getStringArrayListExtra(EXTRA_PRIMARY_EVIDENCE).orEmpty()
        sourceIntent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
            .takeIf { it != 0 }
            ?.let { NotificationManagerCompat.from(this).cancel(it) }
        if (recoveredIntent == null) {
            ScreenshotMonitorService.clearPendingPreview(this)
        }

        setContent {
            SuiShouBanTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                LaunchedEffect(screenshotUri, ocrText) {
                    if (screenshotUri == null) {
                        finish()
                        return@LaunchedEffect
                    }
                    if (!ocrText.isNullOrBlank()) {
                        viewModel.prepareScreenshotPrompt(
                            ocrText = ocrText,
                            gateReason = gateReason,
                            deadlineHint = deadlineHint,
                            promptSummary = promptSummary,
                            confidenceBand = confidenceBand,
                            scenarioType = scenarioType,
                            primaryEvidence = primaryEvidence,
                        )
                    } else {
                        viewModel.analyzeImage(screenshotUri, notifyWhenEmpty = true)
                    }
                }

                ScreenshotFloatingPanel(
                    state = state,
                    onStartAnalysis = {
                        viewModel.analyzeScreenshotPrompt(
                            screenshotUri = screenshotUri,
                            ocrText = state.ocrText,
                            gateReason = state.screenshotGateReason,
                            deadlineHint = state.screenshotDeadlineHint,
                            promptSummary = state.screenshotPromptSummary,
                            confidenceBand = state.screenshotConfidenceBand,
                            scenarioType = state.screenshotScenarioType,
                            primaryEvidence = state.screenshotPrimaryEvidence,
                        )
                    },
                    onUpdateDraft = viewModel::updateDraft,
                    onRemoveDraft = viewModel::removeDraft,
                    onToggleDraft = viewModel::toggleDraftSelection,
                    onSelectAll = viewModel::selectAllDrafts,
                    onRefineWithAi = viewModel::refineDraftWithAi,
                    onConfirm = { viewModel.confirmDrafts { finish() } },
                    onIgnore = { viewModel.ignoreScreenshotWorkflow { finish() } },
                )
            }
        }
    }

    private fun configureFloatingWindow() {
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.16f)
        window.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        val params = window.attributes
        params.width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.y = (20 * resources.displayMetrics.density).toInt()
        window.attributes = params
    }

    private fun decodeUtf8Base64(value: String): String? {
        return runCatching {
            val padded = value.padEnd(value.length + (4 - value.length % 4) % 4, '=')
            String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        }.recoverCatching {
            String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    companion object {
        const val EXTRA_OCR_TEXT = "com.suishouban.app.extra.OCR_TEXT"
        const val EXTRA_GATE_REASON = "com.suishouban.app.extra.GATE_REASON"
        const val EXTRA_DEADLINE_HINT = "com.suishouban.app.extra.DEADLINE_HINT"
        const val EXTRA_PROMPT_SUMMARY = "com.suishouban.app.extra.PROMPT_SUMMARY"
        const val EXTRA_CONFIDENCE_BAND = "com.suishouban.app.extra.CONFIDENCE_BAND"
        const val EXTRA_SCENARIO_TYPE = "com.suishouban.app.extra.SCENARIO_TYPE"
        const val EXTRA_PRIMARY_EVIDENCE = "com.suishouban.app.extra.PRIMARY_EVIDENCE"
        const val EXTRA_NOTIFICATION_ID = "com.suishouban.app.extra.NOTIFICATION_ID"
        const val EXTRA_OCR_TEXT_BASE64 = "com.suishouban.app.extra.OCR_TEXT_BASE64"
    }
}

@Composable
private fun ScreenshotFloatingPanel(
    state: AppUiState,
    onStartAnalysis: () -> Unit,
    onUpdateDraft: (ActionCard) -> Unit,
    onRemoveDraft: (String) -> Unit,
    onToggleDraft: (String) -> Unit,
    onSelectAll: () -> Unit,
    onRefineWithAi: (String) -> Unit,
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .heightIn(max = maxHeight),
        color = ComposeColor.White.copy(alpha = 0.97f),
        shape = RoundedCornerShape(30.dp),
        border = BorderStroke(1.dp, ComposeColor.White.copy(alpha = 0.72f)),
        tonalElevation = 0.dp,
        shadowElevation = 18.dp,
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(
                            ComposeColor(0xFFF7FAFF),
                            ComposeColor.White,
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PanelHeader(state = state, onIgnore = onIgnore)
            when {
                state.screenshotWorkflowStage == ScreenshotWorkflowStage.PROMPT_SHOWN && state.draftCards.isEmpty() && !state.loading ->
                    RequestPane(state = state, onStartAnalysis = onStartAnalysis, onIgnore = onIgnore)
                state.loading -> LoadingPane()
                state.draftCards.isEmpty() -> EmptyPane(state.error, onIgnore)
                else -> DraftPane(
                    state = state,
                    onUpdateDraft = onUpdateDraft,
                    onRemoveDraft = onRemoveDraft,
                    onToggleDraft = onToggleDraft,
                    onSelectAll = onSelectAll,
                    onRefineWithAi = onRefineWithAi,
                    onConfirm = onConfirm,
                )
            }
        }
    }
}

@Composable
private fun PanelHeader(state: AppUiState, onIgnore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(
                    Brush.linearGradient(listOf(BrandBlue, ComposeColor(0xFF7BA7FF))),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = ComposeColor.White)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("可能有待办", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = when {
                    state.draftCards.isNotEmpty() -> "识别到 ${state.draftCards.size} 个事项"
                    state.loading -> "正在生成候选行动卡"
                    else -> state.screenshotPromptSummary ?: "截图里可能包含行动事项"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onIgnore) {
            Icon(Icons.Outlined.Close, contentDescription = "忽略")
        }
    }
}

@Composable
private fun RequestPane(
    state: AppUiState,
    onStartAnalysis: () -> Unit,
    onIgnore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EvidenceSummary(state = state)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onIgnore,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("忽略")
            }
            Button(
                onClick = onStartAnalysis,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("生成草稿")
            }
        }
    }
}

@Composable
private fun LoadingPane() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text("正在拆解事项和校验时间", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EmptyPane(error: String?, onIgnore: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(error ?: "没有识别到明确行动事项", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onIgnore, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Text("关闭")
        }
    }
}

@Composable
private fun DraftPane(
    state: AppUiState,
    onUpdateDraft: (ActionCard) -> Unit,
    onRemoveDraft: (String) -> Unit,
    onToggleDraft: (String) -> Unit,
    onSelectAll: () -> Unit,
    onRefineWithAi: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    val selectedCount = state.selectedDraftIds.size
    val selectedCards = state.draftCards.filter { it.id in state.selectedDraftIds }
    val canCreate = selectedCount > 0 && selectedCards.all { it.isReadyForCreation() }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                EvidenceSummary(state = state)
            }
            items(state.draftCards, key = { it.id }) { card ->
                val selected = card.id in state.selectedDraftIds
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = ComposeColor.White,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, if (selected) BrandBlue.copy(alpha = 0.42f) else Line),
                    shadowElevation = if (selected) 6.dp else 1.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = selected, onCheckedChange = { onToggleDraft(card.id) })
                            Column(Modifier.weight(1f)) {
                                Text(card.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = listOfNotNull(card.deadline ?: card.startTime, card.location, card.submitMethod)
                                        .joinToString(" · ")
                                        .ifBlank { "需要确认字段后创建" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (selected && (state.draftCards.size == 1 || selectedCount == 1)) {
                            DraftEditor(
                                card = card,
                                onChange = onUpdateDraft,
                                onRemove = { onRemoveDraft(card.id) },
                            )
                        } else if (selected) {
                            Text(
                                text = "已选中。若要手动补字段，请先只保留这一张选中；也可继续 AI 复检。",
                                style = MaterialTheme.typography.bodySmall,
                                color = BrandBlue,
                            )
                        }
                    }
                }
            }
            item {
                AiRefinementCard(state = state, onRefineWithAi = onRefineWithAi)
            }
            if (state.previewActions.isNotEmpty()) {
                item { PreviewActionsCard(previewActions = state.previewActions.take(4)) }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = ComposeColor.White.copy(alpha = 0.94f),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Line),
            shadowElevation = 4.dp,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
            ) {
                OutlinedButton(
                    onClick = { onRefineWithAi("继续检查遗漏事项，补全具体字段") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "继续让 AI 完善" },
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("继续让 AI 完善")
                }
                state.aiRefinementStatus?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = BrandBlue,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "AI 完善状态" },
                    )
                }
                state.reactSuggestions.firstOrNull()?.let { suggestion ->
                    Text(
                        text = "建议：$suggestion",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "AI 完善建议" },
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.draftCards.size > 1) {
                        OutlinedButton(
                            onClick = onSelectAll,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text("全选")
                        }
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = canCreate,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                        Spacer(Modifier.height(0.dp))
                        Text(
                            text = if (!canCreate) {
                                "补全后继续"
                            } else if (state.draftCards.size > 1) {
                                if (selectedCount == state.draftCards.size) "全部创建" else "只创建 $selectedCount 个"
                            } else {
                                "确认创建"
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiRefinementCard(
    state: AppUiState,
    onRefineWithAi: (String) -> Unit,
) {
    var instruction by remember { mutableStateOf("") }
    val quickActions = listOf(
        "拆成多张卡",
        "补全截止时间",
        "提取提交方式",
        "重写标题更具体",
        "检查是否有遗漏事项",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ComposeColor(0xFFF7FAFF),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, BrandBlue.copy(alpha = 0.16f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(18.dp))
                Text("让 AI 继续完善", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Text(
                state.aiRefinementStatus ?: "不想手动改时，可以让 AI 继续拆分、补全或验证候选卡。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.reactSuggestions.take(3).forEach { suggestion ->
                Text(
                    text = "建议：$suggestion",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandBlue,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                quickActions.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { label ->
                            OutlinedButton(
                                onClick = { onRefineWithAi(label) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            OutlinedTextField(
                value = instruction,
                onValueChange = { instruction = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("也可以直接告诉 AI 怎么改") },
                placeholder = { Text("例如：把会议和提交材料拆开") },
                shape = RoundedCornerShape(16.dp),
                maxLines = 2,
            )
            Button(
                onClick = {
                    val text = instruction.trim().ifBlank { "继续检查遗漏事项，补全具体字段" }
                    instruction = ""
                    onRefineWithAi(text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "继续让 AI 完善" },
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("继续让 AI 完善")
            }
        }
    }
}

@Composable
private fun EvidenceSummary(state: AppUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BrandBlue.copy(alpha = 0.08f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, BrandBlue.copy(alpha = 0.10f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val scene = state.screenshotScenarioType?.let { scenarioLabel(it) }
            val confidence = state.screenshotConfidenceBand?.let { confidenceLabel(it) }
            val enhancementBadges = buildList {
                when (state.modelEnhancementStatus) {
                    "succeeded" -> add("\u4e91\u7aef\u6a21\u578b\u5df2\u53c2\u4e0e")
                    "degraded" -> add("\u4e91\u7aef\u589e\u5f3a\u5df2\u964d\u7ea7")
                    "attempted" -> add("\u7b49\u5f85\u4e91\u7aef\u589e\u5f3a")
                }
                when (state.ocrEnhancementStatus) {
                    "succeeded" -> add("vivo OCR \u5df2\u53c2\u4e0e")
                    "degraded" -> add("vivo OCR \u5df2\u964d\u7ea7")
                }
            }
            if (enhancementBadges.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    enhancementBadges.forEach { label ->
                        Surface(
                            color = BrandBlue.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, BrandBlue.copy(alpha = 0.16f)),
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = BrandBlue,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (scene != null || confidence != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = listOfNotNull(scene, confidence).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = BrandBlue,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            state.screenshotPrimaryEvidence.take(3).forEach { evidence ->
                Text(
                    text = "• $evidence",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            state.ocrArbitrationReason?.let {
                Text(
                    text = "OCR: $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    else -> "待确认场景"
}

private fun confidenceLabel(value: String): String = when (value) {
    "high" -> "高可信"
    "medium" -> "中可信"
    else -> "低可信"
}

private fun ActionCard.isReadyForCreation(): Boolean {
    if (title.isBlank()) return false
    if (title in setOf("相关日程", "待办事项", "相关事项", "日程提醒", "行动事项")) return false
    if (needConfirm.isNotEmpty()) return false
    if (cardType == "promise" && deadline.isNullOrBlank() && startTime.isNullOrBlank()) return false
    return true
}
