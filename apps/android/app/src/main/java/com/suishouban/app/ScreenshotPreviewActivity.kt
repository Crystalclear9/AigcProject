package com.suishouban.app

import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suishouban.app.ui.components.DraftEditor
import com.suishouban.app.ui.components.PreviewActionsCard
import com.suishouban.app.ui.theme.SuiShouBanTheme

class ScreenshotPreviewActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val screenshotUri = intent.data
        val ocrText = intent.getStringExtra(EXTRA_OCR_TEXT)
        val gateReason = intent.getStringExtra(EXTRA_GATE_REASON)
        val deadlineHint = intent.getStringExtra(EXTRA_DEADLINE_HINT)
        val promptSummary = intent.getStringExtra(EXTRA_PROMPT_SUMMARY)
        val confidenceBand = intent.getStringExtra(EXTRA_CONFIDENCE_BAND)
        val scenarioType = intent.getStringExtra(EXTRA_SCENARIO_TYPE)
        val primaryEvidence = intent.getStringArrayListExtra(EXTRA_PRIMARY_EVIDENCE).orEmpty()
        setContent {
            SuiShouBanTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                LaunchedEffect(screenshotUri, ocrText) {
                    if (screenshotUri == null) {
                        viewModel.clearError()
                        finish()
                        return@LaunchedEffect
                    }
                    // 弹窗入口只处理当前截图 URI，避免进入主 App 导航后再触发分析。
                    if (!ocrText.isNullOrBlank()) {
                        viewModel.analyzeScreenshotPrompt(
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

                ScreenshotPreviewDialog(
                    state = state,
                    onUpdateDraft = viewModel::updateDraft,
                    onRemoveDraft = viewModel::removeDraft,
                    onConfirm = { viewModel.confirmDrafts { finish() } },
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_OCR_TEXT = "com.suishouban.app.extra.OCR_TEXT"
        const val EXTRA_GATE_REASON = "com.suishouban.app.extra.GATE_REASON"
        const val EXTRA_DEADLINE_HINT = "com.suishouban.app.extra.DEADLINE_HINT"
        const val EXTRA_PROMPT_SUMMARY = "com.suishouban.app.extra.PROMPT_SUMMARY"
        const val EXTRA_CONFIDENCE_BAND = "com.suishouban.app.extra.CONFIDENCE_BAND"
        const val EXTRA_SCENARIO_TYPE = "com.suishouban.app.extra.SCENARIO_TYPE"
        const val EXTRA_PRIMARY_EVIDENCE = "com.suishouban.app.extra.PRIMARY_EVIDENCE"
    }
}

@Composable
private fun ScreenshotPreviewDialog(
    state: AppUiState,
    onUpdateDraft: (com.suishouban.app.data.model.ActionCard) -> Unit,
    onRemoveDraft: (String) -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("发现可能行动事项", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = state.screenshotPromptSummary
                            ?: state.screenshotDeadlineHint
                            ?: state.screenshotGateReason
                            ?: if (state.engine.isBlank()) "正在根据 OCR 生成草稿" else state.engine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭")
                }
            }

            Spacer(Modifier.height(12.dp))

            when {
                state.loading -> LoadingPane()
                state.draftCards.isEmpty() -> EmptyPane(state.error, onClose)
                else -> DraftPane(
                    state = state,
                    onUpdateDraft = onUpdateDraft,
                    onRemoveDraft = onRemoveDraft,
                    onConfirm = onConfirm,
                )
            }
        }
    }
}

@Composable
private fun LoadingPane() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator()
            Text("正在根据 OCR 生成行动草稿", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EmptyPane(error: String?, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(error ?: "未识别到明确行动事项", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Text("关闭")
        }
    }
}

@Composable
private fun DraftPane(
    state: AppUiState,
    onUpdateDraft: (com.suishouban.app.data.model.ActionCard) -> Unit,
    onRemoveDraft: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            if (state.screenshotPrimaryEvidence.isNotEmpty() || state.screenshotScenarioType != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.screenshotScenarioType?.let {
                        Text(
                            text = "识别场景：${scenarioLabel(it)}" +
                                (state.screenshotConfidenceBand?.let { band -> " · ${confidenceLabel(band)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.screenshotPrimaryEvidence.take(4).forEach { evidence ->
                        Text(
                            text = "• $evidence",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            PreviewActionsCard(previewActions = state.previewActions)
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
                Spacer(Modifier.height(0.dp))
                Text("确认创建提醒与行动卡", modifier = Modifier.padding(start = 8.dp))
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
    else -> "待确认"
}

private fun confidenceLabel(value: String): String = when (value) {
    "high" -> "高可信"
    "medium" -> "中可信"
    else -> "低可信"
}
