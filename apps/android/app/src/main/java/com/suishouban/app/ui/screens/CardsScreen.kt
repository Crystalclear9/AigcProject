package com.suishouban.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suishouban.app.AppUiState
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardStatus
import com.suishouban.app.data.model.CardTypes
import com.suishouban.app.ui.components.ActionCardItem
import com.suishouban.app.ui.components.NeutralPill
import com.suishouban.app.ui.components.SectionHeader

@Composable
fun CardsScreen(
    state: AppUiState,
    onUpdate: (ActionCard) -> Unit,
    onComplete: (String) -> Unit,
    onArchive: (String) -> Unit,
    onImport: () -> Unit,
) {
    var type by rememberSaveable { mutableStateOf("all") }
    var status by rememberSaveable { mutableStateOf("active") }
    var keyword by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<ActionCard?>(null) }

    val filtered = state.cards.filter { card ->
        (type == "all" || card.cardType == type) &&
            when (status) {
                "active" -> card.status != CardStatus.DONE && card.status != CardStatus.ARCHIVED
                "done" -> card.status == CardStatus.DONE
                "archived" -> card.status == CardStatus.ARCHIVED
                else -> true
            } &&
            (keyword.isBlank() || card.title.contains(keyword, ignoreCase = true) || card.summary.contains(keyword, ignoreCase = true) || card.sourceText.contains(keyword, ignoreCase = true))
    }

    LazyColumn(
        modifier = Modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            SectionHeader("卡片中心", "${filtered.size} 张")
        }
        item {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("搜索标题、摘要、原始截图文字") },
                shape = RoundedCornerShape(18.dp),
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "all" to "全部",
                        CardTypes.TASK to "任务",
                        CardTypes.EVENT to "事件",
                        CardTypes.PROMISE to "承诺",
                        CardTypes.NOTE to "资料",
                    ).forEach { (value, label) ->
                        NeutralPill(text = label, selected = type == value, onClick = { type = value })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "active" to "进行中",
                        "done" to "已完成",
                        "archived" to "归档",
                        "all" to "全部状态",
                    ).forEach { (value, label) ->
                        NeutralPill(text = label, selected = status == value, onClick = { status = value })
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("暂无匹配卡片", style = MaterialTheme.typography.titleLarge)
                    Button(onClick = onImport, shape = RoundedCornerShape(16.dp)) {
                        Text("导入截图")
                    }
                }
            }
        } else {
            items(filtered, key = { it.id }) { card ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionCardItem(
                        card = card,
                        onEdit = { editing = card },
                        onComplete = if (card.status == CardStatus.DONE) null else ({ onComplete(card.id) }),
                    )
                    if (card.status != CardStatus.ARCHIVED) {
                        Button(
                            onClick = { onArchive(card.id) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text("归档")
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(92.dp))
        }
    }

    editing?.let { card ->
        EditCardDialog(
            card = card,
            onDismiss = { editing = null },
            onSave = {
                onUpdate(it)
                editing = null
            },
        )
    }
}

@Composable
private fun EditCardDialog(
    card: ActionCard,
    onDismiss: () -> Unit,
    onSave: (ActionCard) -> Unit,
) {
    var draft by remember(card.id) { mutableStateOf(card) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        title = { Text("编辑行动卡") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { draft = draft.copy(title = it) },
                    label = { Text("标题") },
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = draft.summary,
                    onValueChange = { draft = draft.copy(summary = it) },
                    label = { Text("摘要") },
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = draft.deadline ?: draft.startTime ?: "",
                    onValueChange = {
                        draft = if (draft.cardType == CardTypes.EVENT) draft.copy(startTime = it.ifBlank { null })
                        else draft.copy(deadline = it.ifBlank { null })
                    },
                    label = { Text("时间") },
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = draft.location.orEmpty(),
                    onValueChange = { draft = draft.copy(location = it.ifBlank { null }) },
                    label = { Text("地点/平台") },
                    shape = RoundedCornerShape(14.dp),
                )
            }
        },
        shape = RoundedCornerShape(24.dp),
    )
}
