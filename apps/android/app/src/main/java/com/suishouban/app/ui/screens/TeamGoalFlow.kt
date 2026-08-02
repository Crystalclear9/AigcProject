package com.suishouban.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suishouban.app.data.model.ProposedTeamTask
import com.suishouban.app.data.model.TeamGoalPlan
import com.suishouban.app.data.model.TeamMemberInfo
import com.suishouban.app.mascot.MascotAnimationHint
import com.suishouban.app.mascot.MascotColorRole
import com.suishouban.app.mascot.MascotMood
import com.suishouban.app.mascot.MascotState
import com.suishouban.app.mascot.MofeiPetSprite
import com.suishouban.app.ui.components.DateTimeWheelPickerDialog
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.DS
import com.suishouban.app.ui.theme.Ink
import com.suishouban.app.ui.theme.MistBlue
import com.suishouban.app.ui.theme.Muted
import com.suishouban.app.ui.theme.SoftCard
import com.suishouban.app.ui.theme.TaskRed

/**
 * Owner-only goal publishing flow: one sentence in, AI decomposition preview out, one confirm.
 * Three quiet steps in a single full-screen swap — no sheets stacked on dialogs, no toasts.
 * During decomposition Mofei appears only as its plain sprite; no halo, particles, or bubble.
 */
@Composable
fun TeamGoalFlow(
    members: List<TeamMemberInfo>,
    reduceMotion: Boolean,
    onCreateGoal: (String, String?, (Result<TeamGoalPlan>) -> Unit) -> Unit,
    onConfirmGoal: (String, List<ProposedTeamTask>, (String?) -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var title by rememberSaveable { mutableStateOf("") }
    var dueDate by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<TeamGoalPlan?>(null) }
    var tasks by remember { mutableStateOf<List<ProposedTeamTask>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    fun decompose() {
        if (title.isBlank() || loading) return
        loading = true
        error = null
        plan = null
        onCreateGoal(title.trim(), dueDate) { result ->
            loading = false
            result
                .onSuccess { generated ->
                    plan = generated
                    tasks = generated.tasks
                }
                .onFailure { failure -> error = failure.message ?: "拆解失败，请稍后再试" }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = DS.ScreenPadding)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = Ink)
            }
            Text(
                "发布共同目标",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
        }
        Spacer(Modifier.height(DS.ItemGap))
        val currentPlan = plan
        when {
            loading -> GoalDecomposeLoading(reduceMotion = reduceMotion)
            currentPlan != null -> GoalPreview(
                plan = currentPlan,
                tasks = tasks,
                members = members,
                error = error,
                confirming = confirming,
                onTasksChange = { tasks = it },
                onRetry = {
                    plan = null
                    error = null
                },
                onConfirm = {
                    if (confirming) return@GoalPreview
                    confirming = true
                    error = null
                    onConfirmGoal(currentPlan.goal.id, tasks) { failure ->
                        confirming = false
                        if (failure == null) onClose() else error = failure
                    }
                },
            )
            else -> GoalInput(
                title = title,
                dueDate = dueDate,
                error = error,
                onTitleChange = { title = it },
                onPickDate = { showDatePicker = true },
                onClearDate = { dueDate = null },
                onSubmit = ::decompose,
            )
        }
    }

    if (showDatePicker) {
        DateTimeWheelPickerDialog(
            initialValue = dueDate,
            title = "选择目标截止日",
            onDismiss = { showDatePicker = false },
            onClear = {
                dueDate = null
                showDatePicker = false
            },
            onConfirm = { value ->
                // The backend goal due_date is a plain ISO date.
                dueDate = value.take(10)
                showDatePicker = false
            },
        )
    }
}

@Composable
private fun GoalInput(
    title: String,
    dueDate: String?,
    error: String?,
    onTitleChange: (String) -> Unit,
    onPickDate: () -> Unit,
    onClearDate: () -> Unit,
    onSubmit: () -> Unit,
) {
    SoftCard {
        Column(Modifier.padding(DS.CardPadding), verticalArrangement = Arrangement.spacedBy(DS.ItemGap)) {
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("一句话说出共同目标") },
                singleLine = true,
                shape = RoundedCornerShape(DS.RadiusTile),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPickDate) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, tint = BrandBlue)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        dayLabelOf(dueDate)?.let { "截止 $it" } ?: "选择截止日（可选）",
                        color = if (dueDate == null) Muted else BrandBlue,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (dueDate != null) {
                    TextButton(onClick = onClearDate) { Text("清除", color = Muted) }
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = TaskRed) }
            Button(
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
                enabled = title.isNotBlank(),
                shape = RoundedCornerShape(DS.RadiusButton),
            ) {
                Text("AI 拆解", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun GoalDecomposeLoading(reduceMotion: Boolean) {
    // A plain sprite only: the pet's focus frames without halo, particles, or speech bubble.
    val thinkingState = remember {
        MascotState(
            mood = MascotMood.FOCUS,
            userMessage = "墨斐正在拆解目标",
            colorRole = MascotColorRole.FOCUS,
            animationHint = MascotAnimationHint.SCAN,
        )
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MofeiPetSprite(
                state = thinkingState,
                reduceMotion = reduceMotion,
                modifier = Modifier.size(96.dp),
            )
            Text("墨斐正在拆解目标…", style = MaterialTheme.typography.bodyMedium, color = Muted)
            LinearProgressIndicator(
                modifier = Modifier.width(160.dp).height(3.dp),
                color = BrandBlue,
                trackColor = MistBlue,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalPreview(
    plan: TeamGoalPlan,
    tasks: List<ProposedTeamTask>,
    members: List<TeamMemberInfo>,
    error: String?,
    confirming: Boolean,
    onTasksChange: (List<ProposedTeamTask>) -> Unit,
    onRetry: () -> Unit,
    onConfirm: () -> Unit,
) {
    val milestoneTitles = plan.goal.milestones.associate { it.id to it.title }
    val summaryLine = buildString {
        append("已生成 ${tasks.size} 项任务、${plan.goal.milestones.size} 个里程碑")
        if (plan.goal.decomposeSource == "template") append(" · 规则拆解")
    }
    val grouped = tasks
        .withIndex()
        .groupBy { it.value.milestoneId }
    val groupOrder = buildList {
        plan.goal.milestones.sortedBy { it.sortOrder }.forEach { milestone ->
            if (grouped.containsKey(milestone.id)) add(milestone.id)
        }
        grouped.keys.filter { it !in milestoneTitles.keys }.forEach { add(it) }
    }

    Column(Modifier.fillMaxSize()) {
        Text(summaryLine, style = MaterialTheme.typography.labelMedium, color = Muted)
        Spacer(Modifier.height(DS.ItemGap))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DS.ItemGap),
        ) {
            groupOrder.forEach { milestoneId ->
                val groupTasks = grouped[milestoneId].orEmpty()
                item(key = "milestone-$milestoneId") {
                    Text(
                        milestoneTitles[milestoneId] ?: "未分组",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }
                items(groupTasks.size, key = { index -> "task-$milestoneId-${groupTasks[index].index}" }) { position ->
                    val indexed = groupTasks[position]
                    ProposedTaskRow(
                        task = indexed.value,
                        members = members,
                        onAssigneeChange = { assignee ->
                            onTasksChange(
                                tasks.toMutableList().also {
                                    it[indexed.index] = it[indexed.index].copy(assigneeId = assignee)
                                },
                            )
                        },
                        onDelete = {
                            onTasksChange(tasks.filterIndexed { i, _ -> i != indexed.index })
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = TaskRed)
            Spacer(Modifier.height(6.dp))
        }
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            enabled = tasks.isNotEmpty() && !confirming,
            shape = RoundedCornerShape(DS.RadiusButton),
        ) {
            Text(if (confirming) "发布中…" else "确认发布", fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("重新拆解", color = Muted, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProposedTaskRow(
    task: ProposedTeamTask,
    members: List<TeamMemberInfo>,
    onAssigneeChange: (String?) -> Unit,
    onDelete: () -> Unit,
) {
    SoftCard(radius = DS.RadiusTile) {
        Column(
            Modifier.padding(horizontal = DS.CardPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "删除任务",
                        tint = Muted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                var expanded by remember { mutableStateOf(false) }
                val assigneeName = members.firstOrNull { it.userId == task.assigneeId }?.nickname
                    ?: "未分配"
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = assigneeName,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelMedium,
                        label = { Text("负责人") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        shape = RoundedCornerShape(DS.RadiusButton),
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        members.forEach { member ->
                            DropdownMenuItem(
                                text = { Text(member.nickname) },
                                onClick = {
                                    expanded = false
                                    onAssigneeChange(member.userId)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("未分配", color = Muted) },
                            onClick = {
                                expanded = false
                                onAssigneeChange(null)
                            },
                        )
                    }
                }
                dayLabelOf(task.deadline ?: task.startTime)?.let { day ->
                    Spacer(Modifier.width(10.dp))
                    Text(day, style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
        }
    }
}
