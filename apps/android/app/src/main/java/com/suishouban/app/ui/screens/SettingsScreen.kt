package com.suishouban.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.suishouban.app.AppUiState
import com.suishouban.app.data.repository.AppSettings
import com.suishouban.app.mascot.MascotState
import com.suishouban.app.mascot.MofeiMoodBanner
import com.suishouban.app.notification.InstalledAppInfo
import com.suishouban.app.data.repository.WorkflowUrlPolicy
import com.suishouban.app.data.repository.ProviderEndpointPolicy
import com.suishouban.app.data.model.AiConnectionMode
import com.suishouban.app.data.model.AutoReactPolicy
import com.suishouban.app.data.model.OcrEnhancementPolicy
import com.suishouban.app.data.model.ReminderPreset
import com.suishouban.app.data.model.WorkflowDepthPolicy
import com.suishouban.app.ui.components.SectionHeader
import com.suishouban.app.ui.theme.AccentIconChip
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.DS
import com.suishouban.app.ui.theme.Ink
import com.suishouban.app.ui.theme.Line
import com.suishouban.app.ui.theme.SoftCard

@Composable
fun SettingsScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onUpdate: (AppSettings) -> Unit,
    onSync: () -> Unit,
    onTestConnection: () -> Unit,
    onSaveProviderApiKey: (String) -> Unit,
    onClearProviderApiKey: () -> Unit,
    onMascotOverlayToggle: (Boolean) -> Unit,
    notificationAccessGranted: Boolean,
    notificationApps: List<InstalledAppInfo>,
    onOpenNotificationAccessSettings: () -> Unit,
    mascotState: MascotState,
    onClearInferredProfile: () -> Unit,
    onResetUserProfile: () -> Unit,
) {
    var apiBaseUrl by remember(state.settings.apiBaseUrl) { mutableStateOf(state.settings.apiBaseUrl) }
    var providerProfile by remember(state.settings.providerProfile) {
        mutableStateOf(state.settings.providerProfile)
    }
    var apiKeyInput by rememberSaveable { mutableStateOf("") }
    var confirmInsecureOcr by rememberSaveable { mutableStateOf(false) }
    var showMascotAdvanced by rememberSaveable { mutableStateOf(false) }
    val trimmedApiBaseUrl = apiBaseUrl.trim()
    val apiUrlAccepted = trimmedApiBaseUrl.isBlank() || WorkflowUrlPolicy.isAccepted(trimmedApiBaseUrl)
    val modeLabel = when {
        trimmedApiBaseUrl.isBlank() -> "当前为本机模式：不访问开发主机，端侧 OCR + 本地规则可完整运行。"
        apiUrlAccepted -> "将使用手机可直接访问的 HTTPS 网关。蓝心 key 只应放在后端/网关，不进入 APK。"
        else -> "地址不可用：请输入 HTTPS 网关，不能使用 127.0.0.1、localhost、10.0.2.2 或局域网开发主机。"
    }

    LazyColumn(
        modifier = Modifier.padding(horizontal = DS.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(DS.SectionGap),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = Ink)
                }
                Box(Modifier.weight(1f)) {
                    SectionHeader(
                        "设置",
                        if (state.settings.apiBaseUrl.isBlank()) "手机独立运行" else "AI 增强已配置",
                        icon = Icons.Outlined.SettingsSuggest,
                    )
                }
            }
        }
        item {
            ExpandableSettingsCard(
                title = "高级 AI 连接",
                summary = when (state.settings.aiConnectionMode) {
                    AiConnectionMode.LOCAL -> "本机模式"
                    AiConnectionMode.WORKFLOW_GATEWAY -> "完整工作流"
                    AiConnectionMode.DIRECT_API -> "直接增强"
                },
                icon = Icons.Outlined.CloudSync,
            ) {
                Text(
                    "默认无需配置。网关提供完整 Agent 图；直接 API 只增强候选，仍由本机规则校验。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChoiceRow(
                    options = listOf(
                        AiConnectionMode.LOCAL to "本机",
                        AiConnectionMode.WORKFLOW_GATEWAY to "Workflow 网关",
                        AiConnectionMode.DIRECT_API to "直接 API",
                    ),
                    selected = state.settings.aiConnectionMode,
                    onSelected = { mode ->
                        onUpdate(
                            state.settings.copy(
                                aiConnectionMode = mode,
                                preferCloudModel = mode == AiConnectionMode.WORKFLOW_GATEWAY &&
                                    WorkflowUrlPolicy.isAccepted(state.settings.apiBaseUrl),
                            )
                        )
                    },
                )
                CloudModeBanner(
                    mode = state.settings.aiConnectionMode,
                    url = when (state.settings.aiConnectionMode) {
                        AiConnectionMode.WORKFLOW_GATEWAY -> state.settings.apiBaseUrl
                        AiConnectionMode.DIRECT_API -> state.settings.providerProfile.chatUrl
                        AiConnectionMode.LOCAL -> ""
                    },
                )
                if (state.settings.aiConnectionMode == AiConnectionMode.WORKFLOW_GATEWAY) {
                    OutlinedTextField(
                        value = apiBaseUrl,
                        onValueChange = { apiBaseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Workflow HTTPS 地址") },
                        placeholder = { Text("https://workflow.example.com/") },
                        isError = !apiUrlAccepted,
                        supportingText = { Text(modeLabel) },
                        shape = RoundedCornerShape(16.dp),
                    )
                    Button(
                        onClick = {
                            onUpdate(
                                state.settings.copy(
                                    apiBaseUrl = trimmedApiBaseUrl,
                                    aiConnectionMode = AiConnectionMode.WORKFLOW_GATEWAY,
                                    preferCloudModel = trimmedApiBaseUrl.isNotBlank() && apiUrlAccepted,
                                )
                            )
                        },
                        enabled = apiUrlAccepted && trimmedApiBaseUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存网关") }
                }
                if (state.settings.aiConnectionMode == AiConnectionMode.DIRECT_API) {
                    OutlinedTextField(
                        value = providerProfile.chatUrl,
                        onValueChange = { providerProfile = providerProfile.copy(chatUrl = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("模型 HTTPS 地址") },
                        isError = providerProfile.chatUrl.isNotBlank() &&
                            ProviderEndpointPolicy.normalizeChat(providerProfile.chatUrl) == null,
                    )
                    OutlinedTextField(
                        value = providerProfile.ocrUrl,
                        onValueChange = { providerProfile = providerProfile.copy(ocrUrl = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("OCR 地址") },
                        isError = providerProfile.ocrUrl.isNotBlank() &&
                            ProviderEndpointPolicy.normalizeOcr(
                                providerProfile.ocrUrl,
                                providerProfile.allowInsecureVivoOcr,
                            ) == null,
                    )
                    SettingSwitch(
                        title = "允许 vivo 非加密 OCR",
                        checked = providerProfile.allowInsecureVivoOcr,
                        onCheckedChange = {
                            if (it) confirmInsecureOcr = true
                            else providerProfile = providerProfile.copy(allowInsecureVivoOcr = false)
                        },
                    )
                    if (providerProfile.allowInsecureVivoOcr) {
                        Text(
                            "风险：图片和 Bearer key 通过 HTTP 发送。仅允许官方固定地址，且禁止重定向。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    OutlinedTextField(
                        value = providerProfile.modelName,
                        onValueChange = { providerProfile = providerProfile.copy(modelName = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("模型名称") },
                    )
                    OutlinedTextField(
                        value = providerProfile.businessId,
                        onValueChange = { providerProfile = providerProfile.copy(businessId = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("OCR businessid") },
                        supportingText = { Text("默认支持旋转图片；通常无需修改") },
                    )
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (state.hasProviderApiKey) "替换 API key" else "API key") },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Button(
                        onClick = {
                            onUpdate(
                                state.settings.copy(
                                    aiConnectionMode = AiConnectionMode.DIRECT_API,
                                    providerProfile = providerProfile,
                                    preferCloudModel = false,
                                )
                            )
                            if (apiKeyInput.isNotBlank()) {
                                onSaveProviderApiKey(apiKeyInput)
                                apiKeyInput = ""
                            }
                        },
                        enabled = ProviderEndpointPolicy.normalizeChat(providerProfile.chatUrl) != null &&
                            ProviderEndpointPolicy.normalizeOcr(
                                providerProfile.ocrUrl,
                                providerProfile.allowInsecureVivoOcr,
                            ) != null && (state.hasProviderApiKey || apiKeyInput.isNotBlank()),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存直接连接") }
                    if (state.hasProviderApiKey) {
                        TextButton(onClick = onClearProviderApiKey) { Text("清除本机密钥") }
                    }
                }
                Text(
                    state.connectionStatus.ifBlank { "未测试服务连接" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onSync,
                    enabled = state.settings.aiConnectionMode == AiConnectionMode.WORKFLOW_GATEWAY &&
                        state.settings.preferCloudModel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("恢复服务端卡片（高级）")
                }
                OutlinedButton(
                    onClick = onTestConnection,
                    enabled = state.settings.aiConnectionMode == AiConnectionMode.LOCAL ||
                        state.settings.aiConnectionMode == AiConnectionMode.WORKFLOW_GATEWAY ||
                        state.hasProviderApiKey,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("测试服务连接")
                }
            }
        }
        item {
            ExpandableSettingsCard(
                title = "权限与隐私",
                summary = if (state.settings.privacyMask) "敏感字段脱敏已开启" else "建议开启脱敏",
                icon = Icons.Outlined.Lock,
            ) {
                SettingSwitch(
                    title = "敏感字段脱敏",
                    checked = state.settings.privacyMask,
                    onCheckedChange = { onUpdate(state.settings.copy(privacyMask = it)) },
                )
                SettingSwitch(
                    title = "保留原始截图",
                    checked = state.settings.originalImageRetentionDays > 0,
                    onCheckedChange = {
                        onUpdate(
                            state.settings.copy(
                                keepOriginalScreenshot = it,
                                originalImageRetentionDays = if (it) 1 else 0,
                            )
                        )
                    },
                )
            }
        }
        item {
            ExpandableSettingsCard(
                title = "自动化偏好",
                summary = if (state.settings.autoDetectScreenshots) "截图识别已开启" else "按需开启自动识别",
                icon = Icons.Outlined.SettingsSuggest,
            ) {
                SettingSwitch(
                    title = "截图入口提示",
                    checked = state.settings.autoDetectScreenshots,
                    onCheckedChange = {
                        onUpdate(
                            state.settings.copy(
                                autoDetectScreenshots = it,
                                importSources = state.settings.importSources.copy(
                                    screenshots = state.settings.importSources.screenshots || it,
                                ),
                            )
                        )
                    },
                )
                Text(
                    "开启后监听新截图；只有命中明确行动证据才发低打扰提示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitch(
                    title = "日历同步",
                    checked = state.settings.calendarSync,
                    onCheckedChange = { onUpdate(state.settings.copy(calendarSync = it)) },
                )
            }
        }
        item {
            ExpandableSettingsCard(
                title = "规划与个性化",
                summary = if (state.settings.cardRefinementEnabled) "深度计划可用" else "深度计划已关闭",
                icon = Icons.Outlined.SettingsSuggest,
            ) {
                SettingSwitch(
                    title = "卡片深度细化",
                    checked = state.settings.cardRefinementEnabled,
                    onCheckedChange = {
                        onUpdate(state.settings.copy(cardRefinementEnabled = it))
                    },
                )
                Text(
                    "开启后，可在已创建卡片的详情中按需上传材料并生成里程碑、时间块和执行步骤。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitch(
                    title = "使用个性化规划",
                    checked = state.settings.personalizedPlanningEnabled,
                    onCheckedChange = {
                        onUpdate(state.settings.copy(personalizedPlanningEnabled = it))
                    },
                )
                SettingSwitch(
                    title = "根据使用持续学习",
                    checked = state.settings.profileLearningEnabled,
                    onCheckedChange = {
                        onUpdate(state.settings.copy(profileLearningEnabled = it))
                    },
                )
                Text(
                    "持续学习默认关闭；开启后只汇总你接受的粒度、时间块调整和提醒偏好，至少 3 次一致信号才更新推断。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitch(
                    title = "生成可安排的时间块",
                    checked = state.settings.refinementWorkBlocksEnabled,
                    onCheckedChange = {
                        onUpdate(state.settings.copy(refinementWorkBlocksEnabled = it))
                    },
                )
                SettingSwitch(
                    title = "里程碑提醒",
                    checked = state.settings.milestoneRemindersEnabled,
                    onCheckedChange = {
                        onUpdate(state.settings.copy(milestoneRemindersEnabled = it))
                    },
                )
                Text("规划深度", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                ChoiceRow(
                    options = listOf(
                        WorkflowDepthPolicy.FAST to "快速",
                        WorkflowDepthPolicy.BALANCED to "均衡",
                        WorkflowDepthPolicy.DEEP to "深度",
                    ),
                    selected = state.settings.workflowDepthPolicy,
                    onSelected = { onUpdate(state.settings.copy(workflowDepthPolicy = it)) },
                )
                Text("自动 ReAct", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                ChoiceRow(
                    options = listOf(
                        AutoReactPolicy.OFF to "关闭",
                        AutoReactPolicy.LOW_CONFIDENCE to "低置信",
                        AutoReactPolicy.COMPLEX_TASKS to "复杂任务",
                    ),
                    selected = state.settings.autoReactPolicy,
                    onSelected = { onUpdate(state.settings.copy(autoReactPolicy = it)) },
                )
                SettingSwitch(
                    title = "历史重复检查",
                    checked = state.settings.historyDuplicateCheckEnabled,
                    onCheckedChange = {
                        onUpdate(state.settings.copy(historyDuplicateCheckEnabled = it))
                    },
                )
                SettingSwitch(
                    title = "团队依赖检查",
                    checked = state.settings.teamDependencyCheckEnabled,
                    onCheckedChange = {
                        onUpdate(state.settings.copy(teamDependencyCheckEnabled = it))
                    },
                )
                SettingSwitch(
                    title = "联网检索",
                    checked = state.settings.webRetrievalEnabled,
                    onCheckedChange = { onUpdate(state.settings.copy(webRetrievalEnabled = it)) },
                )
                Text("默认计划粒度", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("concise" to "简洁", "balanced" to "均衡", "detailed" to "详细")
                        .forEach { (value, label) ->
                            FilterChip(
                                selected = state.settings.defaultRefinementGranularity == value,
                                onClick = {
                                    onUpdate(state.settings.copy(defaultRefinementGranularity = value))
                                },
                                label = { Text(label) },
                            )
                        }
                }
                HorizontalProfileSummary(
                    scenario = state.userProfile.scenario,
                    granularity = state.userProfile.planningGranularity,
                    reminderStyle = state.userProfile.reminderStyle,
                    questionnaireCompleted = state.userProfile.questionnaireCompleted,
                )
                OutlinedButton(
                    onClick = { onUpdate(state.settings.copy(onboardingSeen = false)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(if (state.userProfile.questionnaireCompleted) "重新填写个性化问卷" else "完成个性化问卷")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onClearInferredProfile,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text("清除推断")
                    }
                    OutlinedButton(
                        onClick = onResetUserProfile,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text("重置画像")
                    }
                }
            }
        }
        item {
            ExpandableSettingsCard(
                title = "墨斐悬浮助手",
                summary = if (state.settings.mascotOverlayEnabled) "外部助手已开启" else "仅在应用内显示",
                icon = Icons.Outlined.Notifications,
            ) {
                // A live preview reacts to the current mood and the reduce-motion toggle below it.
                MofeiMoodBanner(
                    state = mascotState,
                    reduceMotion = state.settings.reduceMascotMotion,
                    spriteSize = 56.dp,
                    message = "这是墨斐当前的状态预览，会随你的事项与下方开关变化。",
                )
                SettingSwitch(
                    title = "在应用内显示墨斐宠物",
                    checked = state.settings.mascotInAppEnabled,
                    onCheckedChange = { onUpdate(state.settings.copy(mascotInAppEnabled = it)) },
                )
                Text(
                    "应用内常驻的悬浮墨斐：可拖拽吸边、轻点对话、长按菜单。无需任何权限。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitch(
                    title = "在其他应用上显示墨斐",
                    checked = state.settings.mascotOverlayEnabled,
                    // Permission navigation stays in MainActivity so this composable never opens
                    // system settings as a side effect of recomposition.
                    onCheckedChange = onMascotOverlayToggle,
                )
                Text(
                    "系统级仅在离开应用后、于其他应用上层显示为轻量状态胶囊（与应用内宠物不同）。开启时会跳转系统“显示在其他应用上层”授权页。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { showMascotAdvanced = !showMascotAdvanced },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("墨斐高级能力", modifier = Modifier.weight(1f))
                    Icon(
                        if (showMascotAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (showMascotAdvanced) "收起" else "展开",
                    )
                }
                AnimatedVisibility(visible = showMascotAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingSwitch(
                            title = "减少墨斐动态效果",
                            checked = state.settings.reduceMascotMotion,
                            onCheckedChange = { onUpdate(state.settings.copy(reduceMascotMotion = it)) },
                        )
                        SettingSwitch(
                            title = "让墨斐读取指定 App 通知",
                            checked = state.settings.mofeiNotificationDraftsEnabled,
                            onCheckedChange = {
                                onUpdate(state.settings.copy(mofeiNotificationDraftsEnabled = it))
                            },
                        )
                        Text(
                            if (notificationAccessGranted) {
                                "通知访问已授权。墨斐只读取允许名单，并且只生成待确认草稿。"
                            } else {
                                "通知访问未授权。开启功能后仍需在系统页面明确授权。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = onOpenNotificationAccessSettings,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text(if (notificationAccessGranted) "管理通知访问权限" else "前往授权通知访问")
                        }
                        if (state.settings.mofeiNotificationDraftsEnabled) {
                            Text(
                                "允许读取的 App（已选 ${state.settings.mofeiNotificationPackageAllowlist.size} 个）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            if (notificationApps.isEmpty()) {
                                Text(
                                    "未找到可选择的应用",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    notificationApps.forEach { app ->
                                        val selected = app.packageName in
                                            state.settings.mofeiNotificationPackageAllowlist
                                        FilterChip(
                                            selected = selected,
                                            onClick = {
                                                val updated = state.settings
                                                    .mofeiNotificationPackageAllowlist
                                                    .toMutableSet()
                                                    .apply {
                                                        if (selected) remove(app.packageName)
                                                        else add(app.packageName)
                                                    }
                                                    .toSet()
                                                onUpdate(
                                                    state.settings.copy(
                                                        mofeiNotificationPackageAllowlist = updated,
                                                    ),
                                                )
                                            },
                                            label = { Text(app.label) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            ExpandableSettingsCard(
                title = "提醒策略",
                summary = when (state.settings.reminderPreset) {
                    ReminderPreset.LIGHT -> "轻量 · 最多 ${state.settings.maxSuggestedReminders} 个"
                    ReminderPreset.STANDARD -> "标准 · 最多 ${state.settings.maxSuggestedReminders} 个"
                    ReminderPreset.MULTI_STAGE -> "多节点 · 最多 ${state.settings.maxSuggestedReminders} 个"
                },
                icon = Icons.Outlined.Notifications,
            ) {
                ChoiceRow(
                    options = listOf(
                        ReminderPreset.LIGHT to "轻量",
                        ReminderPreset.STANDARD to "标准",
                        ReminderPreset.MULTI_STAGE to "多节点",
                    ),
                    selected = state.settings.reminderPreset,
                    onSelected = { onUpdate(state.settings.copy(reminderPreset = it)) },
                )
                Text("最大自动建议数", style = MaterialTheme.typography.titleSmall)
                ChoiceRow(
                    options = (1..5).map { it to it.toString() },
                    selected = state.settings.maxSuggestedReminders,
                    onSelected = { onUpdate(state.settings.copy(maxSuggestedReminders = it)) },
                )
            }
        }
        item {
            ExpandableSettingsCard(
                title = "导入与 OCR",
                summary = "${listOf(
                    state.settings.importSources.screenshots,
                    state.settings.importSources.galleryImages,
                    state.settings.importSources.text,
                    state.settings.importSources.documents,
                ).count { it }} 个来源已开启",
                icon = Icons.Outlined.PhotoLibrary,
            ) {
                SettingSwitch(
                    title = "截图",
                    checked = state.settings.importSources.screenshots,
                    onCheckedChange = {
                        onUpdate(
                            state.settings.copy(
                                importSources = state.settings.importSources.copy(screenshots = it),
                                autoDetectScreenshots = state.settings.autoDetectScreenshots && it,
                            )
                        )
                    },
                )
                SettingSwitch(
                    title = "相册图片",
                    checked = state.settings.importSources.galleryImages,
                    onCheckedChange = {
                        onUpdate(state.settings.copy(importSources = state.settings.importSources.copy(galleryImages = it)))
                    },
                )
                SettingSwitch(
                    title = "文字",
                    checked = state.settings.importSources.text,
                    onCheckedChange = {
                        onUpdate(state.settings.copy(importSources = state.settings.importSources.copy(text = it)))
                    },
                )
                SettingSwitch(
                    title = "文档",
                    checked = state.settings.importSources.documents,
                    onCheckedChange = {
                        onUpdate(state.settings.copy(importSources = state.settings.importSources.copy(documents = it)))
                    },
                )
                Text("OCR 策略", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                ChoiceRow(
                    options = listOf(
                        OcrEnhancementPolicy.LOCAL_ONLY to "仅端侧",
                        OcrEnhancementPolicy.LOW_QUALITY to "低质量增强",
                        OcrEnhancementPolicy.ALWAYS_COMPARE to "双路比较",
                    ),
                    selected = state.settings.ocrEnhancementPolicy,
                    onSelected = { onUpdate(state.settings.copy(ocrEnhancementPolicy = it)) },
                )
                Text("原图保留", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                ChoiceRow(
                    options = listOf(0 to "不保留", 1 to "1 天", 7 to "7 天"),
                    selected = state.settings.originalImageRetentionDays,
                    onSelected = {
                        onUpdate(
                            state.settings.copy(
                                originalImageRetentionDays = it,
                                keepOriginalScreenshot = it > 0,
                            )
                        )
                    },
                )
            }
        }
        item {
            Spacer(Modifier.height(92.dp))
        }
    }

    if (confirmInsecureOcr) {
        AlertDialog(
            onDismissRequest = { confirmInsecureOcr = false },
            title = { Text("确认使用非加密 OCR？") },
            text = {
                Text("图片内容和 Bearer key 将通过 HTTP 传输。应用仅允许 vivo 官方固定地址，并禁止重定向，但网络链路仍不受 TLS 保护。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        providerProfile = providerProfile.copy(allowInsecureVivoOcr = true)
                        confirmInsecureOcr = false
                    }
                ) { Text("了解风险并启用") }
            },
            dismissButton = {
                TextButton(onClick = { confirmInsecureOcr = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelected(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun HorizontalProfileSummary(
    scenario: String,
    granularity: String,
    reminderStyle: String,
    questionnaireCompleted: Boolean,
) {
    val scenarioLabel = mapOf(
        "study" to "学习",
        "office" to "办公",
        "freelance" to "自由职业",
        "life" to "生活管理",
        "mixed" to "混合场景",
    )[scenario] ?: "中性画像"
    val granularityLabel = mapOf(
        "concise" to "简洁计划",
        "balanced" to "均衡计划",
        "detailed" to "详细计划",
    )[granularity] ?: "均衡计划"
    val reminderLabel = mapOf(
        "light" to "轻提醒",
        "standard" to "标准提醒",
        "multi" to "多节点提醒",
    )[reminderStyle] ?: "标准提醒"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrandBlue.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (questionnaireCompleted) "当前画像" else "当前为通用中性画像",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
        Text(
            "$scenarioLabel · $granularityLabel · $reminderLabel",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "画像不会修改标题、DDL、地点等事实字段。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CloudModeBanner(mode: AiConnectionMode, url: String) {
    val enabled = mode != AiConnectionMode.LOCAL
    val (title, subtitle) = when (mode) {
        AiConnectionMode.LOCAL ->
            "本机模式" to "截图识别、卡片和提醒均可端侧完成"
        AiConnectionMode.WORKFLOW_GATEWAY ->
            "完整工作流" to "通过 HTTPS 网关运行受控 Agent 图，手机不保存服务端密钥"
        AiConnectionMode.DIRECT_API ->
            "直接增强" to "密钥由 Android Keystore 保存；模型仅补充候选，不代替完整 Agent 图"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(
                    listOf(
                        BrandBlue.copy(alpha = if (enabled) 0.16f else 0.08f),
                        Color.White,
                    )
                ),
                RoundedCornerShape(18.dp),
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(if (enabled) BrandBlue else Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (enabled) "AI" else "端", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (enabled) {
                Text(
                    url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    SoftCard {
        Column(Modifier.padding(DS.CardPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AccentIconChip(icon = icon, accent = BrandBlue, size = 30.dp)
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
            }
            content()
        }
    }
}

@Composable
private fun ExpandableSettingsCard(
    title: String,
    summary: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    SoftCard {
        Column(
            Modifier
                .animateContentSize()
                .padding(DS.CardPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        stateDescription = if (expanded) "已展开" else "已折叠"
                    }
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AccentIconChip(icon = icon, accent = BrandBlue, size = 30.dp)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "折叠$title" else "展开$title",
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
