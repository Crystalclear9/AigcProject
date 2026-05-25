package com.suishouban.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.AnalyzeResult
import com.suishouban.app.data.repository.AppSettings
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUiState(
    val cards: List<ActionCard> = emptyList(),
    val draftCards: List<ActionCard> = emptyList(),
    val previewActions: List<String> = emptyList(),
    val ocrText: String = "",
    val engine: String = "",
    val traceId: String = "",
    val fallbackReason: String? = null,
    val warnings: List<String> = emptyList(),
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

    fun analyzeImage(uri: Uri, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            val screenshotTime = OffsetDateTime.now(ZoneOffset.ofHours(8)).toString()
            val cloudResult = runCatching { repository.analyzeImage(uri, screenshotTime) }.getOrNull()
            if (cloudResult != null) {
                applyAnalyzeResult(cloudResult)
                onDone()
                return@launch
            }
            _uiState.update {
                it.copy(
                    error = "云端图片识别不可用，已切换到本地 OCR",
                )
            }

            val text = runCatching { ocr.recognize(getApplication(), uri) }
                .getOrElse { error ->
                    _uiState.update { it.copy(loading = false, error = "图片识别失败：${error.message ?: "请换一张截图"}") }
                    return@launch
                }
            analyzeTextInternal(
                text = text,
                onDone = onDone,
                screenshotTime = screenshotTime,
                enginePrefix = "mlkit",
                extraWarnings = listOf("云端图片识别不可用，已切换到 ML Kit 本地 OCR"),
            )
        }
    }

    fun analyzeText(text: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            analyzeTextInternal(text, onDone)
        }
    }

    private suspend fun analyzeTextInternal(
        text: String,
        onDone: () -> Unit,
        screenshotTime: String? = null,
        enginePrefix: String? = null,
        extraWarnings: List<String> = emptyList(),
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
        applyAnalyzeResult(result.copy(warnings = extraWarnings + result.warnings))
        onDone()
    }

    private fun applyAnalyzeResult(result: AnalyzeResult) {
        _uiState.update {
            it.copy(
                loading = false,
                ocrText = result.ocrText,
                draftCards = result.cards,
                previewActions = result.previewActions,
                engine = result.engine,
                traceId = result.traceId,
                fallbackReason = result.fallbackReason,
                warnings = result.warnings,
            )
        }
    }

    fun updateDraft(card: ActionCard) {
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
            drafts.forEach { card ->
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
                )
            }
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
