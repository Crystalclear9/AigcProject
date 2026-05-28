package com.suishouban.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.suishouban.app.AppUiState
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.ui.components.DraftEditor
import com.suishouban.app.ui.components.PreviewActionsCard
import com.suishouban.app.ui.components.SectionHeader
import com.suishouban.app.ui.components.WorkflowStrip
import com.suishouban.app.ui.theme.Line

@Composable
fun PreviewScreen(
    state: AppUiState,
    onUpdateDraft: (ActionCard) -> Unit,
    onRemoveDraft: (String) -> Unit,
    onConfirm: () -> Unit,
    onImport: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            SectionHeader("动作预览", if (state.engine.isBlank()) null else state.engine)
        }

        item {
            WorkflowStrip(currentStep = 2, modifier = Modifier.fillMaxWidth())
        }

        if (state.draftCards.isEmpty()) {
            item {
                EmptyPreviewCard(onImport)
            }
        } else {
            item {
                PreviewActionsCard(previewActions = state.previewActions)
            }

            items(state.draftCards, key = { it.id }) { card ->
                DraftEditor(
                    card = card,
                    onChange = onUpdateDraft,
                    onRemove = { onRemoveDraft(card.id) },
                )
            }

            item {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("确认创建提醒与行动卡")
                }
            }
        }

        item {
            Spacer(Modifier.height(92.dp))
        }
    }
}

@Composable
private fun EmptyPreviewCard(onImport: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("暂无预览", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onImport, shape = RoundedCornerShape(16.dp)) {
                Text("导入截图")
            }
        }
    }
}

