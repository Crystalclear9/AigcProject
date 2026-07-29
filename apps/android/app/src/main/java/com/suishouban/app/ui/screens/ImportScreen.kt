package com.suishouban.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suishouban.app.AppUiState
import com.suishouban.app.mascot.MascotState
import com.suishouban.app.ui.components.NeutralPill
import com.suishouban.app.ui.components.SectionHeader
import com.suishouban.app.ui.theme.AccentIconChip
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.DS
import com.suishouban.app.ui.theme.Ink
import com.suishouban.app.ui.theme.Line
import com.suishouban.app.ui.theme.SoftCard

@Composable
fun ImportScreen(
    state: AppUiState,
    onPickImage: (Uri) -> Unit,
    onPickFiles: (List<Uri>) -> Unit,
    onAnalyzeText: (String) -> Unit,
    onPreview: () -> Unit,
    mascotState: MascotState,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onPickImage(uri)
    }
    val multiFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onPickFiles(uris.take(8))
    }

    LazyColumn(
        modifier = Modifier.padding(horizontal = DS.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(DS.SectionGap),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            SectionHeader("截图导入", "先识别，再确认", icon = Icons.Outlined.PhotoCamera)
        }

        if (state.loading) {
            item {
                Text(
                    mascotState.userMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SoftCard {
                Column(Modifier.padding(DS.CardPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccentIconChip(icon = Icons.Outlined.ImageSearch, accent = BrandBlue, size = 30.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("选择截图", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
                    }
                    if (state.engine.isNotBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NeutralPill(text = "识别方式 ${displayEngineLabel(state.engine)}", selected = true)
                        }
                    }
                    if (state.warnings.isNotEmpty()) {
                        Text(
                            state.warnings.distinct().joinToString("；"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { launcher.launch("image/*") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(DS.RadiusButton),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                        ) {
                            Icon(Icons.Outlined.ImageSearch, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("相册", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = { text = sampleTexts.first().second },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(DS.RadiusButton),
                        ) {
                            Icon(Icons.Outlined.TextFields, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("示例")
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            multiFileLauncher.launch(
                                arrayOf(
                                    "image/*",
                                    "text/plain",
                                    "text/markdown",
                                    "application/pdf",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(DS.RadiusButton),
                    ) {
                        Text("导入长截图、聊天记录或文档")
                    }
                    if (state.loading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp, color = BrandBlue)
                            Spacer(Modifier.width(10.dp))
                            Text("正在提取候选事项", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        item {
            SoftCard {
                Column(Modifier.padding(DS.CardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccentIconChip(icon = Icons.Outlined.TextFields, accent = BrandBlue, size = 30.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("文字识别结果", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
                    }
                    OutlinedTextField(
                        value = text.ifBlank { state.ocrText },
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(168.dp),
                        minLines = 6,
                        shape = RoundedCornerShape(DS.RadiusTile),
                        placeholder = { Text("粘贴通知、海报或聊天文字") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = DS.TileNeutral,
                            unfocusedContainerColor = DS.TileNeutral,
                            focusedIndicatorColor = BrandBlue,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    Button(
                        onClick = { onAnalyzeText(text.ifBlank { state.ocrText }) },
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(DS.RadiusButton),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                    ) {
                        Icon(Icons.Outlined.AutoFixHigh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("生成候选卡", fontWeight = FontWeight.SemiBold)
                    }
                    if (state.draftCards.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onPreview,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(DS.RadiusButton),
                        ) {
                            Text("查看候选卡", color = BrandBlue, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        item {
            SectionHeader("样例", icon = Icons.Outlined.AutoFixHigh)
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                sampleTexts.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (label, value) ->
                            NeutralPill(text = label, onClick = { text = value })
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(92.dp))
        }
    }
}

private val sampleTexts = listOf(
    "课程通知" to "请同学们在本周五晚上 22:00 前提交实验报告，提交至学习通，文件命名为学号+姓名。",
    "比赛报名" to "AIGC 创新赛报名截止时间为 5 月 15 日 23:59，请提交报名表和作品说明书，通过官网报名链接提交。",
    "社团活动" to "本周六下午 2 点在大学生活动中心集合，负责签到的同学请提前 30 分钟到场。",
    "聊天承诺" to "你明天上午能不能帮我把表格发给老师？可以，我明天上午发。",
    "会议准备" to "明天下午 3 点开组会，每个人准备 5 分钟进展汇报。",
)

private fun displayEngineLabel(engine: String): String {
    val normalized = engine.lowercase()
    return when {
        normalized.contains("lanxin") || normalized.contains("model") || normalized.contains("expert") -> "AI 增强"
        normalized.contains("ocr") || normalized.contains("rules") || normalized.contains("mlkit") -> "手机端识别"
        engine.isNotBlank() -> "智能识别"
        else -> "手机端识别"
    }
}
