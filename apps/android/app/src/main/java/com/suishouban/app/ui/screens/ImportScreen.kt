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
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.suishouban.app.ui.components.NeutralPill
import com.suishouban.app.ui.components.SectionHeader
import com.suishouban.app.ui.components.brandGradient
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.Line

@Composable
fun ImportScreen(
    state: AppUiState,
    onPickImage: (Uri) -> Unit,
    onAnalyzeText: (String) -> Unit,
    onPreview: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onPickImage(uri)
    }

    LazyColumn(
        modifier = Modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            SectionHeader("截图导入", state.engine.ifBlank { "OCR + AI" })
        }

        item {
            Card(
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Line),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("选择截图", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { launcher.launch("image/*") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Outlined.ImageSearch, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("相册")
                        }
                        OutlinedButton(
                            onClick = { text = sampleTexts.first().second },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Outlined.TextFields, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("示例")
                        }
                    }
                    if (state.loading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("正在生成行动卡", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.96f)),
                border = BorderStroke(1.dp, Line),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("文字识别结果", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = text.ifBlank { state.ocrText },
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(168.dp),
                        minLines = 6,
                        shape = RoundedCornerShape(18.dp),
                        placeholder = { Text("粘贴通知、海报或聊天文字") },
                    )
                    Button(
                        onClick = { onAnalyzeText(text.ifBlank { state.ocrText }) },
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Outlined.AutoFixHigh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("生成行动卡")
                    }
                    if (state.draftCards.isNotEmpty()) {
                        Button(
                            onClick = onPreview,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = BrandBlue),
                        ) {
                            Text("查看动作预览")
                        }
                    }
                }
            }
        }

        item {
            SectionHeader("高频场景")
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
