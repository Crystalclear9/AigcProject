package com.suishouban.app

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.lifecycle.lifecycleScope
import com.suishouban.app.data.local.AppDatabase
import com.suishouban.app.data.local.IntakeSessionEntity
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardTypes
import com.suishouban.app.domain.screenshot.ScreenshotWorkflowStage
import com.suishouban.app.domain.team.TeamMemberOption
import com.suishouban.app.domain.team.TeamWorkspacePolicy
import com.suishouban.app.domain.TextIntegrity
import com.suishouban.app.reminder.ScreenshotMonitorService
import com.suishouban.app.mascot.MascotOverlayService
import com.suishouban.app.ui.components.DraftEditor
import com.suishouban.app.ui.components.NeutralPill
import com.suishouban.app.ui.components.PreviewActionsCard
import com.suishouban.app.ui.components.formatSmartTime
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.Line
import com.suishouban.app.ui.theme.Muted
import com.suishouban.app.ui.theme.visualForPriority
import com.suishouban.app.ui.theme.SuiShouBanTheme
import androidx.compose.ui.graphics.Color as ComposeColor
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.launch

class ScreenshotPreviewActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private var privateCaptureUri: Uri? = null
    private var restoreOverlayAfterCapture = false
    private var intakeSessionId: String? = null
    private var sessionFinished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        configureFloatingWindow()
        val incomingIntent = intent
        val fromPrivateCapture = isTrustedPrivateCapture(incomingIntent)
        if (fromPrivateCapture) privateCaptureUri = incomingIntent.data
        restoreOverlayAfterCapture = fromPrivateCapture &&
            incomingIntent.getBooleanExtra(EXTRA_RESTORE_OVERLAY_AFTER_CAPTURE, false)
        val sourceIntent = when {
            fromPrivateCapture -> incomingIntent
            ScreenshotMonitorService.isTrustedPendingPreview(this, incomingIntent) -> incomingIntent
            incomingIntent.action == ScreenshotMonitorService.ACTION_PROCESS_SCREENSHOT ->
                ScreenshotMonitorService.consumePendingPreviewIntent(this)
            else -> null
        }
        if (sourceIntent == null) {
            finish()
            return
        }
        val screenshotUri = sourceIntent.data
        val intakeSessionId = sourceIntent.getStringExtra(EXTRA_INTAKE_SESSION_ID)
            ?: UUID.randomUUID().toString()
        this.intakeSessionId = intakeSessionId
        viewModel.beginFreshScreenshotPrompt(intakeSessionId)
        lifecycleScope.launch {
            val now = OffsetDateTime.now().toString()
            AppDatabase.get(this@ScreenshotPreviewActivity).workflowDao().upsertIntake(
                IntakeSessionEntity(
                    id = intakeSessionId,
                    sourceKind = if (fromPrivateCapture) "external_mofei_screenshot" else "screenshot",
                    sourceUri = screenshotUri?.toString(),
                    workspaceType = "personal",
                    status = "reviewing",
                    workflowRunId = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        val ocrText = sourceIntent.getStringExtra(EXTRA_OCR_TEXT)
            ?: ScreenshotMonitorService.consumePendingOcrText(sourceIntent.getStringExtra(EXTRA_OCR_TOKEN))
            ?: sourceIntent.getStringExtra(EXTRA_OCR_TEXT_BASE64)?.let(::decodeUtf8Base64)
        val gateReason = sourceIntent.getStringExtra(EXTRA_GATE_REASON)
        val deadlineHint = sourceIntent.getStringExtra(EXTRA_DEADLINE_HINT)
        val promptSummary = sourceIntent.getStringExtra(EXTRA_PROMPT_SUMMARY)
        val confidenceBand = sourceIntent.getStringExtra(EXTRA_CONFIDENCE_BAND)
        val scenarioType = sourceIntent.getStringExtra(EXTRA_SCENARIO_TYPE)
        val primaryEvidence = sourceIntent.getStringArrayListExtra(EXTRA_PRIMARY_EVIDENCE).orEmpty()
        if (!fromPrivateCapture) ScreenshotMonitorService.clearPendingPreview(this)

        setContent {
            SuiShouBanTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val teamState by viewModel.teamUiState.collectAsStateWithLifecycle()
                val teamMembers by viewModel.teamMemberOptions.collectAsStateWithLifecycle()

                LaunchedEffect(screenshotUri, ocrText) {
                    if (screenshotUri == null) {
                        finish()
                        return@LaunchedEffect
                    }
                    if (!ocrText.isNullOrBlank() || !promptSummary.isNullOrBlank() || !gateReason.isNullOrBlank()) {
                        viewModel.prepareScreenshotPrompt(
                            ocrText = ocrText.orEmpty(),
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
                        if (state.ocrText.isBlank() && screenshotUri != null) {
                            viewModel.analyzeImage(screenshotUri, notifyWhenEmpty = true)
                        } else {
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
                        }
                    },
                    onUpdateDraft = viewModel::updateDraft,
                    onRemoveDraft = viewModel::removeDraft,
                    onToggleDraft = viewModel::toggleDraftSelection,
                    onSelectAll = viewModel::selectAllDrafts,
                    onRefineWithAi = viewModel::refineDraftWithAi,
                    onResolveOcr = { corrected -> viewModel.analyzeText(corrected) },
                    onManualAdd = viewModel::addManualDraftFromCurrentText,
                    teams = teamState.teams,
                    teamMembers = teamMembers,
                    onSelectWorkspace = viewModel::setDraftWorkspace,
                    onConfirm = {
                        viewModel.confirmDrafts {
                            sessionFinished = true
                            finish()
                        }
                    },
                    onIgnore = {
                        viewModel.ignoreScreenshotWorkflow {
                            sessionFinished = true
                            markSessionTerminal("ignored")
                            finish()
                        }
                    },
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
        private const val ACTION_CAPTURE_PREVIEW = "com.suishouban.app.action.PREVIEW_MOFEI_CAPTURE"
        private const val EXTRA_RESTORE_OVERLAY_AFTER_CAPTURE = "restore_overlay_after_capture"
        const val EXTRA_OCR_TEXT = "com.suishouban.app.extra.OCR_TEXT"
        const val EXTRA_GATE_REASON = "com.suishouban.app.extra.GATE_REASON"
        const val EXTRA_DEADLINE_HINT = "com.suishouban.app.extra.DEADLINE_HINT"
        const val EXTRA_PROMPT_SUMMARY = "com.suishouban.app.extra.PROMPT_SUMMARY"
        const val EXTRA_CONFIDENCE_BAND = "com.suishouban.app.extra.CONFIDENCE_BAND"
        const val EXTRA_SCENARIO_TYPE = "com.suishouban.app.extra.SCENARIO_TYPE"
        const val EXTRA_PRIMARY_EVIDENCE = "com.suishouban.app.extra.PRIMARY_EVIDENCE"
        const val EXTRA_NOTIFICATION_ID = "com.suishouban.app.extra.NOTIFICATION_ID"
        const val EXTRA_OCR_TEXT_BASE64 = "com.suishouban.app.extra.OCR_TEXT_BASE64"
        const val EXTRA_OCR_TOKEN = "com.suishouban.app.extra.OCR_TOKEN"
        const val EXTRA_INTAKE_SESSION_ID = "com.suishouban.app.extra.INTAKE_SESSION_ID"

        /** Explicit and non-exported; only private FileProvider capture URIs are accepted. */
        fun captureIntent(
            context: Context,
            uri: Uri,
            restoreOverlayAfterCapture: Boolean = false,
            intakeSessionId: String = UUID.randomUUID().toString(),
        ): Intent =
            Intent(context, ScreenshotPreviewActivity::class.java).apply {
                action = ACTION_CAPTURE_PREVIEW
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(EXTRA_RESTORE_OVERLAY_AFTER_CAPTURE, restoreOverlayAfterCapture)
                putExtra(EXTRA_INTAKE_SESSION_ID, intakeSessionId)
            }

        private fun Context.isTrustedPrivateCapture(source: Intent?): Boolean {
            val uri = source?.data ?: return false
            return source.action == ACTION_CAPTURE_PREVIEW &&
                uri.scheme == "content" &&
                uri.authority == "$packageName.fileprovider" &&
                uri.path.orEmpty().contains("mofei_capture")
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            if (!sessionFinished) markSessionTerminal("cancelled")
            // FileProvider owns this app-private cache URI; system MediaStore screenshots are untouched.
            privateCaptureUri?.let { uri -> runCatching { contentResolver.delete(uri, null, null) } }
            privateCaptureUri = null
            if (restoreOverlayAfterCapture) {
                restoreOverlayAfterCapture = false
                MascotOverlayService.restoreVisibleAfterCapture(this)
            }
        }
        super.onDestroy()
    }

    private fun markSessionTerminal(status: String) {
        val id = intakeSessionId ?: return
        (application as SuiShouBanApp).applicationScope.launch {
            AppDatabase.get(this@ScreenshotPreviewActivity).workflowDao().updateIntakeStatus(
                id = id,
                status = status,
                workflowRunId = null,
                updatedAt = OffsetDateTime.now().toString(),
            )
        }
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
    onResolveOcr: (String) -> Unit,
    onManualAdd: () -> Unit,
    teams: List<TeamSummary> = emptyList(),
    teamMembers: List<TeamMemberOption> = emptyList(),
    onSelectWorkspace: (String?) -> Unit = {},
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    val isReviewing = state.draftCards.isNotEmpty()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .then(
                if (isReviewing) Modifier.height(maxHeight)
                else Modifier.heightIn(max = maxHeight)
            )
            .semantics { contentDescription = "screenshot-action-panel" },
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
                state.draftCards.isEmpty() -> EmptyPane(
                    error = state.error,
                    onRetry = onStartAnalysis,
                    onManualAdd = onManualAdd,
                    onIgnore = onIgnore,
                )
                else -> DraftPane(
                    state = state,
                    onUpdateDraft = onUpdateDraft,
                    onRemoveDraft = onRemoveDraft,
                    onToggleDraft = onToggleDraft,
                    onSelectAll = onSelectAll,
                    onRefineWithAi = onRefineWithAi,
                    onResolveOcr = onResolveOcr,
                    teams = teams,
                    teamMembers = teamMembers,
                    onSelectWorkspace = onSelectWorkspace,
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
private fun EmptyPane(
    error: String?,
    onRetry: () -> Unit,
    onManualAdd: () -> Unit,
    onIgnore: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(error ?: "没有识别到明确行动事项", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                Text("重新识别")
            }
            OutlinedButton(onClick = onManualAdd, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                Text("手动添加")
            }
        }
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
    onResolveOcr: (String) -> Unit,
    teams: List<TeamSummary> = emptyList(),
    teamMembers: List<TeamMemberOption> = emptyList(),
    onSelectWorkspace: (String?) -> Unit = {},
    onConfirm: () -> Unit,
) {
    val selectedCount = state.selectedDraftIds.size
    val selectedCards = state.draftCards.filter { it.id in state.selectedDraftIds }
    val requiresOcrReview = state.workflowStatus == "awaiting_ocr_review"
    val canCreate = selectedCount > 0 &&
        !requiresOcrReview &&
        selectedCards.all { it.isReadyForCreation() }
    val selectedTeamMembers = state.draftTeamId
        ?.let { teamId -> teamMembers.filter { it.teamId == teamId } }
        .orEmpty()
    var correctedOcrText by remember(state.traceId) { mutableStateOf(state.ocrText) }
    val suggestedOcrText = remember(state.ocrText) { TextIntegrity.suggestOcrCorrection(state.ocrText) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (requiresOcrReview) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.38f),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.24f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "识别文字需要复核",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "请修正错字或时间，再重新拆分事项。当前候选不会被保存。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = correctedOcrText,
                                onValueChange = { correctedOcrText = it },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                                maxLines = 6,
                                label = { Text("识别文字") },
                            )
                            if (suggestedOcrText != null && correctedOcrText != suggestedOcrText) {
                                OutlinedButton(
                                    onClick = { correctedOcrText = suggestedOcrText },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Text("使用建议修正")
                                }
                            }
                            Button(
                                onClick = { onResolveOcr(correctedOcrText.trim()) },
                                enabled = correctedOcrText.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text("应用修正并重新分析")
                            }
                        }
                    }
                }
            }
            item {
                EvidenceSummary(state = state)
            }
            if (teams.isNotEmpty()) {
                // Batch-level 归属 chip: one quiet control for the whole candidate list.
                item {
                    var menuOpen by remember { mutableStateOf(false) }
                    val selectedLabel = teams.firstOrNull { it.id == state.draftTeamId }?.name ?: "个人"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("归属", style = MaterialTheme.typography.labelMedium, color = Muted)
                        Spacer(Modifier.width(8.dp))
                        Box {
                            NeutralPill(
                                text = "$selectedLabel ▾",
                                selected = state.draftTeamId != null,
                                onClick = { menuOpen = true },
                            )
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("个人") },
                                    onClick = {
                                        menuOpen = false
                                        onSelectWorkspace(null)
                                    },
                                )
                                teams.forEach { team ->
                                    DropdownMenuItem(
                                        text = { Text(team.name) },
                                        onClick = {
                                            menuOpen = false
                                            onSelectWorkspace(team.id)
                                        },
                                    )
                                }
                            }
                        }
                        if (state.draftTeamSuggested && state.draftTeamId != null) {
                            Spacer(Modifier.width(6.dp))
                            Text("AI 建议", style = MaterialTheme.typography.labelSmall, color = Muted)
                        }
                    }
                }
            }
            items(state.draftCards, key = { it.id }) { card ->
                val selected = card.id in state.selectedDraftIds
                val candidateInfo = state.actionCandidates.firstOrNull { it.card.id == card.id }
                val priorityVisual = visualForPriority(card.priority)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = priorityVisual.container,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(
                        if (selected) 2.dp else 1.dp,
                        if (selected) priorityVisual.accent else priorityVisual.accent.copy(alpha = 0.34f),
                    ),
                    shadowElevation = if (selected) 6.dp else 1.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { onToggleDraft(card.id) },
                                modifier = Modifier.semantics {
                                    contentDescription = "选择候选：${card.title}"
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(card.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = listOfNotNull(
                                        formatSmartTime(card.deadline ?: card.startTime),
                                        card.location,
                                        card.submitMethod,
                                    )
                                        .joinToString(" · ")
                                        .ifBlank { "需要确认字段后创建" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                color = priorityVisual.accent.copy(alpha = 0.13f),
                                shape = RoundedCornerShape(999.dp),
                            ) {
                                Text(
                                    text = priorityVisual.label,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = priorityVisual.content,
                                )
                            }
                            Surface(
                                color = BrandBlue.copy(alpha = 0.10f),
                                shape = RoundedCornerShape(999.dp),
                            ) {
                                Text(
                                    text = confidenceLabel(candidateInfo?.confidenceBand ?: "medium"),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BrandBlue,
                                )
                            }
                            if (card.needConfirm.isNotEmpty()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(999.dp),
                                ) {
                                    Text(
                                        text = "待确认 ${card.needConfirm.size}",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                            if (state.draftTeamId != null) {
                                val assigneeLabel = TeamWorkspacePolicy
                                    .matchAssignee(card.assigneeId, selectedTeamMembers)?.nickname
                                    ?: card.assigneeId?.trim()?.takeIf(String::isNotBlank)
                                assigneeLabel?.let { label ->
                                    Surface(
                                        color = BrandBlue.copy(alpha = 0.10f),
                                        shape = RoundedCornerShape(999.dp),
                                    ) {
                                        Text(
                                            text = label,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = BrandBlue,
                                        )
                                    }
                                }
                            }
                        }
                        val evidence = candidateInfo?.evidenceSummary?.ifEmpty { card.evidenceSummary } ?: card.evidenceSummary
                        evidence.take(2).forEach { item ->
                            Text(
                                text = "证据：$item",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
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
                state.teamPushError?.let { pushError ->
                    Text(
                        text = pushError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
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
                                when {
                                    requiresOcrReview -> "先修正识别文字"
                                    selectedCount == 0 && state.draftCards.isNotEmpty() -> "选择后创建"
                                    else -> "补全后继续"
                                }
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
    val scene = (state.screenshotScenarioType ?: inferScenarioType(state.draftCards))?.let { scenarioLabel(it) }
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
    val evidenceItems = state.screenshotPrimaryEvidence
        .filter(String::isNotBlank)
        .take(3)
    if (scene == null && confidence == null && enhancementBadges.isEmpty() && evidenceItems.isEmpty()) {
        return
    }

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
            evidenceItems.forEach { evidence ->
                Text(
                    text = "• $evidence",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun inferScenarioType(cards: List<ActionCard>): String? {
    val text = cards.joinToString(" ") { "${it.title} ${it.sourceText} ${it.location.orEmpty()}" }
    return when {
        cards.any { it.cardType == CardTypes.PROMISE } -> "chat_promise"
        listOf("学习通", "作业", "课程", "实验报告", "考试").any { it in text } -> "course_notice"
        listOf("报名", "报名表", "参赛").any { it in text } -> "registration"
        listOf("会议", "汇报", "组会", "答辩").any { it in text } -> "meeting"
        else -> null
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
