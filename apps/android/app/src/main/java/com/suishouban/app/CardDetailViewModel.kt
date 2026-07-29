package com.suishouban.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.ActionPlan
import com.suishouban.app.data.model.CardAttachment
import com.suishouban.app.data.model.CardRefinementPreference
import com.suishouban.app.data.model.PlanItem
import com.suishouban.app.data.repository.ApplyRefinementResult
import com.suishouban.app.data.repository.PendingAttachment
import com.suishouban.app.data.repository.RefinementDraft
import com.suishouban.app.domain.LocalRefinementOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CardDetailUiState(
    val card: ActionCard,
    val persistedPlan: ActionPlan? = null,
    val persistedAttachments: List<CardAttachment> = emptyList(),
    val preference: CardRefinementPreference? = null,
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val draft: RefinementDraft? = null,
    val selectedItemIds: Set<String> = emptySet(),
    val loading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class CardDetailViewModel(
    application: Application,
    card: ActionCard,
) : AndroidViewModel(application) {
    private val app = application as SuiShouBanApp
    private val repository = app.cardRefinementRepository
    private val settingsRepository = app.settingsRepository
    private val _state = MutableStateFlow(CardDetailUiState(card = card))
    val state: StateFlow<CardDetailUiState> = _state

    init {
        viewModelScope.launch {
            repository.observePlan(card.id).collect { plan ->
                _state.update { it.copy(persistedPlan = plan) }
            }
        }
        viewModelScope.launch {
            repository.observeAttachments(card.id).collect { attachments ->
                _state.update { it.copy(persistedAttachments = attachments) }
            }
        }
        viewModelScope.launch {
            repository.observePreference(card.id).collect { preference ->
                _state.update { it.copy(preference = preference) }
            }
        }
    }

    fun addAttachments(uris: List<Uri>) {
        viewModelScope.launch {
            runCatching { repository.describeAttachments(uris) }
                .onSuccess { attachments ->
                    _state.update {
                        it.copy(
                            pendingAttachments = (it.pendingAttachments + attachments)
                                .distinctBy { attachment -> attachment.uri }
                                .take(8),
                            error = null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "无法读取附件") }
                }
        }
    }

    fun removePendingAttachment(id: String) {
        _state.update {
            it.copy(pendingAttachments = it.pendingAttachments.filterNot { item -> item.id == id })
        }
    }

    fun generatePlan(instruction: String = "") {
        val snapshot = _state.value
        val settings = settingsRepository.settings.value
        val preference = snapshot.preference
        if (!(preference?.refinementEnabled ?: settings.cardRefinementEnabled)) {
            _state.update { it.copy(error = "此卡片的深度细化已关闭") }
            return
        }
        val options = LocalRefinementOptions(
            granularity = preference?.granularity ?: settings.defaultRefinementGranularity,
            includeMilestones = true,
            includeWorkBlocks = preference?.includeWorkBlocks ?: settings.refinementWorkBlocksEnabled,
            milestoneReminders = preference?.milestoneReminders ?: settings.milestoneRemindersEnabled,
            useProfile = preference?.useProfile ?: settings.personalizedPlanningEnabled,
        )
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, message = null) }
            runCatching {
                repository.startRefinement(
                    card = snapshot.card,
                    attachments = snapshot.pendingAttachments,
                    options = options,
                    instruction = instruction,
                )
            }.onSuccess { draft ->
                _state.update {
                    it.copy(
                        draft = draft,
                        selectedItemIds = draft.plan.items.map(PlanItem::id).toSet(),
                        loading = false,
                        message = if (draft.usedCloud) "云端 AI 已生成细化计划" else "已生成本地可编辑计划",
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(loading = false, error = error.message ?: "生成细化计划失败")
                }
            }
        }
    }

    fun toggleItem(id: String) {
        _state.update {
            val selected = it.selectedItemIds.toMutableSet()
            if (!selected.add(id)) selected.remove(id)
            it.copy(selectedItemIds = selected)
        }
    }

    fun updateDraftItem(updated: PlanItem) {
        _state.update { state ->
            val draft = state.draft ?: return@update state
            state.copy(
                draft = draft.copy(
                    plan = draft.plan.copy(
                        items = draft.plan.items.map { if (it.id == updated.id) updated else it }
                    )
                )
            )
        }
    }

    fun refineSelected(instruction: String) {
        val snapshot = _state.value
        val draft = snapshot.draft ?: return
        if (snapshot.selectedItemIds.isEmpty()) {
            _state.update { it.copy(error = "请至少选择一个计划项再继续完善") }
            return
        }
        if (instruction.isBlank()) {
            _state.update { it.copy(error = "请输入需要 AI 调整的内容") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                repository.refineDraft(draft, snapshot.selectedItemIds, instruction)
            }.onSuccess { refined ->
                _state.update {
                    it.copy(
                        draft = refined,
                        selectedItemIds = refined.plan.items.map(PlanItem::id)
                            .filter { id -> id in snapshot.selectedItemIds }
                            .toSet(),
                        loading = false,
                        message = "已更新选中的计划项",
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(loading = false, error = error.message ?: "计划调整失败") }
            }
        }
    }

    fun applyPlan(onApplied: (ApplyRefinementResult) -> Unit = {}) {
        val snapshot = _state.value
        val draft = snapshot.draft ?: return
        val selected = draft.plan.items.filter { it.id in snapshot.selectedItemIds }
        if (selected.isEmpty()) {
            _state.update { it.copy(error = "请至少保留一个计划项") }
            return
        }
        if (draft.plan.constraintErrors.isNotEmpty()) {
            _state.update {
                it.copy(error = "计划仍有约束冲突，请修改或重新生成后再应用")
            }
            return
        }
        if (selected.any { it.needConfirm.isNotEmpty() }) {
            _state.update { it.copy(error = "仍有时间或要求需要确认，请编辑后再应用计划") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repository.applyDraft(snapshot.card, draft, selected) }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            draft = null,
                            pendingAttachments = emptyList(),
                            selectedItemIds = emptySet(),
                            loading = false,
                            message = buildString {
                                append("计划已保存")
                                if (result.scheduledMilestones > 0) {
                                    append("，已创建 ${result.scheduledMilestones} 个提醒")
                                }
                            },
                        )
                    }
                    onApplied(result)
                }
                .onFailure { error ->
                    _state.update { it.copy(loading = false, error = error.message ?: "保存计划失败") }
                }
        }
    }

    fun savePreference(preference: CardRefinementPreference) {
        viewModelScope.launch { repository.savePreference(preference) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null, error = null) }
    }
}

class CardDetailViewModelFactory(
    private val application: Application,
    private val card: ActionCard,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CardDetailViewModel(application, card) as T
}
