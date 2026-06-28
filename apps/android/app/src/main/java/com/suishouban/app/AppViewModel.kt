package com.suishouban.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.ActionCandidate
import com.suishouban.app.data.model.AnalyzeResult
import com.suishouban.app.data.model.CardTypes
import com.suishouban.app.data.repository.AppSettings
import com.suishouban.app.data.repository.EngineLabels
import com.suishouban.app.data.model.NodeTrace
import com.suishouban.app.domain.ocr.OcrCandidate
import com.suishouban.app.domain.ocr.OcrRaceController
import com.suishouban.app.domain.screenshot.ScreenshotWorkflowStage
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

data class AppUiState(
    val cards: List<ActionCard> = emptyList(),
    val draftCards: List<ActionCard> = emptyList(),
    val actionCandidates: List<ActionCandidate> = emptyList(),
    val selectedDraftIds: Set<String> = emptySet(),
    val previewActions: List<String> = emptyList(),
    val ocrText: String = "",
    val engine: String = "",
    val traceId: String = "",
    val fallbackReason: String? = null,
    val warnings: List<String> = emptyList(),
    val workflowStatus: String = "",
    val pendingAction: String? = null,
    val nodeTrace: List<NodeTrace> = emptyList(),
    val revision: Int = 0,
    val resultStage: String = "",
    val overallConfidence: Double = 0.0,
    val route: String = "",
    val timeToFirstDraftMs: Double? = null,
    val timeToFinalMs: Double? = null,
    val activeAgents: List<String> = emptyList(),
    val decisionReasons: List<String> = emptyList(),
    val riskLevel: String = "low",
    val validationErrors: List<String> = emptyList(),
    val fieldConflicts: List<Map<String, Any?>> = emptyList(),
    val fieldVersions: Map<String, Map<String, Int>> = emptyMap(),
    val modelEnhancementStatus: String = "not_configured",
    val ocrEnhancementStatus: String = "not_configured",
    val imageGenerationStatus: String = "not_configured",
    val reactSuggestions: List<String> = emptyList(),
    val aiRefinementStatus: String? = null,
    val screenshotGateReason: String? = null,
    val screenshotDeadlineHint: String? = null,
    val screenshotPromptSummary: String? = null,
    val screenshotConfidenceBand: String? = null,
    val screenshotScenarioType: String? = null,
    val screenshotPrimaryEvidence: List<String> = emptyList(),
    val screenshotWorkflowStage: ScreenshotWorkflowStage? = null,
    val ocrArbitrationReason: String? = null,
    val connectionStatus: String = "未检测",
    val loading: Boolean = false,
    val error: String? = null,
    val settings: AppSettings = AppSettings(),
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SuiShouBanApp
    private val repository = app.cardRepository
    private val settingsRepository = app.settingsRepository
    private val ocr = app.textRecognitionService
    private val scheduler = app.reminderScheduler
    private val calendarSyncer = app.calendarSyncer
    private val ocrRaceController = OcrRaceController
    private val locallyEditedDraftIds = mutableSetOf<String>()
    private var ignoreActiveWorkflowRestore: Boolean = false
    private var restoreWorkflowJob: Job? = null

    private val _uiState = MutableStateFlow(AppUiState(settings = settingsRepository.settings.value))
    val uiState: StateFlow<AppUiState> = _uiState

    init {
        viewModelScope.launch {
            repository.observeAll().collect { cards ->
                _uiState.update { it.copy(cards = cards) }
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
        repository.activeRunId()?.let { runId ->
            restoreWorkflowJob = viewModelScope.launch {
                runCatching {
                    repository.followWorkflow(runId) {
                        if (!ignoreActiveWorkflowRestore) {
                            applyAnalyzeResult(it)
                        }
                    }
                }.onFailure {
                    if (it is CancellationException || ignoreActiveWorkflowRestore) return@onFailure
                    _uiState.update { state ->
                        state.copy(error = userVisibleWorkflowError(it, "恢复上次工作流失败"))
                    }
                }
            }
        }
    }

    fun beginFreshScreenshotPrompt() {
        ignoreActiveWorkflowRestore = true
        restoreWorkflowJob?.cancel()
        restoreWorkflowJob = null
        repository.clearActiveWorkflow()
        locallyEditedDraftIds.clear()
        _uiState.update {
            it.copy(
                loading = false,
                draftCards = emptyList(),
                actionCandidates = emptyList(),
                selectedDraftIds = emptySet(),
                previewActions = emptyList(),
                ocrText = "",
                engine = "",
                traceId = "",
                fallbackReason = null,
                warnings = emptyList(),
                workflowStatus = "",
                pendingAction = null,
                nodeTrace = emptyList(),
                revision = 0,
                resultStage = "",
                overallConfidence = 0.0,
                route = "",
                timeToFirstDraftMs = null,
                timeToFinalMs = null,
                activeAgents = emptyList(),
                decisionReasons = emptyList(),
                riskLevel = "low",
                validationErrors = emptyList(),
                fieldConflicts = emptyList(),
                fieldVersions = emptyMap(),
                modelEnhancementStatus = "not_configured",
                ocrEnhancementStatus = "not_configured",
                imageGenerationStatus = "not_configured",
                reactSuggestions = emptyList(),
                aiRefinementStatus = null,
                screenshotGateReason = null,
                screenshotDeadlineHint = null,
                screenshotPromptSummary = null,
                screenshotConfidenceBand = null,
                screenshotScenarioType = null,
                screenshotPrimaryEvidence = emptyList(),
                screenshotWorkflowStage = null,
                ocrArbitrationReason = null,
                error = null,
            )
        }
    }

    fun analyzeImage(
        uri: Uri,
        notifyWhenEmpty: Boolean = true,
        onDone: (Boolean) -> Unit = {},
    ) {
        locallyEditedDraftIds.clear()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = true,
                    error = null,
                    actionCandidates = emptyList(),
                    selectedDraftIds = emptySet(),
                    screenshotGateReason = null,
                    screenshotDeadlineHint = null,
                    screenshotPromptSummary = null,
                    screenshotConfidenceBand = null,
                    screenshotScenarioType = null,
                    screenshotPrimaryEvidence = emptyList(),
                    screenshotWorkflowStage = ScreenshotWorkflowStage.OCR_DETECTED,
                    ocrArbitrationReason = null,
                    reactSuggestions = emptyList(),
                    aiRefinementStatus = null,
                )
            }
            val screenshotTime = OffsetDateTime.now(ZoneOffset.ofHours(8)).toString()
            val localOcr = async { runCatching { ocr.recognize(getApplication(), uri) }.getOrNull() }
            val cloudResult = runCatching { repository.analyzeImage(uri, screenshotTime) }.getOrNull()
            if (cloudResult != null) {
                val candidateSubmit = async {
                    localOcr.await()?.let { text ->
                        runCatching { repository.submitOcrCandidate(cloudResult.traceId, text) }
                    }
                }
                var previewOpened = false
                runCatching {
                    repository.followWorkflow(cloudResult.traceId) { update ->
                        applyAnalyzeResult(update)
                        if (!previewOpened && update.cards.isNotEmpty()) {
                            previewOpened = true
                            onDone(true)
                        }
                    }
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(loading = false, error = userVisibleWorkflowError(error, "工作流事件流中断"))
                    }
                }
                candidateSubmit.await()
                return@launch
            }
            val text = localOcr.await()
            if (text == null) {
                _uiState.update { it.copy(loading = false, error = "Image recognition failed") }
                return@launch
            }
            val arbitration = ocrRaceController.arbitrate(
                listOf(
                    OcrCandidate(
                        engine = "mlkit",
                        text = text,
                        blocks = text.lines().count { it.isNotBlank() },
                        arrivedAtMs = 0L,
                    ),
                ),
            ) ?: run {
                _uiState.update { it.copy(loading = false, error = "Image recognition failed") }
                return@launch
            }
            analyzeTextInternal(
                text = arbitration.selectedCandidate.text,
                onDone = onDone,
                screenshotTime = screenshotTime,
                enginePrefix = arbitration.selectedCandidate.engine,
                extraWarnings = listOf("云端增强不可用，已使用端侧 OCR 与本地规则"),
                notifyWhenEmpty = notifyWhenEmpty,
            )
            _uiState.update { it.copy(ocrArbitrationReason = arbitration.reason) }
        }
    }

    fun analyzeText(text: String, onDone: (Boolean) -> Unit = {}) {
        locallyEditedDraftIds.clear()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = true,
                    error = null,
                    actionCandidates = emptyList(),
                    selectedDraftIds = emptySet(),
                    screenshotGateReason = null,
                    screenshotDeadlineHint = null,
                    screenshotPromptSummary = null,
                    screenshotConfidenceBand = null,
                    screenshotScenarioType = null,
                    screenshotPrimaryEvidence = emptyList(),
                    screenshotWorkflowStage = null,
                    ocrArbitrationReason = null,
                    reactSuggestions = emptyList(),
                    aiRefinementStatus = null,
                )
            }
            analyzeTextInternal(text, onDone, notifyWhenEmpty = true)
        }
    }

    fun prepareScreenshotPrompt(
        ocrText: String,
        gateReason: String?,
        deadlineHint: String?,
        promptSummary: String?,
        confidenceBand: String?,
        scenarioType: String?,
        primaryEvidence: List<String>,
    ) {
        locallyEditedDraftIds.clear()
        _uiState.update {
            it.copy(
                loading = false,
                error = null,
                ocrText = ocrText,
                draftCards = emptyList(),
                actionCandidates = emptyList(),
                selectedDraftIds = emptySet(),
                previewActions = emptyList(),
                engine = "",
                traceId = "",
                screenshotGateReason = gateReason,
                screenshotDeadlineHint = deadlineHint,
                screenshotPromptSummary = promptSummary,
                screenshotConfidenceBand = confidenceBand,
                screenshotScenarioType = scenarioType,
                screenshotPrimaryEvidence = primaryEvidence,
                screenshotWorkflowStage = ScreenshotWorkflowStage.PROMPT_SHOWN,
                ocrArbitrationReason = null,
                reactSuggestions = emptyList(),
                aiRefinementStatus = null,
            )
        }
    }

    fun analyzeScreenshotPrompt(
        screenshotUri: Uri? = null,
        ocrText: String,
        gateReason: String?,
        deadlineHint: String?,
        promptSummary: String?,
        confidenceBand: String?,
        scenarioType: String?,
        primaryEvidence: List<String>,
        onDone: (Boolean) -> Unit = {},
    ) {
        locallyEditedDraftIds.clear()
        viewModelScope.launch {
            val warnings = buildList {
                gateReason?.takeIf { it.isNotBlank() }?.let { add("截图判定：$it") }
                deadlineHint?.takeIf { it.isNotBlank() }?.let { add("候选截止：$it") }
            }
            _uiState.update {
                it.copy(
                    loading = true,
                    error = null,
                    actionCandidates = emptyList(),
                    selectedDraftIds = emptySet(),
                    ocrText = ocrText,
                    screenshotGateReason = gateReason,
                    screenshotDeadlineHint = deadlineHint,
                    screenshotPromptSummary = promptSummary,
                    screenshotConfidenceBand = confidenceBand,
                    screenshotScenarioType = scenarioType,
                    screenshotPrimaryEvidence = primaryEvidence,
                    screenshotWorkflowStage = ScreenshotWorkflowStage.ANALYZING,
                    reactSuggestions = emptyList(),
                    aiRefinementStatus = null,
                )
            }
            val screenshotTime = OffsetDateTime.now(ZoneOffset.ofHours(8)).toString()
            val remoteWorkflow = async {
                runCatching {
                    repository.analyzeText(
                        text = ocrText,
                        screenshotTime = screenshotTime,
                        enginePrefix = "mlkit",
                    )
                }.getOrNull()
            }
            val localResult = runCatching {
                repository.analyzeTextLocal(
                    text = ocrText,
                    screenshotTime = screenshotTime,
                    enginePrefix = "mlkit",
                )
            }.getOrElse { error ->
                _uiState.update { it.copy(loading = false, error = "行动卡生成失败：${error.message ?: "未知错误"}") }
                return@launch
            }
            val hasLocalCards = applyAnalyzeResult(
                localResult.copy(warnings = warnings + localResult.warnings),
                notifyWhenEmpty = true,
            )
            val localCandidateFloor = localResult.cards
            onDone(hasLocalCards)

            val cloudStart = remoteWorkflow.await()
            if (cloudStart?.traceId.isNullOrBlank()) return@launch
            applyAnalyzeResult(
                cloudStart!!.copy(
                    engine = EngineLabels.withPrefix(cloudStart.engine, "mlkit"),
                    cards = mergeRemoteWithLocalCandidateFloor(localCandidateFloor, cloudStart.cards),
                    warnings = warnings + listOf("云端增强正在校验截图和补全候选字段") + cloudStart.warnings,
                ),
            )
            runCatching {
                repository.followWorkflow(cloudStart.traceId) { update ->
                    val enhanced = update.copy(
                        engine = EngineLabels.withPrefix(update.engine, "mlkit"),
                        cards = mergeRemoteWithLocalCandidateFloor(localCandidateFloor, update.cards),
                        warnings = warnings + listOf("云端增强结果已合入候选；用户编辑字段保持锁定") + update.warnings,
                    )
                    applyAnalyzeResult(enhanced)
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(error = userVisibleWorkflowError(error, "云端增强失败，已保留端侧草稿"))
                }
            }
        }
    }

    private suspend fun analyzeTextInternal(
        text: String,
        onDone: (Boolean) -> Unit,
        screenshotTime: String? = null,
        enginePrefix: String? = null,
        extraWarnings: List<String> = emptyList(),
        notifyWhenEmpty: Boolean,
    ) {
        if (text.isBlank()) {
            _uiState.update { it.copy(loading = false, error = "没有识别到可分析的文字") }
            return
        }
        val result = runCatching { repository.analyzeText(text, screenshotTime, enginePrefix) }
            .getOrElse { error ->
                _uiState.update { it.copy(loading = false, error = "行动卡生成失败：${error.message ?: "未知错误"}") }
                return
            }
        if (result.workflowStatus in setOf("queued", "running") && result.traceId.isNotBlank()) {
            var previewOpened = false
            runCatching {
                repository.followWorkflow(result.traceId) { update ->
                    val prefixed = update.copy(engine = EngineLabels.withPrefix(update.engine, enginePrefix))
                    applyAnalyzeResult(prefixed.copy(warnings = extraWarnings + prefixed.warnings))
                    if (!previewOpened && update.cards.isNotEmpty()) {
                        previewOpened = true
                        onDone(true)
                    }
                }
            }.onFailure { error ->
                _uiState.update { it.copy(loading = false, error = userVisibleWorkflowError(error, "工作流事件流中断")) }
            }
            return
        }
        val finalResult = result.copy(warnings = extraWarnings + result.warnings)
        val hasCards = applyAnalyzeResult(finalResult, notifyWhenEmpty)
        onDone(hasCards)
    }

    private fun applyAnalyzeResult(result: AnalyzeResult, notifyWhenEmpty: Boolean = false): Boolean {
        val hasCards = result.cards.isNotEmpty()
        _uiState.update {
            val localDrafts = it.draftCards.associateBy { card -> card.id }
            val incomingDrafts = result.cards
            val mergedDrafts = when {
                incomingDrafts.isEmpty() && it.draftCards.isNotEmpty() -> it.draftCards
                incomingDrafts.isNotEmpty() && it.draftCards.isNotEmpty() -> mergeIncomingWithoutOverwritingUserDrafts(
                    localDrafts = it.draftCards,
                    incomingDrafts = incomingDrafts,
                )
                else -> incomingDrafts.map { incoming ->
                    if (incoming.id in locallyEditedDraftIds) localDrafts[incoming.id] ?: incoming else incoming
                }
            }
            val hasVisibleCards = mergedDrafts.isNotEmpty()
            val previousSelections = it.selectedDraftIds
            val hadPriorCandidates = it.draftCards.isNotEmpty() || it.actionCandidates.isNotEmpty()
            val nextSelectedIds = when {
                !hasVisibleCards -> emptySet()
                previousSelections.isEmpty() && !hadPriorCandidates -> mergedDrafts.map { card -> card.id }.toSet()
                previousSelections.isEmpty() -> emptySet()
                else -> previousSelections.intersect(mergedDrafts.map { card -> card.id }.toSet())
            }
            val previousCandidates = it.actionCandidates.associateBy { candidate -> candidate.card.id }
            val candidates = mergedDrafts.map { card ->
                val previous = previousCandidates[card.id]
                ActionCandidate(
                    card = card,
                    selected = card.id in nextSelectedIds,
                    confidenceBand = previous?.confidenceBand ?: confidenceBand(result.overallConfidence),
                    evidenceSummary = card.evidenceSummary.ifEmpty {
                        previous?.evidenceSummary ?: result.decisionReasons.take(3)
                    },
                    sourceSpan = previous?.sourceSpan ?: card.sourceText.take(180),
                    userLockedFields = previous?.userLockedFields.orEmpty(),
                )
            }
            it.copy(
                loading = false,
                ocrText = result.ocrText,
                draftCards = mergedDrafts,
                actionCandidates = candidates,
                selectedDraftIds = nextSelectedIds,
                previewActions = result.previewActions,
                engine = result.engine,
                traceId = result.traceId,
                fallbackReason = result.fallbackReason,
                warnings = result.warnings,
                workflowStatus = result.workflowStatus,
                pendingAction = result.pendingAction,
                nodeTrace = result.nodeTrace,
                revision = result.revision,
                resultStage = result.resultStage,
                overallConfidence = result.overallConfidence,
                route = result.route,
                timeToFirstDraftMs = result.timeToFirstDraftMs,
                timeToFinalMs = result.timeToFinalMs,
                activeAgents = result.activeAgents,
                decisionReasons = result.decisionReasons,
                riskLevel = result.riskLevel,
                validationErrors = result.validationErrors,
                fieldConflicts = result.fieldConflicts,
                fieldVersions = result.fieldVersions,
                modelEnhancementStatus = result.modelEnhancementStatus,
                ocrEnhancementStatus = result.ocrEnhancementStatus,
                imageGenerationStatus = result.imageGenerationStatus,
                reactSuggestions = result.reactSuggestions,
                aiRefinementStatus = when {
                    result.reactSuggestions.isNotEmpty() -> "AI 已完成一次受控 ReAct 完善，建议可逐项确认"
                    result.engine.contains("react", ignoreCase = true) -> "AI 已重新检查候选草稿"
                    else -> it.aiRefinementStatus
                },
                screenshotWorkflowStage = if (hasVisibleCards) ScreenshotWorkflowStage.CANDIDATES_READY else it.screenshotWorkflowStage,
                error = if (!hasVisibleCards && notifyWhenEmpty) "未识别到明确行动事项" else it.error,
            )
        }
        return _uiState.value.draftCards.isNotEmpty()
    }

    private fun mergeIncomingWithoutOverwritingUserDrafts(
        localDrafts: List<ActionCard>,
        incomingDrafts: List<ActionCard>,
    ): List<ActionCard> {
        val merged = localDrafts.toMutableList()
        incomingDrafts.forEach { incoming ->
            val sameIndex = merged.indexOfFirst { existing -> sameActionCandidate(existing, incoming) }
            when {
                sameIndex < 0 -> merged += incoming
                else -> merged[sameIndex] = fillEmptyFields(merged[sameIndex], incoming)
            }
        }
        return merged
    }

    private fun fillEmptyFields(local: ActionCard, incoming: ActionCard): ActionCard {
        return local.copy(
            summary = local.summary.ifBlank { incoming.summary },
            deadline = local.deadline ?: incoming.deadline,
            startTime = local.startTime ?: incoming.startTime,
            endTime = local.endTime ?: incoming.endTime,
            location = local.location ?: incoming.location,
            materials = if (local.materials.isEmpty()) incoming.materials else local.materials,
            submitMethod = local.submitMethod ?: incoming.submitMethod,
            tags = if (local.tags.isEmpty()) incoming.tags else local.tags,
            evidenceSummary = (local.evidenceSummary + incoming.evidenceSummary).distinct().take(6),
        )
    }

    private fun mergeRemoteWithLocalCandidateFloor(
        localCards: List<ActionCard>,
        remoteCards: List<ActionCard>,
    ): List<ActionCard> {
        if (localCards.isEmpty()) return remoteCards
        if (remoteCards.isEmpty()) return localCards
        val merged = localCards.toMutableList()
        remoteCards.forEach { incoming ->
            val sameIndex = merged.indexOfFirst { existing -> sameActionCandidate(existing, incoming) }
            if (sameIndex < 0) {
                merged += incoming
            } else {
                merged[sameIndex] = fillEmptyFields(merged[sameIndex], incoming)
            }
        }
        return merged
    }

    private fun sameActionCandidate(left: ActionCard, right: ActionCard): Boolean {
        if (left.cardType != right.cardType) return false
        val leftSignals = titleActionSignals(left.title)
        val rightSignals = titleActionSignals(right.title)
        if (leftSignals.isNotEmpty() && rightSignals.isNotEmpty() && leftSignals.intersect(rightSignals).isEmpty()) {
            return false
        }
        val leftTime = left.deadline ?: left.startTime
        val rightTime = right.deadline ?: right.startTime
        val sameTime = !leftTime.isNullOrBlank() && leftTime.take(16) == rightTime?.take(16)
        val sharedMaterials = left.materials.intersect(right.materials.toSet()).isNotEmpty()
        val titleOverlap = tokenOverlap(left.title, right.title) >= 0.55
        return sameTime || sharedMaterials || titleOverlap
    }

    private fun titleActionSignals(title: String): Set<String> = buildSet {
        if ("实验报告" in title) add("lab_report")
        if ("报名" in title || "报名表" in title) add("registration")
        if ("会议" in title) add("meeting")
        if ("汇报" in title || "PPT" in title) add("report")
    }

    private fun tokenOverlap(left: String, right: String): Double {
        val a = left.normalizedForMatch()
        val b = right.normalizedForMatch()
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a.contains(b) || b.contains(a)) return 1.0
        val gramsA = a.windowed(2).toSet()
        val gramsB = b.windowed(2).toSet()
        if (gramsA.isEmpty() || gramsB.isEmpty()) return 0.0
        return gramsA.intersect(gramsB).size.toDouble() / minOf(gramsA.size, gramsB.size)
    }

    fun updateDraft(card: ActionCard) {
        locallyEditedDraftIds += card.id
        _uiState.update { state ->
            state.copy(
                draftCards = state.draftCards.map { if (it.id == card.id) card else it },
                actionCandidates = state.actionCandidates.map { candidate ->
                    if (candidate.card.id == card.id) {
                        candidate.copy(card = card, userLockedFields = candidate.userLockedFields + "edited")
                    } else {
                        candidate
                    }
                },
            )
        }
    }

    fun removeDraft(id: String) {
        _uiState.update { state ->
            state.copy(
                draftCards = state.draftCards.filterNot { it.id == id },
                actionCandidates = state.actionCandidates.filterNot { it.card.id == id },
                selectedDraftIds = state.selectedDraftIds - id,
            )
        }
    }

    fun addManualDraftFromCurrentText() {
        val state = _uiState.value
        val evidence = state.ocrText
            .lines()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: state.screenshotPromptSummary
            ?: "手动添加行动事项"
        val card = ActionCard(
            cardType = CardTypes.TASK,
            title = state.screenshotPromptSummary
                ?.substringBefore(" · ")
                ?.takeIf { it.isNotBlank() }
                ?: "手动补全行动事项",
            summary = evidence.take(120),
            needConfirm = listOf("标题", "时间", "地点/平台"),
            sourceText = state.ocrText,
            evidenceSummary = listOf("用户从空结果恢复入口手动创建候选卡"),
        )
        _uiState.update {
            it.copy(
                draftCards = listOf(card),
                actionCandidates = listOf(
                    ActionCandidate(
                        card = card,
                        selected = true,
                        confidenceBand = "low",
                        evidenceSummary = card.evidenceSummary,
                        sourceSpan = card.sourceText.take(180),
                        userLockedFields = emptySet(),
                    )
                ),
                selectedDraftIds = setOf(card.id),
                screenshotWorkflowStage = ScreenshotWorkflowStage.CANDIDATES_READY,
                error = "已创建手动候选卡，请补全关键字段后确认",
            )
        }
    }

    fun toggleDraftSelection(id: String) {
        _uiState.update { state ->
            val nextSelectedIds = if (id in state.selectedDraftIds) {
                state.selectedDraftIds - id
            } else {
                state.selectedDraftIds + id
            }
            state.copy(
                selectedDraftIds = nextSelectedIds,
                actionCandidates = state.actionCandidates.map { candidate ->
                    if (candidate.card.id == id) candidate.copy(selected = id in nextSelectedIds) else candidate
                },
            )
        }
    }

    fun selectAllDrafts() {
        _uiState.update { state ->
            val allIds = state.draftCards.map { it.id }.toSet()
            state.copy(
                selectedDraftIds = allIds,
                actionCandidates = state.actionCandidates.map { it.copy(selected = it.card.id in allIds) },
            )
        }
    }

    fun refineDraftWithAi(instruction: String) {
        viewModelScope.launch {
            val state = _uiState.value
            val text = state.ocrText.ifBlank {
                state.draftCards.joinToString("\n") { card -> card.sourceText.ifBlank { card.title } }
            }
            if (text.isBlank()) {
                _uiState.update { it.copy(error = "没有可供 AI 继续完善的截图文本") }
                return@launch
            }
            if (state.draftCards.isEmpty()) {
                _uiState.update {
                    it.copy(
                        error = "没有可供 AI 完善的候选卡",
                        aiRefinementStatus = "请先生成候选卡，再让 AI 继续完善",
                    )
                }
                return@launch
            }
            if (state.selectedDraftIds.isEmpty()) {
                _uiState.update {
                    it.copy(
                        error = "请至少选择一张候选卡，再让 AI 继续完善",
                        aiRefinementStatus = "请至少选择一张候选卡，再让 AI 继续完善",
                    )
                }
                return@launch
            }
            val selectedIds = state.selectedDraftIds.toList()
            _uiState.update {
                it.copy(
                    loading = true,
                    error = null,
                    aiRefinementStatus = "AI 正在观察证据、选择工具并生成可确认建议",
                )
            }
            val remote = if (state.traceId.isNotBlank() && state.revision > 0) {
                runCatching {
                    withTimeoutOrNull(18_000) {
                        repository.refineWithReact(
                            runId = state.traceId,
                            baseRevision = state.revision,
                            instruction = instruction,
                            selectedCardIds = selectedIds,
                        )
                    } ?: throw IllegalStateException("云端 ReAct 响应超时，已保留当前候选")
                }
            } else {
                Result.failure(IllegalStateException("未连接云端 ReAct 工作流"))
            }
            remote.onSuccess { result ->
                applyAnalyzeResult(
                    result.copy(
                        warnings = listOf("AI 已按 ReAct 范式重新检查：观察证据、调用工具、回写建议") + result.warnings,
                    )
                )
                return@launch
            }
            val fallback = runCatching {
                repository.analyzeTextLocal(
                    text = state.draftCards
                        .filter { it.id in selectedIds }
                        .joinToString("\n") { card -> card.sourceText.ifBlank { card.title } }
                        .ifBlank { text },
                    screenshotTime = OffsetDateTime.now(ZoneOffset.ofHours(8)).toString(),
                    enginePrefix = "local-react",
                )
            }.getOrElse { error ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        aiRefinementStatus = "AI 完善失败",
                        error = "继续完善失败：${error.message ?: "请稍后重试"}",
                    )
                }
                return@launch
            }
            val proposed = fallback.cards
            _uiState.update { current ->
                val updatedDrafts = current.draftCards.map { card ->
                    if (card.id !in selectedIds) {
                        card
                    } else {
                        proposed.firstOrNull { incoming -> sameActionCandidate(card, incoming) }
                            ?.let { incoming -> fillEmptyFields(card, incoming) }
                            ?: card
                    }
                }
                current.copy(
                    loading = false,
                    draftCards = updatedDrafts,
                    actionCandidates = current.actionCandidates.map { candidate ->
                        val updated = updatedDrafts.firstOrNull { it.id == candidate.card.id } ?: candidate.card
                        candidate.copy(card = updated)
                    },
                    engine = EngineLabels.withPrefix(fallback.engine, "react-fallback"),
                    warnings = listOf("云端 ReAct 不可用，已用端侧规则复检选中的候选卡") + fallback.warnings,
                    reactSuggestions = listOf("本次为端侧规则复检；配置 HTTPS Workflow 网关后可使用 vivo 模型继续完善"),
                    aiRefinementStatus = "端侧规则已复检选中的候选卡",
                )
            }
        }
    }

    fun ignoreScreenshotWorkflow(onDone: () -> Unit = {}) {
        _uiState.update {
            it.copy(
                draftCards = emptyList(),
                actionCandidates = emptyList(),
                selectedDraftIds = emptySet(),
                previewActions = emptyList(),
                screenshotWorkflowStage = ScreenshotWorkflowStage.IGNORED,
                loading = false,
                error = null,
            )
        }
        onDone()
    }

    fun confirmDrafts(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.draftCards.isNotEmpty() && state.selectedDraftIds.isEmpty()) {
                _uiState.update { it.copy(error = "请至少选择一张候选卡后再创建") }
                return@launch
            }
            val drafts = state.draftCards.filter { card ->
                card.id in state.selectedDraftIds
            }
            if (drafts.isEmpty()) {
                _uiState.update { it.copy(error = "没有需要确认的行动卡") }
                return@launch
            }
            val blockingReasons = drafts.mapNotNull { it.creationBlockingReason() }
            if (blockingReasons.isNotEmpty()) {
                _uiState.update {
                    it.copy(error = blockingReasons.distinct().take(3).joinToString("\n"))
                }
                return@launch
            }
            val selectedIds = drafts.map { it.id }.toSet()
            val shouldConfirmRemoteWorkflowBeforeLocalSave =
                state.traceId.isNotBlank() &&
                    state.workflowStatus in setOf("queued", "running", "awaiting_review") &&
                    state.settings.preferCloudModel
            val cardsToSave = if (
                shouldConfirmRemoteWorkflowBeforeLocalSave
            ) {
                _uiState.update { it.copy(loading = true, error = null) }
                val resumed = runCatching {
                    repository.reviewAndConfirm(
                        state.traceId,
                        state.revision,
                        drafts,
                        state.fieldVersions,
                    )
                }.getOrElse { error ->
                    _uiState.update {
                        it.copy(loading = false, error = "审核提交失败：${error.message ?: "未知错误"}")
                    }
                    return@launch
                }
                applyAnalyzeResult(resumed)
                if (resumed.workflowStatus != "completed") {
                    _uiState.update { it.copy(error = "仍有字段需要确认") }
                    return@launch
                }
                resumed.cards.filter { it.id in selectedIds }.ifEmpty { drafts }
            } else {
                drafts
            }
            val syncWarnings = mutableListOf<String>()
            val confirmationMessages = mutableListOf<String>()
            cardsToSave.forEach { card ->
                val saveResult = repository.saveConfirmed(card)
                val saved = saveResult.card
                saveResult.syncError?.let(syncWarnings::add)
                val reminderResult = scheduler.schedule(saved)
                if (reminderResult.scheduled) {
                    confirmationMessages += reminderResult.message
                } else {
                    syncWarnings += reminderResult.message
                }
                if (_uiState.value.settings.calendarSync) {
                    val calendarResult = calendarSyncer.insertIfPermitted(saved)
                    if (calendarResult.failed) {
                        syncWarnings += calendarResult.message
                    } else if (calendarResult.synced) {
                        confirmationMessages += calendarResult.message
                    }
                }
            }
            _uiState.update {
                it.copy(
                    draftCards = emptyList(),
                    actionCandidates = emptyList(),
                    selectedDraftIds = emptySet(),
                    previewActions = emptyList(),
                    ocrText = "",
                    engine = "",
                    traceId = "",
                    fallbackReason = null,
                    warnings = emptyList(),
                    workflowStatus = "",
                    pendingAction = null,
                    nodeTrace = emptyList(),
                    revision = 0,
                    resultStage = "",
                    overallConfidence = 0.0,
                    route = "",
                    timeToFirstDraftMs = null,
                    timeToFinalMs = null,
                    activeAgents = emptyList(),
                    decisionReasons = emptyList(),
                    riskLevel = "low",
                    validationErrors = emptyList(),
                    fieldConflicts = emptyList(),
                    fieldVersions = emptyMap(),
                    modelEnhancementStatus = "not_configured",
                    ocrEnhancementStatus = "not_configured",
                    imageGenerationStatus = "not_configured",
                    reactSuggestions = emptyList(),
                    aiRefinementStatus = null,
                    screenshotGateReason = null,
                    screenshotDeadlineHint = null,
                    screenshotPromptSummary = null,
                    screenshotConfidenceBand = null,
                    screenshotScenarioType = null,
                    screenshotPrimaryEvidence = emptyList(),
                    screenshotWorkflowStage = ScreenshotWorkflowStage.CONFIRMED,
                    ocrArbitrationReason = null,
                    error = (syncWarnings.ifEmpty { confirmationMessages })
                        .distinct()
                        .takeIf { messages -> messages.isNotEmpty() }
                        ?.joinToString("\n"),
                )
            }
            locallyEditedDraftIds.clear()
            onDone()
        }
    }

    fun completeCard(id: String) {
        viewModelScope.launch {
            repository.complete(id)
        }
    }

    fun updateCard(card: ActionCard) {
        viewModelScope.launch {
            repository.update(card)
            scheduler.schedule(card)
        }
    }

    fun archiveCard(id: String) {
        viewModelScope.launch {
            repository.archive(id)
        }
    }

    fun updateSettings(settings: AppSettings) {
        settingsRepository.update(settings)
    }

    fun syncFromServer() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { repository.syncFromServer() }
                .onSuccess {
                    _uiState.update { it.copy(loading = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = "云端同步失败：${error.message ?: "未知错误"}",
                        )
                    }
                }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(connectionStatus = "检测中…", error = null) }
            runCatching { repository.testConnection() }
                .onSuccess { message ->
                    _uiState.update { it.copy(connectionStatus = message) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            connectionStatus = "离线或地址不可达",
                            error = "连接测试失败：${error.message ?: "未知错误"}",
                        )
                    }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun confidenceBand(value: Double): String = when {
        value >= 0.82 -> "high"
        value >= 0.58 -> "medium"
        else -> "low"
    }

    private fun userVisibleWorkflowError(error: Throwable, prefix: String): String {
        val message = error.message.orEmpty()
        return when {
            "Parameter specified as non-null is null" in message ||
                "AnalyzeResult.<init>" in message ||
                "cacheStatus" in message ||
                "工作流事件解析失败" in message ->
                "$prefix：工作流事件解析失败，请重试或查看诊断"
            else -> "$prefix：${message.ifBlank { "请稍后重试或查看诊断" }}"
        }
    }
}

private fun String.normalizedForMatch(): String =
    lowercase().replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "")

private fun ActionCard.creationBlockingReason(): String? {
    if (title.isBlank()) return "存在标题为空的行动卡，请先补全标题"
    if (title in setOf("相关日程", "待办事项", "相关事项", "日程提醒", "行动事项")) {
        return "存在标题过于泛化的行动卡，请先让 AI 继续完善或手动修改"
    }
    if (needConfirm.isNotEmpty()) {
        return "仍有字段需要确认：${needConfirm.joinToString("、")}"
    }
    if (cardType == "promise" && deadline.isNullOrBlank() && startTime.isNullOrBlank()) {
        return "承诺类行动卡需要补全执行时间后才能创建"
    }
    return null
}
