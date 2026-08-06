package com.suishouban.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suishouban.app.TeamDetailUiState
import com.suishouban.app.TeamSummary
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardStatus
import com.suishouban.app.data.model.ProposedTeamTask
import com.suishouban.app.data.model.TeamGoalPlan
import com.suishouban.app.data.model.TeamGoalProgress
import com.suishouban.app.data.model.TeamMemberInfo
import com.suishouban.app.ui.components.NeutralPill
import com.suishouban.app.ui.components.DateTimeWheelPickerDialog
import com.suishouban.app.ui.theme.AccentIconChip
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.CollectionBrown
import com.suishouban.app.ui.theme.DS
import com.suishouban.app.ui.theme.DsSectionHeader
import com.suishouban.app.ui.theme.HairlineDivider
import com.suishouban.app.ui.theme.Ink
import com.suishouban.app.ui.theme.Line
import com.suishouban.app.ui.theme.MistBlue
import com.suishouban.app.ui.theme.Muted
import com.suishouban.app.ui.theme.PromiseOrange
import com.suishouban.app.ui.theme.SoftCard
import com.suishouban.app.ui.theme.TaskRed
import com.suishouban.app.ui.theme.visualForCardType
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/**
 * Team detail: one restrained page — members, the shared goal with milestone progress, per-member
 * progress, and a read-only task list; a second tab shows the same tasks on a horizontal timeline.
 * The server summary is polled while this screen is visible and released on dispose.
 */
@Composable
fun TeamDetailScreen(
    teamId: String,
    teamRow: TeamSummary?,
    detail: TeamDetailUiState,
    teamCards: List<ActionCard>,
    myUserId: String,
    reduceMotion: Boolean,
    onBack: () -> Unit,
    onStartPolling: (String) -> Unit,
    onStopPolling: () -> Unit,
    onRefresh: () -> Unit,
    onRename: (String, String, (String?) -> Unit) -> Unit,
    onDissolve: (String, (String?) -> Unit) -> Unit,
    onCreateGoal: (String, String, String?, (Result<TeamGoalPlan>) -> Unit) -> Unit,
    onConfirmGoal: (String, String, List<ProposedTeamTask>, (String?) -> Unit) -> Unit,
    onUpdateTask: (ActionCard, (String?) -> Unit) -> Unit,
    onExtractGoalSeed: (android.net.Uri, (Result<com.suishouban.app.data.model.GoalSeed>) -> Unit) -> Unit = { _, onResult ->
        onResult(Result.failure(IllegalStateException("未接入截图提取")))
    },
) {
    // Poll only while this screen is actually visible; disposal stops the network cadence.
    DisposableEffect(teamId) {
        onStartPolling(teamId)
        onDispose { onStopPolling() }
    }
    var showGoalFlow by rememberSaveable { mutableStateOf(false) }

    if (showGoalFlow) {
        val summary = detail.summary
        TeamGoalFlow(
            members = summary?.members.orEmpty(),
            reduceMotion = reduceMotion,
            onCreateGoal = { title, dueDate, onResult -> onCreateGoal(teamId, title, dueDate, onResult) },
            onConfirmGoal = { goalId, tasks, onResult -> onConfirmGoal(teamId, goalId, tasks, onResult) },
            onClose = { showGoalFlow = false },
            onExtractGoalSeed = onExtractGoalSeed,
        )
        return
    }

    BackHandler(onBack = onBack)
    var tab by rememberSaveable { mutableStateOf("overview") }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showDissolveDialog by rememberSaveable { mutableStateOf(false) }
    var selectedGoalId by rememberSaveable(teamId) { mutableStateOf<String?>(null) }
    var selectedTask by remember { mutableStateOf<ActionCard?>(null) }

    val summary = detail.summary
    val teamName = summary?.teamName ?: teamRow?.name ?: "团队"
    val inviteCode = summary?.inviteCode?.takeIf { it.isNotBlank() } ?: teamRow?.inviteCode.orEmpty()
    val isOwner = teamRow?.myRole == "owner" || (summary != null && summary.ownerId == myUserId)
    val goals = summary?.goals.orEmpty()
    LaunchedEffect(goals.map { it.goal.id }) {
        if (selectedGoalId !in goals.map { it.goal.id }) {
            selectedGoalId = goals.firstOrNull { it.goal.status == "active" }?.goal?.id
                ?: goals.firstOrNull()?.goal?.id
        }
    }
    val activeGoal = goals.firstOrNull { it.goal.id == selectedGoalId }
    val visibleCards = teamCards.filter { card ->
        card.status != CardStatus.ARCHIVED &&
            (activeGoal == null || card.belongsTo(activeGoal))
    }
    val nicknameById = summary?.members?.associate { it.userId to it.nickname }.orEmpty()

    LazyColumn(
        modifier = Modifier
            .padding(horizontal = DS.ScreenPadding)
            .testTag("team_detail"),
        verticalArrangement = Arrangement.spacedBy(DS.ItemGap),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            TeamDetailHeader(
                teamName = teamName,
                inviteCode = inviteCode,
                isOwner = isOwner,
                onBack = onBack,
                onRename = { showRenameDialog = true },
                onDissolve = { showDissolveDialog = true },
            )
        }
        item {
            MemberChipsRow(members = summary?.members.orEmpty())
        }
        if (goals.isNotEmpty()) {
            item {
                GoalSelectorRow(
                    goals = goals,
                    selectedGoalId = activeGoal?.goal?.id,
                    canPublish = isOwner,
                    onSelect = { selectedGoalId = it },
                    onPublish = { showGoalFlow = true },
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeutralPill(text = "总览", selected = tab == "overview", onClick = { tab = "overview" })
                Spacer(Modifier.width(8.dp))
                NeutralPill(text = "时间线", selected = tab == "timeline", onClick = { tab = "timeline" })
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "刷新",
                        tint = Muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (detail.isStale) {
                Text(
                    "连接不稳定，正在显示最近一次同步",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
        }
        if (tab == "overview") {
            item {
                GoalCard(
                    goal = activeGoal,
                    isOwner = isOwner,
                    loadingSummary = summary == null,
                    onPublish = { showGoalFlow = true },
                )
            }
            // Progress follows the selected goal instead of mixing every goal in the team.
            val stats = summary?.members.orEmpty().map { member ->
                val owned = visibleCards.filter { it.assigneeId == member.userId }
                Triple(member, owned.count { it.status == CardStatus.DONE }, owned.size)
            }
            if (stats.isNotEmpty()) {
                item {
                    DsSectionHeader(
                        title = "成员进度",
                        icon = Icons.Outlined.Groups,
                        trailing = "${stats.size} 人",
                    )
                    Spacer(Modifier.height(4.dp))
                    SoftCard(radius = DS.RadiusTile) {
                        Column(
                            Modifier.padding(horizontal = DS.CardPadding, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            stats.forEach { (member, done, total) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        member.nickname.ifBlank { "成员" },
                                        modifier = Modifier.width(72.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    LinearProgressIndicator(
                                        progress = { if (total > 0) done.toFloat() / total else 0f },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(4.dp)
                                            .clip(CircleShape),
                                        color = BrandBlue,
                                        trackColor = MistBlue,
                                        strokeCap = StrokeCap.Round,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "$done/$total",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Muted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (visibleCards.isNotEmpty()) {
                item {
                    DsSectionHeader(
                        title = "任务",
                        icon = Icons.Outlined.Checklist,
                        trailing = "${visibleCards.size} 项",
                    )
                }
                item {
                    TaskGroups(
                        cards = visibleCards,
                        goal = activeGoal,
                        nicknameById = nicknameById,
                        onOpenTask = { selectedTask = it },
                    )
                }
            }
        } else {
            item {
                TeamTimeline(
                    members = summary?.members.orEmpty(),
                    cards = visibleCards,
                    goal = activeGoal,
                    nicknameById = nicknameById,
                    onOpenTask = { selectedTask = it },
                )
            }
        }
        item { Spacer(Modifier.height(92.dp)) }
    }

    if (showRenameDialog) {
        TeamDetailFieldDialog(
            title = "重命名团队",
            placeholder = "团队名称",
            initialValue = teamName,
            onSubmit = { value, done -> onRename(teamId, value, done) },
            onDismiss = { showRenameDialog = false },
        )
    }
    if (showDissolveDialog) {
        DissolveTeamDialog(
            teamName = teamName,
            onConfirm = { done -> onDissolve(teamId, done) },
            onDismiss = { showDissolveDialog = false },
            onDissolved = onBack,
        )
    }
    selectedTask?.let { task ->
        TaskDetailDialog(
            card = task,
            members = summary?.members.orEmpty(),
            onSave = onUpdateTask,
            onCancelTask = { done ->
                onUpdateTask(task.copy(status = CardStatus.ARCHIVED), done)
            },
            onDismiss = { selectedTask = null },
        )
    }
}

@Composable
private fun TeamDetailHeader(
    teamName: String,
    inviteCode: String,
    isOwner: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDissolve: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = Ink)
        }
        Column(Modifier.weight(1f)) {
            Text(
                teamName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (inviteCode.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(DS.RadiusChipBadge))
                        .background(DS.TileNeutral)
                        .clickable {
                            clipboard.setText(AnnotatedString(inviteCode))
                            copied = true
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(if (copied) "已复制" else "邀请码", fontSize = 10.sp, color = Muted)
                    Text(
                        inviteCode,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BrandBlue,
                        letterSpacing = 1.sp,
                    )
                    Icon(
                        if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        contentDescription = "复制邀请码",
                        tint = if (copied) BrandBlue else Muted,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        if (isOwner) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多", tint = Muted)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("解散团队", color = TaskRed) },
                        onClick = {
                            menuOpen = false
                            onDissolve()
                        },
                    )
                }
            }
        }
    }
}

/** One horizontal line of quiet member chips — an initial disc, the nickname, a 队长 marker. */
@Composable
private fun MemberChipsRow(members: List<TeamMemberInfo>) {
    if (members.isEmpty()) {
        Text("正在同步成员…", style = MaterialTheme.typography.labelMedium, color = Muted)
        return
    }
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        members.forEach { member ->
            val accent = avatarColorOf(member.avatarColor)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(DS.RadiusChipBadge))
                    .background(DS.TileNeutral)
                    .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        member.nickname.firstOrNull()?.toString() ?: "友",
                        fontSize = 11.sp,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    member.nickname,
                    style = MaterialTheme.typography.labelMedium,
                    color = Ink,
                    maxLines = 1,
                )
                if (member.role == "owner") {
                    Text("队长", fontSize = 10.sp, color = BrandBlue, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun GoalSelectorRow(
    goals: List<TeamGoalProgress>,
    selectedGoalId: String?,
    canPublish: Boolean,
    onSelect: (String) -> Unit,
    onPublish: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        goals.forEach { progress ->
            val title = progress.goal.title
            NeutralPill(
                text = if (title.length > 12) "${title.take(12)}…" else title,
                selected = progress.goal.id == selectedGoalId,
                onClick = { onSelect(progress.goal.id) },
            )
        }
        if (canPublish) {
            TextButton(onClick = onPublish) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("新增目标", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun GoalCard(
    goal: TeamGoalProgress?,
    isOwner: Boolean,
    loadingSummary: Boolean,
    onPublish: () -> Unit,
) {
    SoftCard {
        Column(Modifier.padding(DS.CardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                goal == null && loadingSummary -> Text(
                    "正在同步团队目标…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
                goal == null -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AccentIconChip(icon = Icons.Outlined.Flag, size = 36.dp)
                    Text(
                        "还没有共同目标",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink,
                    )
                    if (isOwner) {
                        Text(
                            "发布一个目标，AI 会拆成里程碑和分工",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = onPublish,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            shape = RoundedCornerShape(DS.RadiusButton),
                        ) {
                            Text("发布共同目标", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Text(
                            "等待队长发布目标后，这里会显示进度",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            goal.goal.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                        )
                        if (goal.goal.decomposeSource == "template") {
                            Text(
                                "模板拆解",
                                fontSize = 10.sp,
                                color = Muted,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(DS.RadiusChipBadge))
                                    .background(DS.TileNeutral)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    dayLabelOf(goal.goal.dueDate)?.let { due ->
                        Text("截止 $due", style = MaterialTheme.typography.labelMedium, color = Muted)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { if (goal.total > 0) goal.done.toFloat() / goal.total else 0f },
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(CircleShape),
                            color = BrandBlue,
                            trackColor = MistBlue,
                            strokeCap = StrokeCap.Round,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${goal.done}/${goal.total} 已完成",
                            style = MaterialTheme.typography.labelMedium,
                            color = Muted,
                        )
                    }
                    if (goal.milestones.isNotEmpty()) {
                        HairlineDivider()
                        Text("里程碑", style = MaterialTheme.typography.labelSmall, color = Muted)
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            goal.milestones.forEach { milestone ->
                                val complete = milestone.total > 0 && milestone.done == milestone.total
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .then(
                                                if (complete) Modifier.background(BrandBlue)
                                                else Modifier.border(1.5.dp, Line, CircleShape),
                                            ),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        milestone.milestone.title,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    dayLabelOf(milestone.milestone.dueDate)?.let { due ->
                                        Text(due, style = MaterialTheme.typography.labelSmall, color = Muted)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        "${milestone.done}/${milestone.total}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Muted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Read-only task rows grouped by milestone; status editing stays in 卡片页. */
@Composable
private fun TaskGroups(
    cards: List<ActionCard>,
    goal: TeamGoalProgress?,
    nicknameById: Map<String, String>,
    onOpenTask: (ActionCard) -> Unit,
) {
    val milestones = goal?.goal?.milestones.orEmpty().sortedBy { it.sortOrder }
    val cardsByMilestone = cards.groupBy { it.milestoneId }
    val orderedGroups = buildList {
        milestones.forEach { milestone ->
            cardsByMilestone[milestone.id]?.let { add(milestone.title to it) }
        }
        val knownIds = milestones.map { it.id }.toSet()
        cardsByMilestone
            .filterKeys { it != null && it !in knownIds }
            .values
            .flatten()
            .takeIf { it.isNotEmpty() }
            ?.let { add("其他里程碑" to it) }
        cardsByMilestone[null]?.let { add("未分组" to it) }
    }
    SoftCard(radius = DS.RadiusTile) {
        Column(
            Modifier.padding(horizontal = DS.CardPadding, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            orderedGroups.forEachIndexed { index, (label, groupCards) ->
                if (index > 0) HairlineDivider()
                Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
                groupCards.sortedBy { it.deadline ?: it.startTime ?: "" }.forEach { card ->
                    TaskRow(card = card, nicknameById = nicknameById, onOpen = { onOpenTask(card) })
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    card: ActionCard,
    nicknameById: Map<String, String>,
    onOpen: () -> Unit,
) {
    val visual = visualForCardType(card.cardType)
    val done = card.status == CardStatus.DONE
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DS.RadiusChipBadge))
            .clickable(onClick = onOpen)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (done) visual.color.copy(alpha = 0.35f) else visual.color),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            card.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (done) Muted else Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        card.assigneeId?.let { assignee ->
            val nickname = nicknameById[assignee] ?: assignee.take(4)
            Text(
                nickname,
                fontSize = 10.sp,
                color = BrandBlue,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(DS.RadiusChipBadge))
                    .background(MistBlue)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        dayLabelOf(card.deadline ?: card.startTime)?.let { day ->
            Text(day, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Outlined.Edit, contentDescription = "查看或修改任务", tint = Muted, modifier = Modifier.size(15.dp))
    }
}

// --- Timeline (read-only horizontal Gantt) ---

private val TimelineDayWidth: Dp = 28.dp
private val TimelineRowHeight: Dp = 28.dp
private val TimelineAxisHeight: Dp = 40.dp

@Composable
private fun TeamTimeline(
    members: List<TeamMemberInfo>,
    cards: List<ActionCard>,
    goal: TeamGoalProgress?,
    nicknameById: Map<String, String>,
    onOpenTask: (ActionCard) -> Unit,
) {
    val today = remember { LocalDate.now() }
    val firstDay = today.minusDays(2)
    val goalDue = dayOf(goal?.goal?.dueDate)
    val minLastDay = firstDay.plusDays(13)
    val lastDay = if (goalDue != null && goalDue.isAfter(minLastDay)) goalDue else minLastDay
    val dayCount = (lastDay.toEpochDay() - firstDay.toEpochDay()).toInt() + 1
    val timedCards = cards.filter { dayOf(it.startTime) != null || dayOf(it.deadline) != null }
    val lanes = buildList {
        members.forEach { member ->
            add(member.nickname to timedCards.filter { it.assigneeId == member.userId })
        }
        timedCards.filter { it.assigneeId == null || it.assigneeId !in nicknameById }
            .takeIf { it.isNotEmpty() }
            ?.let { add("未分配" to it) }
    }
    val milestoneDays = goal?.goal?.milestones.orEmpty().mapNotNull { dayOf(it.dueDate) }.toSet()

    if (lanes.isEmpty() || timedCards.isEmpty()) {
        SoftCard(radius = DS.RadiusTile) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "任务带上开始或截止时间后，会出现在时间线上",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
        }
        return
    }

    SoftCard(radius = DS.RadiusTile) {
        Column(Modifier.padding(vertical = 14.dp)) {
        Row {
            // Fixed nickname column so lane owners stay visible while the days scroll.
            Column(Modifier.width(64.dp).padding(start = 12.dp)) {
                Spacer(Modifier.height(TimelineAxisHeight))
                lanes.forEach { (name, _) ->
                    Box(Modifier.height(TimelineRowHeight), contentAlignment = Alignment.CenterStart) {
                        Text(
                            name,
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
            ) {
                val totalWidth = TimelineDayWidth * dayCount
                val totalHeight = TimelineAxisHeight + TimelineRowHeight * lanes.size
                Column(Modifier.width(totalWidth)) {
                    // Day axis: weekday + date ticks, milestone due dates as tiny dots.
                    Row(Modifier.height(TimelineAxisHeight)) {
                        (0 until dayCount).forEach { index ->
                            val day = firstDay.plusDays(index.toLong())
                            Column(
                                Modifier.width(TimelineDayWidth),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(weekdayLabel(day), fontSize = 10.sp, color = Muted)
                                Text("${day.monthValue}/${day.dayOfMonth}", fontSize = 10.sp, color = Muted)
                                if (day in milestoneDays) {
                                    Spacer(Modifier.height(2.dp))
                                    Box(
                                        Modifier
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(BrandBlue),
                                    )
                                }
                            }
                        }
                    }
                    lanes.forEach { (_, laneCards) ->
                        Box(Modifier.height(TimelineRowHeight).width(totalWidth)) {
                            laneCards.forEach { card ->
                                val start = (dayOf(card.startTime) ?: dayOf(card.deadline))!!
                                val end = dayOf(card.deadline) ?: start
                                val normalizedEnd = maxOf(end, start)
                                if (normalizedEnd < firstDay || start > lastDay) return@forEach
                                val clampedStart = maxOf(start, firstDay)
                                val clampedEnd = minOf(normalizedEnd, lastDay)
                                val offsetDays = (clampedStart.toEpochDay() - firstDay.toEpochDay()).toInt()
                                val spanDays = (clampedEnd.toEpochDay() - clampedStart.toEpochDay()).toInt() + 1
                                val done = card.status == CardStatus.DONE
                                val fill = if (done) TaskRed.copy(alpha = 0.09f) else TaskRed.copy(alpha = 0.18f)
                                val edge = if (done) TaskRed.copy(alpha = 0.40f) else TaskRed
                                Row(
                                    Modifier
                                        .offset(x = TimelineDayWidth * offsetDays)
                                        .width(TimelineDayWidth * spanDays)
                                        .height(20.dp)
                                        .align(Alignment.CenterStart)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(fill)
                                        .clickable { onOpenTask(card) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.width(3.dp).height(20.dp).background(edge))
                                    if (spanDays >= 3) {
                                        Text(
                                            card.title,
                                            modifier = Modifier.padding(horizontal = 4.dp),
                                            fontSize = 9.sp,
                                            color = Ink,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // Today marker: one 1dp brand-colored line with a small label at the axis.
                val todayOffset = (today.toEpochDay() - firstDay.toEpochDay()).toInt()
                if (todayOffset in 0 until dayCount) {
                    val lineX = TimelineDayWidth * todayOffset + TimelineDayWidth / 2
                    Box(
                        Modifier
                            .offset(x = lineX)
                            .width(1.dp)
                            .height(totalHeight)
                            .background(BrandBlue),
                    )
                    Text(
                        "今天",
                        fontSize = 10.sp,
                        color = BrandBlue,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.offset(x = lineX + 3.dp, y = totalHeight - 14.dp),
                    )
                }
            }
        }
        HairlineDivider(Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        Column(
            Modifier.padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            timedCards.sortedBy { it.startTime ?: it.deadline ?: "" }.forEach { card ->
                val assignee = card.assigneeId?.let { nicknameById[it] } ?: "未分配"
                val range = timelineRangeLabel(card)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DS.RadiusChipBadge))
                        .clickable { onOpenTask(card) }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(TaskRed))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            card.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("$assignee · $range", style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                    Icon(Icons.Outlined.Edit, contentDescription = "查看或修改任务", tint = Muted, modifier = Modifier.size(15.dp))
                }
            }
        }
        }
    }
}

@Composable
private fun TaskDetailDialog(
    card: ActionCard,
    members: List<TeamMemberInfo>,
    onSave: (ActionCard, (String?) -> Unit) -> Unit,
    onCancelTask: ((String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(card.id, card.updatedAt) { mutableStateOf(card) }
    var assigneeMenuOpen by remember { mutableStateOf(false) }
    var pickerField by remember { mutableStateOf<String?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var cancelSubmitting by remember { mutableStateOf(false) }
    var cancelError by remember { mutableStateOf<String?>(null) }
    val assigneeName = members.firstOrNull { it.userId == draft.assigneeId }?.nickname ?: "未分配"

    AlertDialog(
        onDismissRequest = { if (!submitting && !cancelSubmitting) onDismiss() },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("任务详情", fontWeight = FontWeight.Bold)
                Text(
                    when (card.status) {
                        CardStatus.DONE -> "已完成"
                        CardStatus.CONFIRMED -> "进行中"
                        else -> "待处理"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { draft = draft.copy(title = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("任务名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(DS.RadiusTile),
                )
                OutlinedTextField(
                    value = draft.summary,
                    onValueChange = { draft = draft.copy(summary = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("任务说明") },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(DS.RadiusTile),
                )
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(DS.RadiusTile))
                            .background(DS.TileNeutral)
                            .clickable { assigneeMenuOpen = true }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Person, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("负责人", style = MaterialTheme.typography.labelMedium, color = Muted)
                        Spacer(Modifier.weight(1f))
                        Text(assigneeName, style = MaterialTheme.typography.bodyMedium, color = Ink)
                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, tint = Muted)
                    }
                    DropdownMenu(expanded = assigneeMenuOpen, onDismissRequest = { assigneeMenuOpen = false }) {
                        members.forEach { member ->
                            DropdownMenuItem(
                                text = { Text(member.nickname) },
                                onClick = {
                                    draft = draft.copy(assigneeId = member.userId)
                                    assigneeMenuOpen = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("未分配", color = Muted) },
                            onClick = {
                                draft = draft.copy(assigneeId = null)
                                assigneeMenuOpen = false
                            },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskTimeField(
                        label = "开始",
                        value = draft.startTime,
                        modifier = Modifier.weight(1f),
                        onClick = { pickerField = "start" },
                    )
                    TaskTimeField(
                        label = "截止",
                        value = draft.deadline,
                        modifier = Modifier.weight(1f),
                        onClick = { pickerField = "deadline" },
                    )
                }
                if (draft.deliverables.isNotEmpty()) {
                    Text(
                        "交付物：${draft.deliverables.joinToString("、")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                saveError?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = TaskRed)
                }
                TextButton(
                    onClick = { confirmCancel = true },
                    modifier = Modifier.align(Alignment.End),
                    enabled = !submitting,
                ) {
                    Text("取消此任务", color = TaskRed, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (submitting) return@TextButton
                    submitting = true
                    saveError = null
                    onSave(
                        draft.copy(title = draft.title.trim(), summary = draft.summary.trim()),
                    ) { failure ->
                        submitting = false
                        if (failure == null) onDismiss() else saveError = failure
                    }
                },
                enabled = draft.title.isNotBlank() && !submitting,
            ) {
                Text(
                    if (submitting) "保存中…" else "保存修改",
                    color = BrandBlue,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("关闭", color = Muted) }
        },
    )

    pickerField?.let { field ->
        val current = if (field == "start") draft.startTime else draft.deadline
        DateTimeWheelPickerDialog(
            initialValue = current,
            title = if (field == "start") "选择开始时间" else "选择截止时间",
            onDismiss = { pickerField = null },
            onClear = {
                draft = if (field == "start") draft.copy(startTime = null) else draft.copy(deadline = null)
                pickerField = null
            },
            onConfirm = { value ->
                draft = if (field == "start") draft.copy(startTime = value) else draft.copy(deadline = value)
                pickerField = null
            },
        )
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { if (!cancelSubmitting) confirmCancel = false },
            title = { Text("取消任务", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("“${card.title}”将从该目标的任务与时间线中移除。")
                    cancelError?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = TaskRed)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (cancelSubmitting) return@TextButton
                        cancelSubmitting = true
                        cancelError = null
                        onCancelTask { failure ->
                            cancelSubmitting = false
                            if (failure == null) onDismiss() else cancelError = failure
                        }
                    },
                    enabled = !cancelSubmitting,
                ) {
                    Text(
                        if (cancelSubmitting) "取消中…" else "确认取消",
                        color = TaskRed,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmCancel = false },
                    enabled = !cancelSubmitting,
                ) { Text("返回", color = Muted) }
            },
        )
    }
}

@Composable
private fun TaskTimeField(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(DS.RadiusTile))
            .background(DS.TileNeutral)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
            Text(dateTimeLabel(value) ?: "未设置", style = MaterialTheme.typography.labelMedium, color = Ink)
        }
    }
}

// --- Dialogs ---

@Composable
private fun TeamDetailFieldDialog(
    title: String,
    placeholder: String,
    initialValue: String,
    onSubmit: (String, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initialValue) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    shape = RoundedCornerShape(DS.RadiusTile),
                )
                error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = TaskRed) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (value.isBlank() || submitting) return@TextButton
                    submitting = true
                    error = null
                    onSubmit(value.trim()) { failure ->
                        submitting = false
                        if (failure == null) onDismiss() else error = failure
                    }
                },
                enabled = value.isNotBlank() && !submitting,
            ) {
                Text(if (submitting) "提交中…" else "确认", color = BrandBlue, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Muted) }
        },
    )
}

@Composable
private fun DissolveTeamDialog(
    teamName: String,
    onConfirm: ((String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onDissolved: () -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("解散团队", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "解散“$teamName”后，团队目标与任务分工将不再同步。此操作不可撤销。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink,
                )
                error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = TaskRed) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (submitting) return@TextButton
                    submitting = true
                    error = null
                    onConfirm { failure ->
                        submitting = false
                        if (failure == null) {
                            onDismiss()
                            onDissolved()
                        } else {
                            error = failure
                        }
                    }
                },
                enabled = !submitting,
            ) {
                Text(if (submitting) "解散中…" else "确认解散", color = TaskRed, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Muted) }
        },
    )
}

// --- Small shared helpers ---

/** Server avatar_color tokens map onto the app's existing accent palette; unknowns fall back to brand. */
internal fun avatarColorOf(token: String): Color = when (token.lowercase()) {
    "blue" -> BrandBlue
    "red" -> TaskRed
    "orange" -> PromiseOrange
    "green" -> Color(0xFF2FA36B)
    "purple", "violet" -> Color(0xFF7C5CFF)
    "cyan", "teal" -> Color(0xFF19A3B8)
    "brown", "amber" -> CollectionBrown
    else -> BrandBlue
}

private fun dayOf(value: String?): LocalDate? {
    if (value.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
}

internal fun dayLabelOf(value: String?): String? =
    dayOf(value)?.format(DateTimeFormatter.ofPattern("M月d日"))

private fun weekdayLabel(day: LocalDate): String =
    listOf("一", "二", "三", "四", "五", "六", "日")[day.dayOfWeek.value - 1]

private fun ActionCard.belongsTo(goal: TeamGoalProgress): Boolean {
    if (goalId != null) return goalId == goal.goal.id
    val milestoneIds = goal.goal.milestones.map { it.id }.toSet()
    return milestoneId in milestoneIds || sourceText == "团队目标：${goal.goal.title}"
}

private fun timelineRangeLabel(card: ActionCard): String {
    val start = dayLabelOf(card.startTime)
    val end = dayLabelOf(card.deadline)
    return when {
        start != null && end != null && start != end -> "$start 至 $end"
        end != null -> "截止 $end"
        start != null -> "$start 开始"
        else -> "未设置时间"
    }
}

private fun dateTimeLabel(value: String?): String? {
    if (value.isNullOrBlank()) return null
    return runCatching {
        OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
    }.getOrNull() ?: dayLabelOf(value)
}
