package com.suishouban.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.AnalyzeResult
import com.suishouban.app.data.repository.AppSettings
import com.suishouban.app.data.repository.EngineLabels
import com.suishouban.app.data.model.NodeTrace
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async

data class AppUiState(
    val cards: List<ActionCard> = emptyList(),
    val draftCards: List<ActionCard> = emptyList(),
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
    private val locallyEditedDraftIds = mutableSetOf<String>()

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
    }

    fun analyzeImage(
        uri: Uri,
        notifyWhenEmpty: Boolean = true,
        onDone: (Boolean) -> Unit = {},
    ) {
        locallyEditedDraftIds.clear()
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
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
                        it.copy(loading = false, error = "Workflow event stream failed: ${error.message ?: "unknown"}")
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
            analyzeTextInternal(
                text = text,
                onDone = onDone,
                screenshotTime = screenshotTime,
                enginePrefix = "mlkit",
                extraWarnings = listOf("Cloud workflow unavailable; using local OCR"),
                notifyWhenEmpty = notifyWhenEmpty,
            )
        }
    }

    fun analyzeText(text: String, onDone: (Boolean) -> Unit = {}) {
        locallyEditedDraftIds.clear()
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            analyzeTextInternal(text, onDone, notifyWhenEmpty = true)
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
                _uiState.update { it.copy(loading = false, error = "Workflow event stream failed: ${error.message}") }
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
            val mergedDrafts = result.cards.map { incoming ->
                if (incoming.id in locallyEditedDraftIds) localDrafts[incoming.id] ?: incoming else incoming
            }
            it.copy(
                loading = false,
                ocrText = result.ocrText,
                draftCards = mergedDrafts,
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
                error = if (!hasCards && notifyWhenEmpty) "未识别到明确行动事项" else it.error,
            )
        }
        return hasCards
    }

    fun updateDraft(card: ActionCard) {
        locallyEditedDraftIds += card.id
        _uiState.update { state ->
            state.copy(draftCards = state.draftCards.map { if (it.id == card.id) card else it })
        }
    }

    fun removeDraft(id: String) {
        _uiState.update { state ->
            state.copy(draftCards = state.draftCards.filterNot { it.id == id })
        }
    }

    fun confirmDrafts(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val drafts = _uiState.value.draftCards
            if (drafts.isEmpty()) {
                _uiState.update { it.copy(error = "没有需要确认的行动卡") }
                return@launch
            }
            val cardsToSave = if (
                _uiState.value.workflowStatus in setOf("queued", "running", "awaiting_review") &&
                _uiState.value.traceId.isNotBlank()
            ) {
                val resumed = runCatching {
                    repository.resumeWithReview(_uiState.value.traceId, drafts)
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
                resumed.cards
            } else {
                drafts
            }
            cardsToSave.forEach { card ->
                val saved = repository.saveConfirmed(card)
                scheduler.schedule(saved)
                if (_uiState.value.settings.calendarSync) {
                    calendarSyncer.insertIfPermitted(saved)
                }
            }
            _uiState.update {
                it.copy(
                    draftCards = emptyList(),
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
            repository.syncFromServer()
            _uiState.update { it.copy(loading = false) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
