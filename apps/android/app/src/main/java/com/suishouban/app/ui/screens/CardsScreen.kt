package com.suishouban.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suishouban.app.AppUiState
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.effectiveReminderNodes
import com.suishouban.app.data.model.CardStatus
import com.suishouban.app.data.model.CardTypes
import com.suishouban.app.data.model.Priority
import com.suishouban.app.data.model.PriorityModes
import com.suishouban.app.data.model.WorkspaceTypes
import com.suishouban.app.data.model.mergeReminderLabels
import com.suishouban.app.ui.components.ActionCardItem
import com.suishouban.app.ui.components.DateTimeWheelPickerDialog
import com.suishouban.app.ui.components.ReminderNodesEditor
import com.suishouban.app.ui.components.PriorityPickerDialog
import com.suishouban.app.ui.components.NeutralPill
import com.suishouban.app.ui.components.SectionHeader
import com.suishouban.app.ui.theme.AccentIconChip
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.DS
import com.suishouban.app.ui.theme.Ink
import com.suishouban.app.ui.theme.Line
import com.suishouban.app.ui.theme.Muted
import com.suishouban.app.ui.theme.SoftCard
import java.time.OffsetDateTime

enum class CardWorkspaceTab(val workspaceType: String) {
    PERSONAL(WorkspaceTypes.PERSONAL),
    TEAM(WorkspaceTypes.TEAM),
}

@Composable
fun CardsScreen(
    state: AppUiState,
    onUpdate: (ActionCard) -> Unit,
    onComplete: (String) -> Unit,
    onArchive: (String) -> Unit,
    onImport: () -> Unit,
    highlightCardId: String? = null,
    teamNames: Map<String, String> = emptyMap(),
    workspaceTab: CardWorkspaceTab = CardWorkspaceTab.PERSONAL,
    onWorkspaceTabChange: (CardWorkspaceTab) -> Unit = {},
    onManageTeams: () -> Unit = {},
    teamCount: Int = 0,
    teamSyncing: Boolean = false,
) {
    var personalType by rememberSaveable { mutableStateOf("all") }
    var teamType by rememberSaveable { mutableStateOf("all") }
    var personalStatus by rememberSaveable { mutableStateOf("active") }
    var teamStatus by rememberSaveable { mutableStateOf("active") }
    var personalKeyword by rememberSaveable { mutableStateOf("") }
    var teamKeyword by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<ActionCard?>(null) }
    var selectedCardId by rememberSaveable { mutableStateOf<String?>(null) }
    var priorityEditingId by rememberSaveable { mutableStateOf<String?>(null) }

    val type = if (workspaceTab == CardWorkspaceTab.PERSONAL) personalType else teamType
    val status = if (workspaceTab == CardWorkspaceTab.PERSONAL) personalStatus else teamStatus
    val keyword = if (workspaceTab == CardWorkspaceTab.PERSONAL) personalKeyword else teamKeyword

    val filtered = state.cards.filter { card ->
        (type == "all" || card.cardType == type) &&
            card.workspaceType == workspaceTab.workspaceType &&
            when (status) {
                "active" -> card.status != CardStatus.DONE && card.status != CardStatus.ARCHIVED
                "done" -> card.status == CardStatus.DONE
                "archived" -> card.status == CardStatus.ARCHIVED
                else -> true
            } &&
            (keyword.isBlank() || card.title.contains(keyword, ignoreCase = true) || card.summary.contains(keyword, ignoreCase = true) || card.sourceText.contains(keyword, ignoreCase = true))
    }.sortedBy { card -> if (card.id == highlightCardId) 0 else 1 }

    LazyColumn(
        modifier = Modifier.padding(horizontal = DS.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(DS.SectionGap),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            SectionHeader("卡片中心", "${filtered.size} 张", icon = Icons.Outlined.Style)
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                CardWorkspaceTab.entries.forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = workspaceTab == tab,
                        onClick = { onWorkspaceTabChange(tab) },
                        shape = SegmentedButtonDefaults.itemShape(index, CardWorkspaceTab.entries.size),
                        modifier = Modifier
                            .weight(1f)
                            .testTag(if (tab == CardWorkspaceTab.PERSONAL) "cards_tab_personal" else "cards_tab_team"),
                    ) {
                        Text(if (tab == CardWorkspaceTab.PERSONAL) "个人" else "团队", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (workspaceTab == CardWorkspaceTab.TEAM) {
            item {
                SoftCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onManageTeams)
                            .testTag("cards_manage_teams")
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AccentIconChip(icon = Icons.Outlined.Groups, accent = BrandBlue, size = 38.dp)
                        Column(Modifier.weight(1f)) {
                            Text("管理团队", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Ink)
                            Text(
                                if (teamSyncing) "正在同步" else "$teamCount 个团队",
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                            )
                        }
                        Icon(Icons.Outlined.ChevronRight, contentDescription = "进入团队管理", tint = Muted)
                    }
                }
            }
        }
        // Search + filters grouped into one toolbar card so the controls read as a unit.
        item {
            SoftCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = {
                            if (workspaceTab == CardWorkspaceTab.PERSONAL) personalKeyword = it else teamKeyword = it
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(
                                if (workspaceTab == CardWorkspaceTab.PERSONAL) {
                                    "cards_search_personal"
                                } else {
                                    "cards_search_team"
                                },
                            ),
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = BrandBlue) },
                        placeholder = { Text("搜索标题、摘要、原始截图文字") },
                        shape = RoundedCornerShape(DS.RadiusTile),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = DS.TileNeutral,
                            unfocusedContainerColor = DS.TileNeutral,
                            focusedIndicatorColor = BrandBlue,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            "all" to "全部",
                            CardTypes.TASK to "任务",
                            CardTypes.EVENT to "事件",
                            CardTypes.PROMISE to "承诺",
                        ).forEach { (value, label) ->
                            NeutralPill(
                                text = label,
                                selected = type == value,
                                onClick = {
                                    if (workspaceTab == CardWorkspaceTab.PERSONAL) personalType = value else teamType = value
                                },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            "active" to "进行中",
                            "done" to "已完成",
                            "archived" to "归档",
                            "all" to "全部状态",
                        ).forEach { (value, label) ->
                            NeutralPill(
                                text = label,
                                selected = status == value,
                                onClick = {
                                    if (workspaceTab == CardWorkspaceTab.PERSONAL) personalStatus = value else teamStatus = value
                                },
                            )
                        }
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                SoftCard {
                    Column(
                        Modifier.fillMaxWidth().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AccentIconChip(icon = Icons.Outlined.TravelExplore, accent = BrandBlue, size = 52.dp)
                        Text("暂无匹配卡片", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
                        Text(
                            "换个筛选条件，或从截图重新生成候选卡。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted,
                        )
                        Button(
                            onClick = onImport,
                            shape = RoundedCornerShape(DS.RadiusButton),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                        ) {
                            Text("导入截图生成卡片", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else {
            items(filtered, key = { it.id }) { card ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionCardItem(
                        card = card,
                        onOpen = { selectedCardId = card.id },
                        onEdit = { editing = card },
                        onPriorityClick = { priorityEditingId = card.id },
                        onComplete = if (card.status == CardStatus.DONE) null else ({ onComplete(card.id) }),
                        teamBadge = if (card.workspaceType == WorkspaceTypes.TEAM) {
                            teamNames[card.workspaceId]?.firstOrNull()?.toString() ?: "团"
                        } else {
                            null
                        },
                    )
                    if (card.status != CardStatus.ARCHIVED) {
                        // Archive is a low-emphasis action, so it's a quiet text button, not a
                        // full solid bar competing with the card's own primary action.
                        TextButton(
                            onClick = { onArchive(card.id) },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text("归档", color = Muted, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(92.dp))
        }
    }

    editing?.let { card ->
        EditCardDialog(
            card = card,
            onDismiss = { editing = null },
            onSave = {
                onUpdate(it)
                editing = null
            },
        )
    }

    selectedCardId?.let { cardId ->
        val card = state.cards.firstOrNull { it.id == cardId }
        if (card != null) {
            CardDetailSheet(
                card = card,
                settings = state.settings,
                onDismiss = { selectedCardId = null },
                onEditParent = {
                    selectedCardId = null
                    editing = it
                },
                onUpdateParent = onUpdate,
            )
        }
    }
    priorityEditingId?.let { cardId ->
        val card = state.cards.firstOrNull { it.id == cardId }
        if (card != null) {
            PriorityPickerDialog(
                card = card,
                onDismiss = { priorityEditingId = null },
                onChange = {
                    onUpdate(it)
                    priorityEditingId = null
                },
            )
        }
    }
}

@Composable
private fun EditCardDialog(
    card: ActionCard,
    onDismiss: () -> Unit,
    onSave: (ActionCard) -> Unit,
) {
    var draft by remember(card.id) { mutableStateOf(card) }
    var pickerField by remember(card.id) { mutableStateOf<String?>(null) }
    var timeError by remember(card.id) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onSave(draft) },
                enabled = timeError == null,
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        title = { Text("编辑行动卡") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { draft = draft.copy(title = it) },
                    label = { Text("标题") },
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = draft.summary,
                    onValueChange = { draft = draft.copy(summary = it) },
                    label = { Text("摘要") },
                    shape = RoundedCornerShape(14.dp),
                )
                if (draft.cardType == CardTypes.EVENT) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EditTimeField(
                            value = draft.startTime,
                            label = "开始时间",
                            onClick = { pickerField = "start" },
                            modifier = Modifier.weight(1f),
                        )
                        EditTimeField(
                            value = draft.endTime,
                            label = "结束时间",
                            onClick = { pickerField = "end" },
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    EditTimeField(
                        value = draft.deadline,
                        label = if (draft.deadline.isNullOrBlank()) "截止时间 · 待确认" else "截止时间",
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
                OutlinedTextField(
                    value = draft.location.orEmpty(),
                    onValueChange = { draft = draft.copy(location = it.ifBlank { null }) },
                    label = { Text("地点/平台") },
                    shape = RoundedCornerShape(14.dp),
                )
                Text("任务空间", style = MaterialTheme.typography.labelLarge, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        WorkspaceTypes.PERSONAL to "个人",
                        WorkspaceTypes.TEAM to "团队",
                    ).forEach { (value, label) ->
                        NeutralPill(
                            text = label,
                            selected = draft.workspaceType == value,
                            onClick = {
                                draft = draft.copy(
                                    workspaceType = value,
                                    workspaceId = if (value == WorkspaceTypes.PERSONAL) {
                                        WorkspaceTypes.PERSONAL
                                    } else {
                                        draft.workspaceId.takeUnless { it == WorkspaceTypes.PERSONAL }
                                            ?: "local-team"
                                    },
                                )
                            },
                        )
                    }
                }
                if (draft.workspaceType == WorkspaceTypes.TEAM) {
                    OutlinedTextField(
                        value = draft.assigneeId.orEmpty(),
                        onValueChange = { draft = draft.copy(assigneeId = it.ifBlank { null }) },
                        label = { Text("负责人") },
                        shape = RoundedCornerShape(14.dp),
                    )
                    OutlinedTextField(
                        value = draft.participantIds.joinToString("，"),
                        onValueChange = { draft = draft.copy(participantIds = splitTeamValues(it)) },
                        label = { Text("参与者") },
                        shape = RoundedCornerShape(14.dp),
                    )
                    OutlinedTextField(
                        value = draft.deliverables.joinToString("，"),
                        onValueChange = { draft = draft.copy(deliverables = splitTeamValues(it)) },
                        label = { Text("交付物") },
                        shape = RoundedCornerShape(14.dp),
                    )
                    OutlinedTextField(
                        value = draft.dependencies.joinToString("，"),
                        onValueChange = { draft = draft.copy(dependencies = splitTeamValues(it)) },
                        label = { Text("前置任务 / 交接") },
                        shape = RoundedCornerShape(14.dp),
                    )
                }
                Text("优先级方式", style = MaterialTheme.typography.labelLarge, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeutralPill(
                        text = "自动调整",
                        selected = draft.priorityMode == PriorityModes.ADAPTIVE,
                        onClick = {
                            draft = draft.copy(
                                priorityMode = PriorityModes.ADAPTIVE,
                                priorityLocked = false,
                            )
                        },
                    )
                    NeutralPill(
                        text = "手动",
                        selected = draft.priorityMode == PriorityModes.MANUAL,
                        onClick = {
                            draft = draft.copy(
                                priorityMode = PriorityModes.MANUAL,
                                priorityLocked = true,
                            )
                        },
                    )
                }
                if (draft.priorityMode == PriorityModes.MANUAL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            Priority.LOW to "低",
                            Priority.NORMAL to "普通",
                            Priority.HIGH to "高",
                        ).forEach { (value, label) ->
                            NeutralPill(
                                text = label,
                                selected = draft.priority == value,
                                onClick = {
                                    draft = draft.copy(
                                        priority = value,
                                        priorityLocked = true,
                                    )
                                },
                            )
                        }
                    }
                } else if (draft.priorityReason.isNotBlank()) {
                    Text(
                        "当前依据：${draft.priorityReason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                ReminderNodesEditor(
                    nodes = draft.effectiveReminderNodes(),
                    deadline = draft.deadline,
                    onChange = { nodes ->
                        draft = draft.copy(
                            reminders = nodes.map { it.displayLabel() },
                            reminderNodes = nodes,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        shape = RoundedCornerShape(24.dp),
    )
    pickerField?.let { field ->
        DateTimeWheelPickerDialog(
            initialValue = when (field) {
                "start" -> draft.startTime
                "end" -> draft.endTime
                else -> draft.deadline
            },
            title = when (field) {
                "start" -> "选择开始时间"
                "end" -> "选择结束时间"
                else -> "选择截止时间"
            },
            onDismiss = { pickerField = null },
            onClear = {
                draft = when (field) {
                    "start" -> draft.copy(startTime = null)
                    "end" -> draft.copy(endTime = null)
                    else -> draft.copy(deadline = null)
                }
                timeError = null
                pickerField = null
            },
            onConfirm = { value ->
                val invalidEnd = field == "end" &&
                    draft.startTime?.let { start ->
                        runCatching {
                            OffsetDateTime.parse(value) <= OffsetDateTime.parse(start)
                        }.getOrDefault(false)
                    } == true
                if (invalidEnd) {
                    timeError = "结束时间必须晚于开始时间"
                } else {
                    draft = when (field) {
                        "start" -> draft.copy(startTime = value)
                        "end" -> draft.copy(endTime = value)
                        else -> draft.copy(deadline = value)
                    }
                    timeError = null
                    pickerField = null
                }
            },
        )
    }
}

@Composable
private fun EditTimeField(
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
        label = { Text(label) },
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
    )
}

private fun splitTeamValues(value: String): List<String> =
    value.split(',', '，', ';', '；', '\n')
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
