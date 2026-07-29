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
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
    onUpdate: (AppSettings) -> Unit,
    onSync: () -> Unit,
    onTestConnection: () -> Unit,
    onMascotOverlayToggle: (Boolean) -> Unit,
    notificationAccessGranted: Boolean,
    notificationApps: List<InstalledAppInfo>,
    onOpenNotificationAccessSettings: () -> Unit,
    mascotState: MascotState,
    onClearInferredProfile: () -> Unit,
    onResetUserProfile: () -> Unit,
) {
    var apiBaseUrl by remember(state.settings.apiBaseUrl) { mutableStateOf(state.settings.apiBaseUrl) }
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
            SectionHeader("设置", if (state.settings.apiBaseUrl.isBlank()) "手机独立运行" else "AI 增强已配置", icon = Icons.Outlined.SettingsSuggest)
        }
        item {
            ExpandableSettingsCard(
                title = "AI 增强服务",
                summary = if (
                    state.settings.preferCloudModel &&
                    state.settings.apiBaseUrl.isNotBlank() &&
                    WorkflowUrlPolicy.isAccepted(state.settings.apiBaseUrl)
                ) {
                    "已配置 HTTPS 网关"
                } else {
                    "当前使用端侧能力"
                },
                icon = Icons.Outlined.CloudSync,
            ) {
                Text(
                    "手机只填写随手办 HTTPS 服务地址；vivo key 只放后端。留空时仍可用本机 OCR、规则、卡片和提醒。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CloudModeBanner(
                    enabled = state.settings.preferCloudModel && state.settings.apiBaseUrl.isNotBlank(),
                    url = state.settings.apiBaseUrl,
                )
                OutlinedTextField(
                    value = apiBaseUrl,
                    onValueChange = { apiBaseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("服务地址，可留空") },
                    placeholder = { Text("https://api.example.com/") },
                    isError = !apiUrlAccepted,
                    supportingText = {
                        Text(modeLabel)
                    },
                    shape = RoundedCornerShape(16.dp),
                )
                Text(
                    state.connectionStatus.ifBlank { "未测试服务连接" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        onUpdate(
                            state.settings.copy(
                                apiBaseUrl = trimmedApiBaseUrl,
                                preferCloudModel = trimmedApiBaseUrl.isNotBlank() && apiUrlAccepted,
                            )
                        )
                    },
                    enabled = apiUrlAccepted,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("保存服务地址")
                }
                OutlinedButton(
                    onClick = onSync,
                    enabled = state.settings.preferCloudModel && state.settings.apiBaseUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("恢复服务端卡片（高级）")
                }
                OutlinedButton(
                    onClick = onTestConnection,
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
                    checked = state.settings.keepOriginalScreenshot,
                    onCheckedChange = { onUpdate(state.settings.copy(keepOriginalScreenshot = it)) },
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
                    onCheckedChange = { onUpdate(state.settings.copy(autoDetectScreenshots = it)) },
                )
                Text(
                    "开启后监听新截图；只有命中明确行动证据才发低打扰提示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitch(
                    title = "启用 AI 增强",
                    checked = state.settings.preferCloudModel,
                    onCheckedChange = { onUpdate(state.settings.copy(preferCloudModel = it)) },
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
                title = "提醒策略说明",
                summary = "按截止距离自动安排，不伪造时间",
                icon = Icons.Outlined.Notifications,
            ) {
                Text(
                    "有截止时间：按距离自动安排 1 天、3 小时、30 分钟或尽快提醒。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "无明确时间的候选只保存卡片，不伪造提醒。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            ExpandableSettingsCard(
                title = "支持的导入来源",
                summary = "截图、相册、拍照与文字",
                icon = Icons.Outlined.PhotoLibrary,
            ) {
                Text(
                    "当前版本支持截图监听、相册、拍照和文字粘贴；不接收系统分享内容。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Spacer(Modifier.height(92.dp))
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
private fun CloudModeBanner(enabled: Boolean, url: String) {
    val title = if (enabled) "AI 增强已准备" else "手机独立运行"
    val subtitle = if (enabled) {
        "手机将访问 HTTPS 网关，蓝心 key 仅在后端保存"
    } else {
        "不依赖开发主机，截图识别、卡片和提醒都可端侧完成"
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
