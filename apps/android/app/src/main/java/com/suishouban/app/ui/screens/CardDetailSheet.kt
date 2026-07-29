package com.suishouban.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddTask
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suishouban.app.CardDetailViewModel
import com.suishouban.app.CardDetailViewModelFactory
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardRefinementPreference
import com.suishouban.app.data.model.PlanItem
import com.suishouban.app.data.model.PlanItemKinds
import com.suishouban.app.data.repository.AppSettings
import com.suishouban.app.reminder.CalendarSyncer
import com.suishouban.app.ui.components.NeutralPill
import com.suishouban.app.ui.components.PriorityPickerDialog
import com.suishouban.app.ui.components.DateTimeWheelPickerDialog
import com.suishouban.app.ui.components.formatSmartTime
import com.suishouban.app.ui.theme.visualForPriority
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.DS
import com.suishouban.app.ui.theme.Ink
import com.suishouban.app.ui.theme.Line
import com.suishouban.app.ui.theme.Muted
import com.suishouban.app.ui.theme.SoftCard
import com.suishouban.app.ui.theme.Warning

private val refinementMimeTypes = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "text/plain",
    "text/markdown",
    "image/jpeg",
    "image/png",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailSheet(
    card: ActionCard,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onEditParent: (ActionCard) -> Unit,
    onUpdateParent: (ActionCard) -> Unit,
) {
    val context = LocalContext.current
    val model: CardDetailViewModel = viewModel(
        key = "card-detail-${card.id}",
        factory = CardDetailViewModelFactory(context.applicationContext as android.app.Application, card),
    )
    val state by model.state.collectAsStateWithLifecycle()
    var instruction by remember { mutableStateOf("") }
    var editingItem by remember { mutableStateOf<PlanItem?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
    var showPreferences by remember { mutableStateOf(false) }
    var showPriorityPicker by remember { mutableStateOf(false) }
    val priorityVisual = visualForPriority(card.priority)
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        if (uris.isNotEmpty()) {
            model.addAttachments(uris)
            selectedTab = 2
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = priorityVisual.container,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DS.ScreenPadding, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("行动卡详情", style = MaterialTheme.typography.labelLarge, color = BrandBlue)
                    Text(
                        card.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    color = priorityVisual.container,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.clickable { showPriorityPicker = true },
                ) {
                    Text(
                        priorityVisual.label,
                        color = priorityVisual.content,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
                IconButton(onClick = { onEditParent(card) }) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑父卡")
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭")
                }
            }
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = priorityVisual.container,
            ) {
                listOf("计划", "概览", "材料").forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal) },
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DS.ScreenPadding),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (selectedTab) {
                        0 -> {
                            item {
                                PlanHero(
                                    hasPlan = state.persistedPlan != null,
                                    hasDraft = state.draft != null,
                                    enabled = settings.cardRefinementEnabled ||
                                        state.preference?.refinementEnabled == true,
                                    loading = state.loading,
                                )
                            }
                            item {
                                TextButton(
                                    onClick = { showPreferences = !showPreferences },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("本卡规划设置", modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                                    Icon(
                                        if (showPreferences) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                        contentDescription = if (showPreferences) "收起本卡规划设置" else "展开本卡规划设置",
                                    )
                                }
                            }
                            if (showPreferences) {
                                item {
                                    RefinementPreferencePanel(
                                        cardId = card.id,
                                        settings = settings,
                                        preference = state.preference,
                                        onSave = model::savePreference,
                                    )
                                }
                            }
                            if (state.persistedPlan != null && state.draft == null) {
                                item {
                                    PlanQualitySummary(state.persistedPlan!!)
                                }
                                items(state.persistedPlan!!.items, key = { it.id }) { item ->
                                    PersistedPlanItem(item)
                                }
                            }
                            if (state.loading) {
                                item {
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.size(10.dp))
                                        Text("正在读取材料并校验计划…", color = Muted)
                                    }
                                }
                            }
                            state.draft?.let { draft ->
                                item {
                                    PlanQualitySummary(draft.plan)
                                    Text(
                                        if (draft.usedCloud) "云端 AI 已参与，应用前请复核。" else "当前为本地规则结果，可继续编辑。",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (draft.usedCloud) BrandBlue else Muted,
                                    )
                                }
                                items(draft.plan.items, key = { it.id }) { item ->
                                    DraftPlanItem(
                                        item = item,
                                        selected = item.id in state.selectedItemIds,
                                        onToggle = { model.toggleItem(item.id) },
                                        onEdit = { editingItem = item },
                                    )
                                }
                                item {
                                    OutlinedTextField(
                                        value = instruction,
                                        onValueChange = { instruction = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("继续调整选中的计划项") },
                                        placeholder = { Text("例如：拆得更细、避开晚上、增加一次彩排") },
                                        minLines = 2,
                                        shape = RoundedCornerShape(16.dp),
                                    )
                                }
                            }
                        }
                        1 -> {
                            item {
                                SoftCard {
                                    Column(
                                        Modifier.padding(18.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        if (card.summary.isNotBlank()) {
                                            Text(card.summary, style = MaterialTheme.typography.bodyLarge, color = Ink)
                                        }
                                        DetailLine(
                                            Icons.Outlined.Schedule,
                                            formatSmartTime(card.deadline ?: card.startTime),
                                        )
                                        if (!card.location.isNullOrBlank()) DetailLine(Icons.Outlined.Place, card.location)
                                        if (!card.submitMethod.isNullOrBlank()) {
                                            DetailLine(Icons.Outlined.AddTask, "提交方式：${card.submitMethod}")
                                        }
                                        if (card.materials.isNotEmpty()) {
                                            Text("材料：${card.materials.joinToString("、")}", color = Muted)
                                        }
                                        CalendarSyncer(context).buildInsertIntent(card)?.let { calendarIntent ->
                                            OutlinedButton(
                                                onClick = { context.startActivity(calendarIntent) },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(DS.RadiusButton),
                                            ) {
                                                Icon(Icons.Outlined.Event, contentDescription = null)
                                                Spacer(Modifier.size(6.dp))
                                                Text("添加到系统日历")
                                            }
                                            Text(
                                                "将打开系统日历预填信息，由你最终确认。",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Muted,
                                            )
                                        }
                                    }
                                }
                            }
                            if (card.evidenceSummary.isNotEmpty()) {
                                item {
                                    Text("来源证据", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(card.evidenceSummary.joinToString("\n"), color = Muted)
                                }
                            }
                        }
                        else -> {
                            item {
                                MaterialHero(
                                    pending = state.pendingAttachments.size,
                                    persisted = state.persistedAttachments.size,
                                )
                            }
                            items(state.pendingAttachments, key = { it.id }) { attachment ->
                                SoftCard {
                                    Row(
                                        Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = BrandBlue)
                                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                            Text(attachment.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                            Text("等待生成时解析", style = MaterialTheme.typography.bodySmall, color = Muted)
                                        }
                                        TextButton(onClick = { model.removePendingAttachment(attachment.id) }) { Text("移除") }
                                    }
                                }
                            }
                            items(state.persistedAttachments, key = { it.id }) { attachment ->
                                SoftCard {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(attachment.displayName, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            when (attachment.extractionStatus) {
                                                "succeeded" -> "解析成功"
                                                "degraded" -> "部分解析"
                                                else -> attachment.warning ?: "解析状态：${attachment.extractionStatus}"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (attachment.extractionStatus == "succeeded") BrandBlue else Warning,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (!state.error.isNullOrBlank() || !state.message.isNullOrBlank()) {
                        item {
                            val isError = !state.error.isNullOrBlank()
                            Text(
                                state.error ?: state.message.orEmpty(),
                                color = if (isError) MaterialTheme.colorScheme.error else BrandBlue,
                                modifier = Modifier.clickable(onClick = model::clearMessage),
                            )
                        }
                    }
                    item { Spacer(Modifier.height(18.dp)) }
                }
            }
            HorizontalDivider(color = Line)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = DS.ScreenPadding, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    selectedTab == 2 -> {
                        Button(
                            onClick = { picker.launch(refinementMimeTypes) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(DS.RadiusButton),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                        ) {
                            Icon(Icons.Outlined.AttachFile, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("添加材料")
                        }
                    }
                    state.draft != null -> {
                        OutlinedButton(
                            onClick = { model.refineSelected(instruction) },
                            enabled = !state.loading && state.selectedItemIds.isNotEmpty() && instruction.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(DS.RadiusButton),
                        ) {
                            Text("继续完善")
                        }
                        Button(
                            onClick = { model.applyPlan() },
                            enabled = !state.loading &&
                                state.selectedItemIds.isNotEmpty() &&
                                state.draft?.plan?.constraintErrors.isNullOrEmpty(),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(DS.RadiusButton),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                        ) {
                            Text("应用计划")
                        }
                    }
                    selectedTab == 0 -> {
                    OutlinedButton(
                        onClick = { picker.launch(refinementMimeTypes) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(DS.RadiusButton),
                    ) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("补充材料")
                    }
                    Button(
                        onClick = { model.generatePlan(instruction) },
                        enabled = !state.loading &&
                            (settings.cardRefinementEnabled || state.preference?.refinementEnabled == true),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(DS.RadiusButton),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text(if (state.persistedPlan == null) "细化此卡片" else "重新细化")
                    }
                }
                    else -> {
                        OutlinedButton(
                            onClick = { onEditParent(card) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(DS.RadiusButton),
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("编辑行动卡")
                        }
                    }
                }
            }
        }
    }

    editingItem?.let { item ->
        PlanItemEditDialog(
            item = item,
            onDismiss = { editingItem = null },
            onSave = {
                model.updateDraftItem(it)
                editingItem = null
            },
        )
    }
    if (showPriorityPicker) {
        PriorityPickerDialog(
            card = card,
            onDismiss = { showPriorityPicker = false },
            onChange = {
                onUpdateParent(it)
                showPriorityPicker = false
            },
        )
    }
}

@Composable
private fun PlanHero(
    hasPlan: Boolean,
    hasDraft: Boolean,
    enabled: Boolean,
    loading: Boolean,
) {
    SoftCard {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                when {
                    loading -> "正在形成可执行计划"
                    hasDraft -> "计划草稿等待确认"
                    hasPlan -> "这张卡已有深度计划"
                    else -> "把这张卡拆成真正可执行的步骤"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
            Text(
                when {
                    !enabled -> "深度细化当前关闭，可在本卡规划设置中单独开启。"
                    hasDraft -> "先检查冲突和待确认时间，再决定应用哪些节点。"
                    hasPlan -> "里程碑、时间块和步骤都只在这里展开，外层卡片保持简洁。"
                    else -> "可补充 PDF、Office、文字或图片材料；应用前不会写入提醒和日历。"
                },
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MaterialHero(pending: Int, persisted: Int) {
    SoftCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("补充材料", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "本次待解析 $pending 个 · 已随计划保存元数据 $persisted 个",
                color = Muted,
            )
            Text(
                "原文件仍由设备保管；后端解析完成后立即删除临时副本。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

@Composable
private fun PlanQualitySummary(plan: com.suishouban.app.data.model.ActionPlan) {
    SoftCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (plan.status == "accepted") "已保存计划" else "计划预览",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                NeutralPill(
                    text = "质量 ${(plan.qualityScore * 100).toInt()}%",
                    selected = plan.constraintErrors.isEmpty(),
                )
            }
            Text(
                plan.verificationSummary.ifBlank {
                    if (plan.constraintErrors.isEmpty()) "已完成基础顺序与时间校验" else "计划仍需修正"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (plan.constraintErrors.isEmpty()) BrandBlue else Warning,
            )
            plan.profileEffects.take(3).forEach {
                Text("· $it", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            plan.constraintErrors.forEach {
                Text("待修正：$it", style = MaterialTheme.typography.bodySmall, color = Warning)
            }
        }
    }
}

@Composable
private fun RefinementPreferencePanel(
    cardId: String,
    settings: AppSettings,
    preference: CardRefinementPreference?,
    onSave: (CardRefinementPreference) -> Unit,
) {
    val enabled = preference?.refinementEnabled ?: settings.cardRefinementEnabled
    val useProfile = preference?.useProfile ?: settings.personalizedPlanningEnabled
    val workBlocks = preference?.includeWorkBlocks ?: settings.refinementWorkBlocksEnabled
    val reminders = preference?.milestoneReminders ?: settings.milestoneRemindersEnabled
    val granularity = preference?.granularity ?: settings.defaultRefinementGranularity

    SoftCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PreferenceSwitch("此卡启用深度细化", enabled) {
                onSave((preference ?: CardRefinementPreference(cardId)).copy(refinementEnabled = it))
            }
            PreferenceSwitch("使用个性化规划", useProfile) {
                onSave((preference ?: CardRefinementPreference(cardId)).copy(useProfile = it))
            }
            PreferenceSwitch("生成可安排的时间块", workBlocks) {
                onSave((preference ?: CardRefinementPreference(cardId)).copy(includeWorkBlocks = it))
            }
            PreferenceSwitch("里程碑提醒", reminders) {
                onSave((preference ?: CardRefinementPreference(cardId)).copy(milestoneReminders = it))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("concise" to "简洁", "balanced" to "均衡", "detailed" to "详细")
                    .forEach { (value, label) ->
                        NeutralPill(
                            text = label,
                            selected = granularity == value,
                            onClick = {
                                onSave(
                                    (preference ?: CardRefinementPreference(cardId))
                                        .copy(granularity = value)
                                )
                            },
                        )
                    }
            }
        }
    }
}

@Composable
private fun PreferenceSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), color = Ink)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun DraftPlanItem(
    item: PlanItem,
    selected: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    SoftCard {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NeutralPill(text = item.kindLabel(), selected = true)
                    if (item.needConfirm.isNotEmpty()) {
                        Spacer(Modifier.size(6.dp))
                        Text("待确认", color = Warning, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(
                    item.title,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (item.description.isNotBlank()) {
                    Text(
                        item.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val time = item.deadline ?: item.startTime
                if (!time.isNullOrBlank()) {
                    Text(formatSmartTime(time), style = MaterialTheme.typography.bodySmall, color = BrandBlue)
                }
                Text(
                    "可信度 ${(item.confidence * 100).toInt()}%" +
                        if (item.reminderEnabled) " · 将创建提醒" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑计划项")
            }
        }
    }
}

@Composable
private fun PersistedPlanItem(item: PlanItem) {
    SoftCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeutralPill(text = item.kindLabel(), selected = true)
                Spacer(Modifier.size(8.dp))
                Text(item.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            if (item.description.isNotBlank()) {
                Text(item.description, style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            val time = item.deadline ?: item.startTime
            if (!time.isNullOrBlank()) {
                Text(formatSmartTime(time), color = BrandBlue, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DetailLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(20.dp))
        Text(text, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PlanItemEditDialog(
    item: PlanItem,
    onDismiss: () -> Unit,
    onSave: (PlanItem) -> Unit,
) {
    var draft by remember(item.id) { mutableStateOf(item) }
    var showTimePicker by remember(item.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(draft.copy(needConfirm = emptyList())) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("编辑计划项") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { draft = draft.copy(title = it) },
                    label = { Text("标题") },
                )
                OutlinedTextField(
                    value = draft.description,
                    onValueChange = { draft = draft.copy(description = it) },
                    label = { Text("执行说明") },
                    minLines = 3,
                )
                OutlinedTextField(
                    value = formatSmartTime(draft.deadline ?: draft.startTime),
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { showTimePicker = true }) {
                            Icon(Icons.Outlined.Schedule, contentDescription = "选择时间")
                        }
                    },
                    label = { Text("时间") },
                )
                if (draft.kind == PlanItemKinds.MILESTONE) {
                    PreferenceSwitch("创建节点提醒", draft.reminderEnabled) {
                        draft = draft.copy(reminderEnabled = it)
                    }
                }
            }
        },
        shape = RoundedCornerShape(24.dp),
    )
    if (showTimePicker) {
        DateTimeWheelPickerDialog(
            initialValue = draft.deadline ?: draft.startTime,
            title = if (draft.kind == PlanItemKinds.WORK_BLOCK) "选择时间块" else "选择里程碑时间",
            onDismiss = { showTimePicker = false },
            onClear = {
                draft = draft.copy(deadline = null, startTime = null, reminderEnabled = false)
                showTimePicker = false
            },
            onConfirm = { value ->
                draft = if (draft.kind == PlanItemKinds.WORK_BLOCK) {
                    draft.copy(startTime = value)
                } else {
                    draft.copy(deadline = value)
                }
                showTimePicker = false
            },
        )
    }
}

private fun PlanItem.kindLabel(): String = when (kind) {
    PlanItemKinds.MILESTONE -> "里程碑"
    PlanItemKinds.WORK_BLOCK -> "时间块"
    else -> "步骤"
}
