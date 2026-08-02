package com.suishouban.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.suishouban.app.TeamSummary
import com.suishouban.app.TeamUiState
import com.suishouban.app.ui.components.NeutralPill
import com.suishouban.app.ui.components.Pill
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.DS
import com.suishouban.app.ui.theme.Ink
import com.suishouban.app.ui.theme.Muted
import com.suishouban.app.ui.theme.ScreenTitle
import com.suishouban.app.ui.theme.SoftCard
import com.suishouban.app.ui.theme.TaskRed

@Composable
fun TeamScreen(
    state: TeamUiState,
    onSaveNickname: (String) -> Unit,
    onCreateTeam: (String, (String?) -> Unit) -> Unit,
    onJoinTeam: (String, (String?) -> Unit) -> Unit,
    onRefresh: () -> Unit,
    onOpenTeam: (String) -> Unit,
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var showJoinDialog by rememberSaveable { mutableStateOf(false) }

    // The server is the source of truth; pull once per entry and let Room drive the list.
    LaunchedEffect(Unit) { onRefresh() }

    LazyColumn(
        modifier = Modifier.padding(horizontal = DS.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(DS.ItemGap),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            ScreenTitle(eyebrow = "协作", title = "团队")
        }
        if (state.nickname.isBlank()) {
            item { NicknameCard(onSaveNickname) }
        } else {
            state.error?.let { error ->
                item {
                    Text(error, style = MaterialTheme.typography.labelMedium, color = TaskRed)
                }
            }
            if (state.teams.isEmpty() && !state.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "还没有团队，创建一个或输入邀请码加入",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            items(state.teams, key = { it.id }) { team ->
                TeamCard(team = team, onOpen = { onOpenTeam(team.id) })
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DS.ItemGap),
                ) {
                    Button(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(DS.RadiusButton),
                    ) {
                        Text("创建团队", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { showJoinDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(DS.RadiusButton),
                    ) {
                        Text("邀请码加入", fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showCreateDialog) {
        TeamFieldDialog(
            title = "创建团队",
            placeholder = "团队名称",
            confirmLabel = "确认",
            onSubmit = onCreateTeam,
            onDismiss = { showCreateDialog = false },
        )
    }
    if (showJoinDialog) {
        TeamFieldDialog(
            title = "邀请码加入",
            placeholder = "6 位邀请码",
            confirmLabel = "确认",
            uppercase = true,
            onSubmit = onJoinTeam,
            onDismiss = { showJoinDialog = false },
        )
    }
}

/** Inline identity setup: one nickname field, one action — no full-screen onboarding. */
@Composable
private fun NicknameCard(onSaveNickname: (String) -> Unit) {
    var nickname by rememberSaveable { mutableStateOf("") }
    SoftCard {
        Column(Modifier.padding(DS.CardPadding), verticalArrangement = Arrangement.spacedBy(DS.ItemGap)) {
            Text(
                "先取一个昵称，团队成员会看到它",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("你的昵称") },
                singleLine = true,
                shape = RoundedCornerShape(DS.RadiusTile),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = DS.TileNeutral,
                    unfocusedContainerColor = DS.TileNeutral,
                    focusedIndicatorColor = BrandBlue,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
            Button(
                onClick = { onSaveNickname(nickname) },
                modifier = Modifier.fillMaxWidth(),
                enabled = nickname.isNotBlank(),
                shape = RoundedCornerShape(DS.RadiusButton),
            ) {
                Text("开始协作", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TeamCard(team: TeamSummary, onOpen: () -> Unit) {
    SoftCard(radius = DS.RadiusTile) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = DS.CardPadding, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    team.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
                Text(
                    "${team.memberCount} 人",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted,
                )
            }
            if (team.myRole == "owner") Pill("队长") else NeutralPill("成员")
        }
    }
}

/**
 * The one dialog shape shared by create and join: a single field plus 确认/取消, with failures
 * shown inline instead of a toast so the user can correct and retry in place.
 */
@Composable
private fun TeamFieldDialog(
    title: String,
    placeholder: String,
    confirmLabel: String,
    onSubmit: (String, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    uppercase: Boolean = false,
) {
    var value by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    fun submit() {
        if (value.isBlank() || submitting) return
        submitting = true
        error = null
        onSubmit(value.trim()) { failure ->
            submitting = false
            if (failure == null) onDismiss() else error = failure
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = if (uppercase) it.uppercase() else it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    shape = RoundedCornerShape(DS.RadiusTile),
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = TaskRed)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = value.isNotBlank() && !submitting) {
                Text(if (submitting) "提交中…" else confirmLabel, color = BrandBlue, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Muted)
            }
        },
    )
}
