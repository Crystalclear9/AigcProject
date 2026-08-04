package com.suishouban.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suishouban.app.data.model.GoalSeed
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
    onExtractGoalSeed: (Uri, (Result<GoalSeed>) -> Unit) -> Unit = { _, onResult ->
        onResult(Result.failure(IllegalStateException("未接入截图提取")))
    },
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
    var extracting by remember { mutableStateOf(false) }
    var extractError by remember { mutableStateOf<String?>(null) }
    // Reuses the same system photo picker as the import flow; the analysis itself is the
    // existing OCR + rule path, so no new pipeline is introduced for goal prefill.
    val goalImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null && !extracting) {
            extracting = true
            extractError = null
            onExtractGoalSeed(uri) { result ->
                extracting = false
                result
                    .onSuccess { seed ->
                        title = seed.title
                        seed.dueDate?.let { dueDate = it }
                    }
                    .onFailure { extractError = "未能从截图提取，请手动输入" }
            }
        }
    }

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
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
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
                extracting = extracting,
                extractError = extractError,
                memberCount = members.size,
                onTitleChange = { title = it },
                onPickDate = { showDatePicker = true },
                onClearDate = { dueDate = null },
                onExtractFromScreenshot = {
                    goalImagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
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
    extracting: Boolean,
    extractError: String?,
    memberCount: Int,
    onTitleChange: (String) -> Unit,
    onPickDate: () -> Unit,
    onClearDate: () -> Unit,
    onExtractFromScreenshot: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column {
        SoftCard {
            Column(Modifier.padding(DS.CardPadding), verticalArrangement = Arrangement.spacedBy(DS.ItemGap)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "目标",
                        style = MaterialTheme.typography.labelMedium,
                        color = BrandBlue,
                        fontWeight = FontWeight.Bold,
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = onTitleChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("一句话说出共同目标") },
                        singleLine = true,
                        shape = RoundedCornerShape(DS.RadiusTile),
                    )
                    Text(
                        "例如：6月20日前完成AIGC比赛作品初稿",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }
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
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onExtractFromScreenshot, enabled = !extracting) {
                        Text(
                            if (extracting) "正在提取…" else "从截图提取",
                            color = Muted,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (extracting) {
                    // One thin quiet line while the on-device OCR + rules run.
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = BrandBlue,
                        trackColor = MistBlue,
                    )
                }
                extractError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Muted) }
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
        Text(
            "AI 会把目标拆成里程碑，并把任务分给 $memberCount 名成员，发布前你可以调整",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
        )
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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
        if (plan.goal.decomposeSource == "template") append(" · 模板拆解")
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
        Text(
            plan.goal.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        dayLabelOf(plan.goal.dueDate)?.let { due ->
            Spacer(Modifier.height(2.dp))
            Text("截止 $due", style = MaterialTheme.typography.labelMedium, color = Muted)
        }
        Spacer(Modifier.height(4.dp))
        Text(summaryLine, style = MaterialTheme.typography.labelMedium, color = Muted)
        Spacer(Modifier.height(DS.ItemGap))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DS.ItemGap),
        ) {
            groupOrder.forEach { milestoneId ->
                val groupTasks = grouped[milestoneId].orEmpty()
                item(key = "milestone-$milestoneId") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(BrandBlue),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            milestoneTitles[milestoneId] ?: "未分组",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink,
                        )
                    }
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
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("重新拆解", color = Muted, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ProposedTaskRow(
    task: ProposedTeamTask,
    members: List<TeamMemberInfo>,
    onAssigneeChange: (String?) -> Unit,
    onDelete: () -> Unit,
) {
    SoftCard(radius = DS.RadiusTile) {
        Column(
            Modifier.padding(horizontal = DS.CardPadding, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
                val assigned = assigneeName != null
                val tint = if (assigned) BrandBlue else Muted
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(DS.RadiusChipBadge))
                            .background(if (assigned) MistBlue else DS.TileNeutral)
                            .clickable { expanded = true }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Person,
                            contentDescription = "负责人",
                            tint = tint,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            assigneeName ?: "未分配",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = tint,
                        )
                        Icon(
                            Icons.Outlined.ArrowDropDown,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
                Spacer(Modifier.weight(1f))
                dayLabelOf(task.deadline ?: task.startTime)?.let { day ->
                    Text(day, style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
        }
    }
}
