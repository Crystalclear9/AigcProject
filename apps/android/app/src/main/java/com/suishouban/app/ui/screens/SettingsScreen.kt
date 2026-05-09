package com.suishouban.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suishouban.app.AppUiState
import com.suishouban.app.data.repository.AppSettings
import com.suishouban.app.ui.components.SectionHeader
import com.suishouban.app.ui.theme.Line

@Composable
fun SettingsScreen(
    state: AppUiState,
    onUpdate: (AppSettings) -> Unit,
    onSync: () -> Unit,
) {
    var apiBaseUrl by remember(state.settings.apiBaseUrl) { mutableStateOf(state.settings.apiBaseUrl) }

    LazyColumn(
        modifier = Modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            SectionHeader("设置中心")
        }
        item {
            SettingsCard(title = "服务端", icon = Icons.Outlined.CloudSync) {
                OutlinedTextField(
                    value = apiBaseUrl,
                    onValueChange = { apiBaseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Base URL") },
                    shape = RoundedCornerShape(16.dp),
                )
                Button(
                    onClick = { onUpdate(state.settings.copy(apiBaseUrl = apiBaseUrl)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("保存服务地址")
                }
                Button(
                    onClick = onSync,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("同步后端卡片")
                }
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
                    title = "优先使用云端模型",
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
