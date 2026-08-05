package com.suishouban.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.ActionCandidate
import com.suishouban.app.data.model.ActionPlan
import com.suishouban.app.data.model.AnalyzeResult
import com.suishouban.app.data.model.AiConnectionMode
import com.suishouban.app.data.model.CardTypes
import com.suishouban.app.data.model.candidateIdentity
import com.suishouban.app.data.model.UserProfile
import com.suishouban.app.data.model.UserProfileContext
import com.suishouban.app.data.model.toContext
import com.suishouban.app.data.repository.UserProfileRepository
import com.suishouban.app.data.repository.AppSettings
import com.suishouban.app.data.repository.EngineLabels
import com.suishouban.app.data.model.NodeTrace
import com.suishouban.app.domain.ocr.OcrCandidate
import com.suishouban.app.domain.ocr.OcrEvidenceBlock
import com.suishouban.app.domain.workflow.ConfirmationCoordinator
import com.suishouban.app.domain.workflow.OcrCoordinator
import com.suishouban.app.domain.workflow.PriorityCoordinator
import com.suishouban.app.domain.screenshot.ScreenshotWorkflowStage
import com.suishouban.app.mascot.MascotCompletionEvent
import com.suishouban.app.mascot.MascotRefreshPolicy
import com.suishouban.app.mascot.MascotState
import com.suishouban.app.mascot.MascotStateResolver
import com.suishouban.app.notification.NotificationCandidateUiModel
import com.suishouban.app.notification.NotificationDraftAssociation
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import com.suishouban.app.data.model.OcrEnhancementPolicy
import com.suishouban.app.domain.TextIntegrity
import com.suishouban.app.domain.EvidenceSummaryComposer

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
    val workflowPhase: String = "received",
    val evidenceStatus: String = "trusted",
    val draftStatus: String = "not_started",
    val reviewItems: List<Map<String, Any?>> = emptyList(),
    val effectStatus: String = "not_started",
    val blockedReasons: List<String> = emptyList(),
    val checkpointId: String? = null,
    val commandIds: List<String> = emptyList(),
    val evidenceEnvelopes: List<Map<String, Any?>> = emptyList(),
    val fieldEvidence: List<Map<String, Any?>> = emptyList(),
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
    val ocrQualityReport: com.suishouban.app.data.model.OcrQualityReport? = null,
    val ocrReviewReasons: List<String> = emptyList(),
    val imageGenerationStatus: String = "not_configured",
    val reactSuggestions: List<String> = emptyList(),
    val agentContractVersion: String = "agent-contract-v2",
    val agentOutputs: List<Map<String, Any?>> = emptyList(),
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
    val hasProviderApiKey: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val settings: AppSettings = AppSettings(),
    val userProfile: UserProfile = UserProfileRepository.genericProfile(),
    val actionPlans: List<ActionPlan> = emptyList(),
    /** Batch workspace for the current draft-confirm surface: null = 个人 (today's behavior). */
    val draftTeamId: String? = null,
    /** True while [draftTeamId] is an unedited AI suggestion — cleared on any manual choice. */
    val draftTeamSuggested: Boolean = false,
    /** Inline quiet error shown on the confirm surface when a team batch cannot reach the server. */
    val teamPushError: String? = null,
)

/** One row of the team list: everything the screen shows, nothing more. */
data class TeamSummary(
    val id: String,
    val name: String,
    val memberCount: Int,
    val myRole: String,
    val inviteCode: String = "",
)

/** Team collaboration state kept separate from [AppUiState] so the main stream stays lean. */
data class TeamUiState(
    val teams: List<TeamSummary> = emptyList(),
    val nickname: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Live snapshot of one team's detail screen. [isStale] flips quietly when the last poll failed;
 * the previous good summary is kept so the screen never blanks out mid-session.
 */
data class TeamDetailUiState(
    val teamId: String? = null,
    val summary: com.suishouban.app.data.model.TeamDetailSummary? = null,
    val isStale: Boolean = false,
)

/** One quiet calendar mark: a milestone due date with the owning team's name resolved. */
data class TeamMilestoneMark(
    val id: String,
    val teamName: String,
    val title: String,
    val dueDate: String,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SuiShouBanApp
    private val repository = app.cardRepository
    private val settingsRepository = app.settingsRepository
    private val ocr = app.textRecognitionService
    private val scheduler = app.reminderScheduler
    private val notificationCandidateRepository = app.notificationCandidateRepository
    private val userProfileRepository = app.userProfileRepository
    private val cardRefinementRepository = app.cardRefinementRepository
    private val teamRepository = app.teamRepository
    private val workflowDao = com.suishouban.app.data.local.AppDatabase.get(application).workflowDao()
    private var activeIntakeSessionId: String? = null
    private val locallyEditedDraftIds = mutableSetOf<String>()
    /** True once the user picked 归属 by hand; blocks any later AI re-suggestion for this batch. */
    private var draftTeamUserChoice = false
    private var ignoreActiveWorkflowRestore: Boolean = false
    private var restoreWorkflowJob: Job? = null
    private val cardReplanJobs = mutableMapOf<String, Job>()
    private val mascotResolver = MascotStateResolver()
    private val _mascotCompletionEvent = MutableStateFlow<MascotCompletionEvent?>(null)
    private val _mascotRefreshTick = MutableStateFlow(0L)
    private val _mascotInteractions = MutableSharedFlow<MascotCompletionEvent>(extraBufferCapacity = 1)
    private var notificationDraftAssociation: NotificationDraftAssociation? = null

    private val _uiState = MutableStateFlow(
        AppUiState(
            settings = settingsRepository.settings.value,
            hasProviderApiKey = app.providerSecretStore.hasApiKey(),
        )
    )
    val uiState: StateFlow<AppUiState> = _uiState
    val notificationCandidates: StateFlow<List<NotificationCandidateUiModel>> =
        notificationCandidateRepository.observeActive()
            .map { NotificationCandidateUiModel.from(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pendingNotificationCandidateCount: StateFlow<Int> = notificationCandidates
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // Loading/error are transient view concerns; Room-backed rows and settings stay authoritative.
    private val teamOperationState = MutableStateFlow(TeamUiState())
    val teamUiState: StateFlow<TeamUiState> = combine(
        teamRepository.observeTeams(),
        workflowDao.observeMemberCounts(),
        settingsRepository.settings,
        teamOperationState,
    ) { teams, memberCounts, settings, operation ->
        val countByWorkspace = memberCounts.associate { it.workspaceId to it.memberCount }
        TeamUiState(
            teams = teams.map { workspace ->
                TeamSummary(
                    id = workspace.id,
                    name = workspace.name,
                    memberCount = countByWorkspace[workspace.id] ?: 0,
                    myRole = workspace.myRole,
                    inviteCode = workspace.inviteCode,
                )
            },
            nickname = settings.userNickname,
            loading = operation.loading,
            error = operation.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TeamUiState())

    // Eagerly shared: the AI workspace suggestion reads this synchronously when drafts arrive,
    // before any screen has started collecting it.
    val teamMemberOptions: StateFlow<List<com.suishouban.app.domain.team.TeamMemberOption>> =
        workflowDao.observeAllMembers()
            .map { rows ->
                rows.map { row ->
                    com.suishouban.app.domain.team.TeamMemberOption(
                        teamId = row.workspaceId,
                        userId = row.id.substringAfter(':'),
                        nickname = row.displayName,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Milestone due dates mirrored from team summaries, rendered as quiet calendar marks. */
    val milestoneMarks: StateFlow<List<TeamMilestoneMark>> = combine(
        workflowDao.observeMilestones(),
        teamRepository.observeTeams(),
    ) { milestones, teams ->
        val names = teams.associate { it.id to it.name }
        milestones.mapNotNull { milestone ->
            val due = milestone.dueDate?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            TeamMilestoneMark(
                id = milestone.id,
                teamName = names[milestone.teamId] ?: "团队",
                title = milestone.title,
                dueDate = due,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * A single state stream feeds both the in-app companion and the system overlay. Completion is
     * deliberately an event instead of a persisted card property, so it cannot mask urgent work.
     */
    val mascotState: StateFlow<MascotState> = combine(
        _uiState,
        _mascotCompletionEvent,
        _mascotRefreshTick,
    ) { state, completion, _ ->
        mascotResolver.resolve(
            cards = state.cards,
            draftCards = state.draftCards,
            workflowStatus = state.workflowStatus,
            completionEvent = completion,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = mascotResolver.resolve(emptyList(), null),
    )

    /** One-shot completion feedback for surfaces that need to trigger a single celebration. */
    val mascotInteractions: SharedFlow<MascotCompletionEvent> = _mascotInteractions.asSharedFlow()

    init {
        viewModelScope.launch {
            userProfileRepository.initializeIfNeeded()
            userProfileRepository.observe().collect { profile ->
                _uiState.update { it.copy(userProfile = profile) }
            }
        }
        viewModelScope.launch { notificationCandidateRepository.deleteExpired() }
        viewModelScope.launch {
            while (isActive) {
                val delayMillis = MascotRefreshPolicy.nextDelayMillis(
                    deadlines = _uiState.value.cards.map { it.deadline },
                    now = Instant.now(),
                )
                delay(delayMillis)
                // Time alone can cross a DDL; emit even when Room and workflow data are unchanged.
                _mascotRefreshTick.update { it + 1L }
            }
        }
        viewModelScope.launch {
            // Keep the system overlay current even after the activity composition stops collecting.
            mascotState.collect(app.mascotStateStore::update)
        }
        viewModelScope.launch {
            repository.observeAll().collect { cards ->
                _uiState.update { it.copy(cards = cards) }
            }
        }
        viewModelScope.launch {
            cardRefinementRepository.observeAcceptedPlans().collect { plans ->
                _uiState.update { it.copy(actionPlans = plans) }
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

    fun beginFreshScreenshotPrompt(intakeSessionId: String? = null) {
        activeIntakeSessionId = intakeSessionId
        clearNotificationDraftAssociation()
        ignoreActiveWorkflowRestore = true
        restoreWorkflowJob?.cancel()
        restoreWorkflowJob = null
        repository.clearActiveWorkflow()
        locallyEditedDraftIds.clear()
        resetDraftWorkspace()
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
        if (!_uiState.value.settings.importSources.galleryImages) {
            _uiState.update { it.copy(error = "相册与图片导入已在设置中关闭") }
            onDone(false)
            return
        }
        clearNotificationDraftAssociation()
        locallyEditedDraftIds.clear()
        resetDraftWorkspace()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = true,
                    error = null,
                    draftCards = emptyList(),
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
            val localOcr = async {
                runCatching { ocr.recognizeCandidates(getApplication(), uri) }
                    .getOrDefault(emptyList())
            }
            val cloudResult = runCatching {
                repository.analyzeImage(uri, screenshotTime, planningProfileContext())
            }.getOrNull()
            if (cloudResult != null) {
                val candidateSubmit = async {
                    localOcr.await()
                        .sortedByDescending { result ->
                            com.suishouban.app.domain.ocr.OcrQualityScorer.score(
                                result.text,
                                result.blocks.size,
                            )
                        }
                        .take(2)
                        .forEach { result ->
                            runCatching {
                                repository.submitOcrCandidate(
                                    cloudResult.traceId,
                                    result.text,
                                    result,
                                )
                            }
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
            val localResults = localOcr.await()
            val localCandidates = localResults.mapIndexed { index, result ->
                    OcrCandidate(
                        engine = "mlkit:${result.variant}",
                        text = result.text,
                        blocks = result.blocks.size,
                        evidenceBlocks = result.blocks.map { block ->
                            OcrEvidenceBlock(
                                text = block.text,
                                left = block.left.toDouble(),
                                top = block.top.toDouble(),
                                right = block.right.toDouble(),
                                bottom = block.bottom.toDouble(),
                                readingOrder = block.readingOrder,
                            )
                        },
                        arrivedAtMs = index.toLong(),
                    )
                }
            val ocrPolicy = _uiState.value.settings.ocrEnhancementPolicy
            val shouldUseDirectOcr = ocrPolicy == OcrEnhancementPolicy.ALWAYS_COMPARE ||
                (ocrPolicy == OcrEnhancementPolicy.LOW_QUALITY &&
                    localCandidates.maxOfOrNull { it.qualityScore }?.let { it < 0.72 } != false)
            val directCandidate = if (shouldUseDirectOcr) {
                repository.recognizeImageDirect(uri)
            } else null
            if (localCandidates.isEmpty() && directCandidate == null) {
                _uiState.update { it.copy(loading = false, error = "图片识别失败，请重试或检查 OCR 设置") }
                return@launch
            }
            val arbitration = OcrCoordinator.adjudicate(
                localCandidates + listOfNotNull(directCandidate),
            ) ?: run {
                _uiState.update { it.copy(loading = false, error = "Image recognition failed") }
                return@launch
            }
            if (arbitration.requiresReview) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        ocrText = arbitration.selectedCandidate.text,
                        ocrArbitrationReason = arbitration.reason,
                        workflowStatus = "awaiting_ocr_review",
                        pendingAction = "resolve_ocr",
                        error = "识别结果不够可靠，请检查文字、重新截图或手动修正后再生成卡片",
                    )
                }
                onDone(false)
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
        if (!_uiState.value.settings.importSources.text) {
            _uiState.update { it.copy(error = "文字导入已在设置中关闭") }
            onDone(false)
            return
        }
        clearNotificationDraftAssociation()
        locallyEditedDraftIds.clear()
        resetDraftWorkspace()
        viewModelScope.launch {
            val current = _uiState.value
            if (
                current.workflowStatus == "awaiting_ocr_review" &&
                text.isNotBlank()
            ) {
                _uiState.update { it.copy(loading = true, error = null) }
                if (
                    current.settings.aiConnectionMode != AiConnectionMode.WORKFLOW_GATEWAY ||
                    current.settings.apiBaseUrl.isBlank() ||
                    current.traceId.isBlank()
                ) {
                    _uiState.update {
                        it.copy(
                            workflowStatus = "",
                            pendingAction = null,
                            draftCards = emptyList(),
                            actionCandidates = emptyList(),
                            selectedDraftIds = emptySet(),
                        )
                    }
                    analyzeTextInternal(
                        text = text,
                        onDone = onDone,
                        enginePrefix = "user-corrected",
                        extraWarnings = listOf("已根据你修正的文字重新分析"),
                        notifyWhenEmpty = true,
                    )
                    return@launch
                }
                runCatching {
                    repository.resolveOcr(current.traceId, text)
                }.onSuccess { resumed ->
                    applyAnalyzeResult(resumed)
                    runCatching {
                        repository.followWorkflow(current.traceId) { update ->
                            applyAnalyzeResult(update)
                        }
                    }
                    onDone(_uiState.value.draftCards.isNotEmpty())
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = userVisibleWorkflowError(error, "无法提交修正后的文字"),
                        )
                    }
                    onDone(false)
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    loading = true,
                    error = null,
                    draftCards = emptyList(),
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

    /** Notification text remains local and becomes ordinary drafts only after this explicit tap. */
    fun analyzeNotificationCandidate(id: String, onDone: (Boolean) -> Unit = {}) {
        // Stop any prior workflow stream before establishing the notification's local-only review.
        beginFreshScreenshotPrompt()
        viewModelScope.launch {
            val candidate = notificationCandidateRepository.findById(id)
            if (candidate == null) {
                _uiState.update { it.copy(error = "这条通知草稿已过期") }
                onDone(false)
                return@launch
            }
            val text = listOf(candidate.title, candidate.body).filter { it.isNotBlank() }.joinToString("\n")
            locallyEditedDraftIds.clear()
            resetDraftWorkspace()
            _uiState.update {
                it.copy(
                    loading = true,
                    error = null,
                    ocrText = text,
                    draftCards = emptyList(),
                    actionCandidates = emptyList(),
                    selectedDraftIds = emptySet(),
                    previewActions = emptyList(),
                    screenshotWorkflowStage = null,
                    reactSuggestions = emptyList(),
                    aiRefinementStatus = null,
                )
            }
            // Notification originals are a local-only privacy boundary and never enter Workflow APIs.
            val result = runCatching {
                repository.analyzeTextLocal(text, enginePrefix = "notification-local")
            }.getOrElse { error ->
                _uiState.update {
                    it.copy(loading = false, error = "通知事项生成失败：${error.message ?: "未知错误"}")
                }
                onDone(false)
                return@launch
            }
            val hasDrafts = applyAnalyzeResult(result, notifyWhenEmpty = true)
            if (hasDrafts) {
                notificationDraftAssociation = NotificationDraftAssociation(
                    candidateId = id,
                    draftIds = result.cards.map(ActionCard::id).toSet(),
                )
            }
            onDone(hasDrafts)
        }
    }

    fun rejectNotificationCandidate(id: String) {
        viewModelScope.launch {
            notificationCandidateRepository.delete(id)
            if (notificationDraftAssociation?.candidateId == id) clearNotificationDraftAssociation()
        }
    }

    fun pruneNotificationCandidates() {
        viewModelScope.launch { notificationCandidateRepository.deleteExpired() }
    }

    fun clearOpenedNotificationCandidate() {
        clearNotificationDraftAssociation()
    }

    private fun clearNotificationDraftAssociation() {
        notificationDraftAssociation = null
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
        resetDraftWorkspace()
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
        resetDraftWorkspace()
        viewModelScope.launch {
            val warnings = buildList {
                gateReason?.takeIf { it.isNotBlank() }?.let { add("截图判定：$it") }
                deadlineHint?.takeIf { it.isNotBlank() }?.let { add("候选截止：$it") }
            }
            _uiState.update {
                it.copy(
                    loading = true,
                    error = null,
                    draftCards = emptyList(),
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
                        profileContext = planningProfileContext(),
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
        val result = runCatching {
            repository.analyzeText(
                text,
                screenshotTime,
                enginePrefix,
                profileContext = planningProfileContext(),
            )
        }
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
            val incomingDrafts = result.cards.map { card ->
                if (card.sourceSessionId == null && activeIntakeSessionId != null) {
                    card.copy(sourceSessionId = activeIntakeSessionId)
                } else {
                    card
                }
            }
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
            val previousSelectedIdentities = it.draftCards
                .filter { card -> card.id in previousSelections }
                .map { card -> card.candidateIdentity() }
                .toSet()
            val hadPriorCandidates = it.draftCards.isNotEmpty() || it.actionCandidates.isNotEmpty()
            val nextSelectedIds = when {
                !hasVisibleCards -> emptySet()
                previousSelections.isEmpty() && !hadPriorCandidates -> mergedDrafts.map { card -> card.id }.toSet()
                previousSelections.isEmpty() -> emptySet()
                else -> mergedDrafts
                    .filter { card ->
                        card.id in previousSelections ||
                            card.candidateIdentity() in previousSelectedIdentities
                    }
                    .map { card -> card.id }
                    .toSet()
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
                workflowPhase = result.workflowPhase,
                evidenceStatus = result.evidenceStatus,
                draftStatus = result.draftStatus,
                reviewItems = result.reviewItems,
                effectStatus = result.effectStatus,
                blockedReasons = result.blockedReasons,
                checkpointId = result.checkpointId,
                commandIds = result.commandIds,
                evidenceEnvelopes = result.evidenceEnvelopes,
                fieldEvidence = result.fieldEvidence,
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
                ocrQualityReport = result.ocrQualityReport,
                ocrReviewReasons = result.ocrReviewReasons,
                imageGenerationStatus = result.imageGenerationStatus,
                reactSuggestions = result.reactSuggestions,
                agentContractVersion = result.agentContractVersion,
                agentOutputs = result.agentOutputs,
                aiRefinementStatus = when {
                    result.reactSuggestions.isNotEmpty() -> "AI 已完成一次受控 ReAct 完善，建议可逐项确认"
                    result.engine.contains("react", ignoreCase = true) -> "AI 已重新检查候选草稿"
                    else -> it.aiRefinementStatus
                },
                screenshotWorkflowStage = if (hasVisibleCards) ScreenshotWorkflowStage.CANDIDATES_READY else it.screenshotWorkflowStage,
                error = when {
                    result.workflowStatus == "awaiting_ocr_review" ->
                        "识别结果存在乱码或关键字段冲突，请先复核文字"
                    !hasVisibleCards && notifyWhenEmpty -> "未识别到明确行动事项"
                    else -> it.error
                },
            )
        }
        maybeSuggestDraftTeam()
        return _uiState.value.draftCards.isNotEmpty()
    }

    /** User-driven 归属 switch on the confirm surface; also clears the AI-suggestion marker. */
    fun setDraftWorkspace(teamId: String?) {
        draftTeamUserChoice = true
        _uiState.update {
            it.copy(draftTeamId = teamId, draftTeamSuggested = false, teamPushError = null)
        }
    }

    private fun resetDraftWorkspace() {
        draftTeamUserChoice = false
        val state = _uiState.value
        if (state.draftTeamId != null || state.draftTeamSuggested || state.teamPushError != null) {
            _uiState.update {
                it.copy(draftTeamId = null, draftTeamSuggested = false, teamPushError = null)
            }
        }
    }

    /**
     * AI 建议: when the draft assignee hints or the source text match members of exactly one
     * local team, preselect that team quietly. Ties, no match, or a manual choice keep 个人.
     */
    private fun maybeSuggestDraftTeam() {
        if (draftTeamUserChoice) return
        val state = _uiState.value
        if (state.draftCards.isEmpty() || state.draftTeamId != null) return
        val suggestion = com.suishouban.app.domain.team.TeamWorkspacePolicy.suggestTeam(
            assigneeHints = state.draftCards.map { it.assigneeId },
            sourceText = buildString {
                append(state.ocrText)
                state.draftCards.forEach { card ->
                    append('\n')
                    append(card.sourceText)
                }
            },
            members = teamMemberOptions.value,
        ) ?: return
        _uiState.update { it.copy(draftTeamId = suggestion, draftTeamSuggested = true) }
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
            summary = TextIntegrity.chooseBetterSummary(local.summary, incoming.summary),
            deadline = local.deadline ?: incoming.deadline,
            startTime = local.startTime ?: incoming.startTime,
            endTime = local.endTime ?: incoming.endTime,
            location = local.location ?: incoming.location,
            materials = if (local.materials.isEmpty()) incoming.materials else local.materials,
            submitMethod = local.submitMethod ?: incoming.submitMethod,
            tags = if (local.tags.isEmpty()) incoming.tags else local.tags,
            // 分工公告 assignee hints arrive on remote cards; keep them when merging into the
            // local candidate floor so the team suggestion and nickname chips still work.
            assigneeId = local.assigneeId ?: incoming.assigneeId,
            participantIds = local.participantIds.ifEmpty { incoming.participantIds },
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
        val manualTitle = state.screenshotPromptSummary
            ?.substringBefore(" · ")
            ?.takeIf { it.isNotBlank() }
            ?: "手动补全行动事项"
        val card = ActionCard(
            cardType = CardTypes.TASK,
            title = manualTitle,
            summary = EvidenceSummaryComposer.compose(
                title = manualTitle,
                deadline = null,
                startTime = null,
                location = null,
                materials = emptyList(),
                submitMethod = null,
            ),
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
            val blockingReasons = ConfirmationCoordinator.blockingReasons(drafts)
            if (blockingReasons.isNotEmpty()) {
                _uiState.update {
                    it.copy(error = blockingReasons.distinct().take(3).joinToString("\n"))
                }
                return@launch
            }
            // The phone is authoritative after the user confirms. Persist the complete
            // selection in one Room transaction, then schedule idempotent reminders.
            // A startup reconciliation covers process death between these two steps.
            // Team batches differ: teammates only see server cards, so those POST first and
            // fall back to an inline error (batch reverts to 个人) when the gateway is away.
            val draftTeamId = state.draftTeamId
            val cardsToSave: List<ActionCard>
            if (draftTeamId != null) {
                val members = teamMemberOptions.value.filter { it.teamId == draftTeamId }
                val teamDrafts = drafts.map { card ->
                    val resolved = com.suishouban.app.domain.team.TeamWorkspacePolicy
                        .matchAssignee(card.assigneeId, members)
                    card.copy(
                        workspaceType = com.suishouban.app.data.model.WorkspaceTypes.TEAM,
                        workspaceId = draftTeamId,
                        // Locally resolvable nicknames become user ids; unknown hints stay
                        // verbatim — the server resolves them against team membership.
                        assigneeId = resolved?.userId ?: card.assigneeId,
                    )
                }
                val pushed = repository.persistConfirmedTeamBatch(teamDrafts)
                if (pushed == null) {
                    draftTeamUserChoice = false
                    _uiState.update {
                        it.copy(
                            draftTeamId = null,
                            draftTeamSuggested = false,
                            teamPushError = "团队卡片需要连接服务器，请检查设置",
                        )
                    }
                    return@launch
                }
                cardsToSave = pushed
            } else {
                cardsToSave = repository.persistConfirmedBatch(drafts)
            }
            val myUserId = state.settings.localUserId
            val syncWarnings = mutableListOf<String>()
            val confirmationMessages = mutableListOf<String>()
            cardsToSave.forEach { saved ->
                // Teammates' (and unassigned) team tasks live in team detail; don't remind me.
                val remindMe = saved.workspaceType != com.suishouban.app.data.model.WorkspaceTypes.TEAM ||
                    saved.assigneeId == myUserId
                if (!remindMe) return@forEach
                val reminderResult = scheduler.schedule(saved)
                if (reminderResult.scheduled) {
                    confirmationMessages += reminderResult.message
                } else {
                    syncWarnings += reminderResult.message
                }
            }
            val shouldConfirmRemoteWorkflowBeforeLocalSave =
                state.traceId.isNotBlank() &&
                    state.workflowStatus in setOf("queued", "running", "awaiting_review") &&
                    state.settings.preferCloudModel
            if (shouldConfirmRemoteWorkflowBeforeLocalSave) {
                _uiState.update { it.copy(loading = true, error = null) }
                val remoteConfirmation = runCatching {
                    activeIntakeSessionId?.let { sessionId ->
                        repository.confirmIntake(sessionId, state.revision, drafts.map(ActionCard::id))
                    } ?: repository.reviewAndConfirm(
                            state.traceId,
                            state.revision,
                            drafts,
                            state.fieldVersions,
                        )
                }
                remoteConfirmation.onFailure { error ->
                    syncWarnings += "行动卡与提醒已保存在手机；云端审核同步失败：${error.message ?: "未知错误"}"
                }.onSuccess { resumed ->
                    if (resumed.workflowStatus != "completed") {
                        syncWarnings += "行动卡与提醒已保存在手机；云端仍有字段待确认"
                    }
                }
            }
            // Consume only the candidate that produced at least one of the drafts actually saved.
            notificationDraftAssociation?.candidateToConsume(cardsToSave.map(ActionCard::id).toSet())?.let { candidateId ->
                notificationCandidateRepository.delete(candidateId)
            }
            clearNotificationDraftAssociation()
            activeIntakeSessionId?.let { sessionId ->
                workflowDao.updateIntakeStatus(
                    id = sessionId,
                    status = "confirmed",
                    workflowRunId = state.traceId.takeIf(String::isNotBlank),
                    updatedAt = OffsetDateTime.now().toString(),
                )
            }
            activeIntakeSessionId = null
            repository.clearActiveWorkflow()
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
            resetDraftWorkspace()
            onDone()
        }
    }

    fun completeCard(id: String) {
        viewModelScope.launch {
            repository.complete(id)
            val event = MascotCompletionEvent(actionCardId = id, occurredAt = Instant.now())
            _mascotCompletionEvent.value = event
            _mascotInteractions.emit(event)
            // The resolver gives urgent and due-soon work precedence immediately. This only
            // clears stale celebration feedback after its visible animation window has elapsed.
            delay(MASCOT_COMPLETION_WINDOW_MILLIS)
            if (_mascotCompletionEvent.value == event) _mascotCompletionEvent.value = null
        }
    }

    fun updateCard(card: ActionCard) {
        val previous = _uiState.value.cards.firstOrNull { it.id == card.id }
        val changedFields = buildList {
            if (previous?.deadline != card.deadline) add("deadline")
            if (previous?.startTime != card.startTime) add("start_time")
            if (previous?.endTime != card.endTime) add("end_time")
            if (previous?.assigneeId != card.assigneeId) add("assignee_id")
            if (previous?.dependencies != card.dependencies) add("dependencies")
            if (previous?.status != card.status) add("status")
            if (previous?.title != card.title) add("title")
            if (previous?.priorityMode != card.priorityMode || previous?.priority != card.priority) {
                add("priority")
            }
        }
        val calibrated = com.suishouban.app.domain.planning.PriorityPlanner.calibrate(card)
        _uiState.update { state ->
            state.copy(
                cards = state.cards.map { existing ->
                    if (existing.id == calibrated.id) calibrated else existing
                },
            )
        }
        viewModelScope.launch {
            repository.update(calibrated)
            scheduler.schedule(calibrated)
        }
        val manualPriorityOnly =
            changedFields == listOf("priority") &&
                (calibrated.priorityMode == com.suishouban.app.data.model.PriorityModes.MANUAL ||
                    calibrated.priorityLocked)
        if (manualPriorityOnly || changedFields.isEmpty()) return
        cardReplanJobs.remove(card.id)?.cancel()
        cardReplanJobs[card.id] = viewModelScope.launch {
            delay(650)
            repository.replan(calibrated, changedFields)?.let { remote ->
                val merged = PriorityCoordinator.mergeRemote(calibrated, remote)
                repository.update(merged)
                scheduler.schedule(merged)
            }
        }
    }

    /** Team changes become visible only after the authenticated server mutation succeeds. */
    fun updateTeamTask(card: ActionCard, onResult: (String?) -> Unit) {
        val calibrated = com.suishouban.app.domain.planning.PriorityPlanner.calibrate(card)
        viewModelScope.launch {
            val result = repository.updateTeamCard(calibrated)
            result.onSuccess { remote ->
                _uiState.update { state ->
                    state.copy(
                        cards = state.cards.map { existing ->
                            if (existing.id == remote.id) remote else existing
                        },
                    )
                }
                scheduler.schedule(remote)
            }
            onResult(result.exceptionOrNull()?.let(::teamErrorMessage))
        }
    }

    fun analyzeFiles(uris: List<Uri>, onDone: (Boolean) -> Unit = {}) {
        if (uris.isEmpty()) return
        if (!_uiState.value.settings.importSources.documents) {
            _uiState.update { it.copy(error = "文档导入已在设置中关闭") }
            onDone(false)
            return
        }
        clearNotificationDraftAssociation()
        locallyEditedDraftIds.clear()
        resetDraftWorkspace()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = true,
                    error = null,
                    draftCards = emptyList(),
                    actionCandidates = emptyList(),
                    selectedDraftIds = emptySet(),
                )
            }
            val remote = repository.analyzeFiles(
                uris = uris,
                sourceKind = if (uris.size > 1) "mixed" else "document",
                profileContext = planningProfileContext(),
            )
            if (remote != null) {
                applyAnalyzeResult(remote, notifyWhenEmpty = true)
                onDone(remote.cards.isNotEmpty())
                return@launch
            }

            val resolver = getApplication<Application>().contentResolver
            val localTexts = uris.take(8).mapNotNull { uri ->
                val mime = resolver.getType(uri).orEmpty()
                when {
                    mime.startsWith("image/") -> runCatching {
                        ocr.recognize(getApplication(), uri)
                    }.getOrNull()
                    mime.startsWith("text/") || mime in setOf(
                        "application/json",
                        "application/xml",
                    ) -> runCatching {
                        resolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                            reader.readText().take(2_000_000)
                        }
                    }.getOrNull()
                    else -> null
                }
            }
            if (localTexts.isEmpty()) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = "这些文件需要配置 HTTPS AI 服务后才能深度解析",
                    )
                }
                onDone(false)
                return@launch
            }
            analyzeTextInternal(
                text = localTexts.joinToString("\n\n"),
                onDone = onDone,
                enginePrefix = "local-multifile",
                extraWarnings = if (localTexts.size < uris.size) {
                    listOf("部分文档需要 HTTPS 增强服务，当前已先处理可本地识别的内容")
                } else {
                    emptyList()
                },
                notifyWhenEmpty = true,
            )
        }
    }

    fun addMaterialsToActiveIntake(uris: List<Uri>) {
        val sessionId = activeIntakeSessionId
        if (sessionId == null) {
            _uiState.update { it.copy(error = "当前候选不是可追加材料的云端 Intake，会保留现有草稿") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { repository.addIntakeAttachments(sessionId, uris) }
                .onSuccess { result ->
                    applyAnalyzeResult(result)
                    _uiState.update { it.copy(loading = false, error = "材料已加入本次识别，可继续完善候选") }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(loading = false, error = "材料加入失败：${error.message ?: "未知错误"}")
                    }
                }
        }
    }

    private suspend fun planningProfileContext(): UserProfileContext? {
        if (!_uiState.value.settings.personalizedPlanningEnabled) return null
        return userProfileRepository.current().toContext()
    }

    fun archiveCard(id: String) {
        viewModelScope.launch {
            repository.archive(id)
        }
    }

    fun updateSettings(settings: AppSettings) {
        settingsRepository.update(settings)
        if (settings.profileLearningEnabled != _uiState.value.userProfile.learningConsent) {
            viewModelScope.launch {
                userProfileRepository.setLearningConsent(settings.profileLearningEnabled)
            }
        }
    }

    fun saveNickname(nickname: String, onResult: (String?) -> Unit) {
        val trimmed = nickname.trim().take(24)
        if (trimmed.isEmpty()) {
            onResult("账号名称不能为空")
            return
        }
        viewModelScope.launch {
            val result = teamRepository.updateIdentity(trimmed)
            if (result.isSuccess) {
                updateSettings(_uiState.value.settings.copy(userNickname = trimmed))
            }
            onResult(result.exceptionOrNull()?.let(::teamErrorMessage))
        }
    }

    fun refreshTeams() {
        viewModelScope.launch {
            teamOperationState.update { it.copy(loading = true, error = null) }
            teamRepository.ensureRegistered()
            val result = teamRepository.refreshTeams()
            teamOperationState.update {
                it.copy(loading = false, error = result.exceptionOrNull()?.let(::teamErrorMessage))
            }
        }
    }

    /** [onResult] receives null on success or a user-visible error to show inline in the dialog. */
    fun createTeam(name: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val result = teamRepository.createTeam(name.trim())
            onResult(result.exceptionOrNull()?.let(::teamErrorMessage))
        }
    }

    /** [onResult] receives null on success or a user-visible error to show inline in the dialog. */
    fun joinTeam(code: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val result = teamRepository.joinTeam(code.trim().uppercase())
            onResult(
                result.exceptionOrNull()?.let { error ->
                    if (error is retrofit2.HttpException && error.code() == 404) {
                        "邀请码不存在"
                    } else {
                        teamErrorMessage(error)
                    }
                },
            )
        }
    }

    private fun teamErrorMessage(error: Throwable): String = when {
        error is retrofit2.HttpException && error.code() == 403 -> "没有权限执行此操作"
        error is retrofit2.HttpException && error.code() == 404 -> "团队不存在或已解散"
        error is retrofit2.HttpException -> "服务器返回错误（${error.code()}）"
        error is java.io.IOException -> "网络不可用，请检查设置中的服务器地址"
        else -> error.message ?: "操作失败，请稍后再试"
    }

    // --- Team detail: summary polling and goal decomposition ---

    private var teamPollingJob: Job? = null
    private var teamSummaryCursor: String? = null
    private val _teamDetailState = MutableStateFlow(TeamDetailUiState())
    val teamDetailState: StateFlow<TeamDetailUiState> = _teamDetailState

    /**
     * Starts the 10s summary poll for the visible team detail screen. Poll failures are silent —
     * the last good summary stays on screen and [TeamDetailUiState.isStale] flips on quietly.
     */
    fun startTeamPolling(teamId: String) {
        if (teamPollingJob?.isActive == true && _teamDetailState.value.teamId == teamId) return
        stopTeamPolling()
        teamSummaryCursor = null
        _teamDetailState.value = TeamDetailUiState(teamId = teamId)
        teamRepository.setActiveTeam(teamId)
        teamPollingJob = viewModelScope.launch {
            while (isActive) {
                pollTeamSummaryOnce(teamId)
                delay(TEAM_SUMMARY_POLL_MILLIS)
            }
        }
    }

    fun stopTeamPolling() {
        teamPollingJob?.cancel()
        teamPollingJob = null
        teamRepository.setActiveTeam(null)
    }

    /** One immediate refresh outside the polling cadence (manual 刷新, post-confirm). */
    fun refreshTeamSummary() {
        val teamId = _teamDetailState.value.teamId ?: return
        viewModelScope.launch { pollTeamSummaryOnce(teamId) }
    }

    private suspend fun pollTeamSummaryOnce(teamId: String) {
        teamRepository.fetchSummary(teamId, teamSummaryCursor)
            .onSuccess { summary ->
                teamSummaryCursor = summary.serverTime.takeIf { it.isNotBlank() }
                _teamDetailState.update { state ->
                    if (state.teamId == teamId) state.copy(summary = summary, isStale = false) else state
                }
            }
            .onFailure {
                _teamDetailState.update { state ->
                    if (state.teamId == teamId) state.copy(isStale = true) else state
                }
            }
    }

    /**
     * 从截图提取共同目标: reuses the existing on-device OCR + rule analysis and hands back only
     * the first card's title and deadline date for the goal form to prefill.
     */
    fun extractGoalSeed(uri: Uri, onResult: (Result<com.suishouban.app.data.model.GoalSeed>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                val text = ocr.recognize(getApplication(), uri)
                check(text.isNotBlank()) { "未识别到文字" }
                val analysis = repository.analyzeTextLocal(
                    text = text,
                    screenshotTime = OffsetDateTime.now(ZoneOffset.ofHours(8)).toString(),
                    enginePrefix = "goal-seed",
                )
                val first = analysis.cards.firstOrNull() ?: error("未识别到行动事项")
                com.suishouban.app.data.model.GoalSeed(
                    title = first.title,
                    dueDate = (first.deadline ?: first.startTime)?.take(10),
                )
            }
            onResult(result)
        }
    }

    /** [onResult] receives the AI task preview or a user-visible error for inline display. */
    fun createTeamGoal(
        teamId: String,
        title: String,
        dueDate: String?,
        onResult: (Result<com.suishouban.app.data.model.TeamGoalPlan>) -> Unit,
    ) {
        viewModelScope.launch {
            val result = teamRepository.createGoal(teamId, title.trim(), dueDate)
            onResult(
                result.fold(
                    onSuccess = { Result.success(it) },
                    onFailure = { Result.failure(IllegalStateException(teamErrorMessage(it))) },
                ),
            )
        }
    }

    /** [onResult] receives null on success or an inline error; success also refreshes the summary. */
    fun confirmTeamGoal(
        teamId: String,
        goalId: String,
        tasks: List<com.suishouban.app.data.model.ProposedTeamTask>,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val result = teamRepository.confirmGoal(teamId, goalId, tasks)
            if (result.isSuccess) refreshTeamSummary()
            onResult(result.exceptionOrNull()?.let(::teamErrorMessage))
        }
    }

    /** [onResult] receives null on success or a user-visible error to show inline in the dialog. */
    fun renameTeam(teamId: String, name: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val result = teamRepository.renameTeam(teamId, name.trim())
            onResult(result.exceptionOrNull()?.let(::teamErrorMessage))
        }
    }

    /** [onResult] receives null on success or a user-visible error to show inline in the dialog. */
    fun dissolveTeam(teamId: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val result = teamRepository.dissolveTeam(teamId)
            onResult(result.exceptionOrNull()?.let(::teamErrorMessage))
        }
    }

    fun saveProviderApiKey(apiKey: String) {
        app.providerSecretStore.saveApiKey(apiKey)
        _uiState.update {
            it.copy(
                hasProviderApiKey = app.providerSecretStore.hasApiKey(),
                connectionStatus = "密钥已安全保存，请测试连接",
            )
        }
    }

    fun clearProviderApiKey() {
        app.providerSecretStore.clear()
        _uiState.update {
            it.copy(hasProviderApiKey = false, connectionStatus = "本机密钥已清除")
        }
    }

    fun skipOnboarding() {
        updateSettings(_uiState.value.settings.copy(onboardingSeen = true))
    }

    fun completeOnboardingQuestionnaire(
        scenario: String,
        activePeriod: String,
        planningGranularity: String,
        reminderStyle: String,
        workRhythm: String,
        bufferPreference: String,
        weekendPolicy: String,
        assistantTone: String,
        learningConsent: Boolean,
        explicitFields: Set<String>,
    ) {
        viewModelScope.launch {
            userProfileRepository.completeQuestionnaire(
                scenario = scenario,
                activePeriod = activePeriod,
                planningGranularity = planningGranularity,
                reminderStyle = reminderStyle,
                workRhythm = workRhythm,
                bufferPreference = bufferPreference,
                weekendPolicy = weekendPolicy,
                assistantTone = assistantTone,
                learningConsent = learningConsent,
                explicitFields = explicitFields,
            )
            settingsRepository.update(
                _uiState.value.settings.copy(
                    onboardingSeen = true,
                    profileLearningEnabled = learningConsent,
                    defaultRefinementGranularity = planningGranularity,
                )
            )
        }
    }

    fun clearInferredProfile() {
        viewModelScope.launch { userProfileRepository.clearInferred() }
    }

    fun resetUserProfile() {
        viewModelScope.launch {
            userProfileRepository.resetAll()
            settingsRepository.update(
                _uiState.value.settings.copy(
                    onboardingSeen = false,
                    profileLearningEnabled = false,
                    defaultRefinementGranularity = "balanced",
                )
            )
        }
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

    private companion object {
        const val MASCOT_COMPLETION_WINDOW_MILLIS = 15_000L
        const val TEAM_SUMMARY_POLL_MILLIS = 10_000L
    }
}

private fun String.normalizedForMatch(): String =
    lowercase().replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "")
