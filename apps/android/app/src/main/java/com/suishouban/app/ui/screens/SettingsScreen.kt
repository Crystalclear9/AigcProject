package com.suishouban.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suishouban.app.AppUiState
import com.suishouban.app.data.repository.AppSettings
import com.suishouban.app.ui.components.SectionHeader
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.Line

@Composable
fun SettingsScreen(
    state: AppUiState,
    onUpdate: (AppSettings) -> Unit,
    onSync: () -> Unit,
    onTestConnection: () -> Unit,
) {
    var apiBaseUrl by remember(state.settings.apiBaseUrl) { mutableStateOf(state.settings.apiBaseUrl) }
    val trimmedApiBaseUrl = apiBaseUrl.trim()
    val apiUrlAccepted = trimmedApiBaseUrl.isBlank() || isAcceptedWorkflowUrl(trimmedApiBaseUrl)
    val modeLabel = when {
        trimmedApiBaseUrl.isBlank() -> "当前为本机模式：不访问开发主机，端侧 OCR + 本地规则可完整运行。"
        apiUrlAccepted -> "将使用手机可直接访问的 HTTPS 网关。蓝心 key 只应放在后端/网关，不进入 APK。"
        else -> "地址不可用：请输入 HTTPS 网关，不能使用 127.0.0.1、localhost、10.0.2.2 或局域网开发主机。"
    }

    LazyColumn(
        modifier = Modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            SectionHeader("设置中心")
        }
        item {
            SettingsCard(title = "云端增强（可选）", icon = Icons.Outlined.CloudSync) {
                Text(
                    "不配置也可使用端侧 OCR、截图判定、本地卡片和提醒。配置手机可访问的 HTTPS 工作流网关后，可启用 AI 增强、SSE 进度和多端同步。",
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
                    label = { Text("Workflow API URL，可留空") },
                    placeholder = { Text("https://api.example.com/") },
                    isError = !apiUrlAccepted,
                    supportingText = {
                        Text(modeLabel)
                    },
                    shape = RoundedCornerShape(16.dp),
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
                    Text("保存增强端点")
                }
                OutlinedButton(
                    onClick = onSync,
                    enabled = state.settings.preferCloudModel && state.settings.apiBaseUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("从云端同步卡片")
                }
                OutlinedButton(
                    onClick = onTestConnection,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("测试增强服务")
                }
                Text(
                    state.connectionStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsCard(title = "权限与隐私", icon = Icons.Outlined.Lock) {
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
            SettingsCard(title = "自动化偏好", icon = Icons.Outlined.SettingsSuggest) {
                SettingSwitch(
                    title = "截图入口提示",
                    checked = state.settings.autoDetectScreenshots,
                    onCheckedChange = { onUpdate(state.settings.copy(autoDetectScreenshots = it)) },
                )
                SettingSwitch(
                    title = "启用云端增强",
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
            SettingsCard(title = "提醒策略", icon = Icons.Outlined.Notifications) {
                Text(
                    "高优先级：3 天 / 1 天 / 3 小时 / 30 分钟",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "会议事件：1 天 / 30 分钟",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsCard(title = "截图来源", icon = Icons.Outlined.PhotoLibrary) {
                Text(
                    "当前版本支持截图监听通知、相册、系统分享和文字粘贴。",
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

private fun isAcceptedWorkflowUrl(value: String): Boolean {
    val lower = value.lowercase()
    if (!lower.startsWith("https://")) return false
    val host = lower.removePrefix("https://").substringBefore("/").substringBefore(":")
    return host !in setOf("localhost", "127.0.0.1", "0.0.0.0", "10.0.2.2", "::1") &&
        !host.endsWith(".local") &&
        !isPrivateIpHost(host)
}

private fun isPrivateIpHost(host: String): Boolean {
    val parts = host.split(".").mapNotNull { it.toIntOrNull() }
    if (parts.size != 4) return false
    val first = parts[0]
    val second = parts[1]
    return first == 10 ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 168) ||
        (first == 169 && second == 254)
}

@Composable
private fun CloudModeBanner(enabled: Boolean, url: String) {
    val title = if (enabled) "云端增强已准备" else "本机模式运行"
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
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            content()
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
