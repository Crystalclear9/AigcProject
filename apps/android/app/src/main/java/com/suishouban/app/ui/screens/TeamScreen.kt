package com.suishouban.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suishouban.app.TeamSummary
import com.suishouban.app.TeamUiState
import com.suishouban.app.ui.components.NeutralPill
import com.suishouban.app.ui.components.Pill
import com.suishouban.app.ui.theme.AccentIconChip
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.DS
import com.suishouban.app.ui.theme.DsSectionHeader
import com.suishouban.app.ui.theme.Ink
import com.suishouban.app.ui.theme.Muted
import com.suishouban.app.ui.theme.ScreenTitle
import com.suishouban.app.ui.theme.SoftCard
import com.suishouban.app.ui.theme.TaskRed

@Composable
fun TeamScreen(
    state: TeamUiState,
    onSaveNickname: (String, (String?) -> Unit) -> Unit,
    onCreateTeam: (String, (String?) -> Unit) -> Unit,
    onJoinTeam: (String, (String?) -> Unit) -> Unit,
    onRefresh: () -> Unit,
    onOpenTeam: (String) -> Unit,
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var showJoinDialog by rememberSaveable { mutableStateOf(false) }
    var showNicknameDialog by rememberSaveable { mutableStateOf(false) }

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
            item {
                AccountNameRow(
                    nickname = state.nickname,
                    onEdit = { showNicknameDialog = true },
                )
            }
            // Create/join live directly under the title so they are always reachable,
            // whether the list below is empty or long.
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DS.ItemGap),
                ) {
                    Button(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        shape = RoundedCornerShape(DS.RadiusButton),
                    ) {
                        Text("创建团队", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { showJoinDialog = true },
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        shape = RoundedCornerShape(DS.RadiusButton),
                    ) {
                        Text("邀请码加入", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            state.error?.let { error ->
                item {
                    Text(
                        error,
                        style = MaterialTheme.typography.labelSmall,
                        color = TaskRed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(DS.RadiusChipBadge))
                            .background(TaskRed.copy(alpha = 0.08f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            if (state.teams.isEmpty() && !state.loading) {
                item { EmptyTeamsCard() }
            }
            if (state.teams.isNotEmpty()) {
                item {
                    DsSectionHeader(
                        title = "我的团队",
                        icon = Icons.Outlined.Groups,
                        trailing = "${state.teams.size} 个",
                    )
                }
            }
            items(state.teams, key = { it.id }) { team ->
                TeamCard(team = team, onOpen = { onOpenTeam(team.id) })
            }
            item { Spacer(Modifier.height(92.dp)) }
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
    if (showNicknameDialog) {
        TeamFieldDialog(
            title = "修改账号名称",
            placeholder = "你的昵称",
            confirmLabel = "保存",
            initialValue = state.nickname,
            onSubmit = onSaveNickname,
            onDismiss = { showNicknameDialog = false },
        )
    }
}

@Composable
private fun AccountNameRow(nickname: String, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DS.RadiusTile))
            .background(DS.TileNeutral)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccentIconChip(icon = Icons.Outlined.Person, size = 30.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("当前账号", style = MaterialTheme.typography.labelSmall, color = Muted)
            Text(
                nickname,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = "修改账号名称", tint = BrandBlue)
        }
    }
}

/** Inline identity setup: one nickname field, one action — no full-screen onboarding. */
@Composable
private fun NicknameCard(onSaveNickname: (String, (String?) -> Unit) -> Unit) {
    var nickname by rememberSaveable { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    SoftCard {
        Column(Modifier.padding(DS.CardPadding), verticalArrangement = Arrangement.spacedBy(DS.ItemGap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccentIconChip(icon = Icons.Outlined.Groups, size = 30.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "开启团队协作",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
            }
            Text(
                "先取一个昵称，团队成员会看到它",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("你的昵称") },
                singleLine = true,
                enabled = !submitting,
                shape = RoundedCornerShape(DS.RadiusTile),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = DS.TileNeutral,
                    unfocusedContainerColor = DS.TileNeutral,
                    focusedIndicatorColor = BrandBlue,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
            error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = TaskRed)
            }
            Button(
                onClick = {
                    if (submitting) return@Button
                    submitting = true
                    error = null
                    onSaveNickname(nickname.trim()) { failure ->
                        submitting = false
                        error = failure
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = nickname.isNotBlank() && !submitting,
                shape = RoundedCornerShape(DS.RadiusButton),
            ) {
                Text(if (submitting) "保存中…" else "开始协作", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Empty state as a proper card: an icon chip and two lines of guidance; actions live above. */
@Composable
private fun EmptyTeamsCard() {
    SoftCard(radius = DS.RadiusTile) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DS.CardPadding, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AccentIconChip(icon = Icons.Outlined.Groups, size = 44.dp)
            Text(
                "把小组搬进随手办",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
            )
            Text(
                "课程小组、比赛队伍、社团部门——\n创建团队，或输入队友分享的邀请码加入",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
            )
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
                .padding(horizontal = DS.CardPadding, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(DS.RadiusChipBadge))
                    .background(BrandBlue.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    team.name.firstOrNull()?.toString() ?: "团",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandBlue,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    team.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildString {
                    append("${team.memberCount} 名成员")
                    if (team.myRole == "owner" && team.inviteCode.isNotBlank()) {
                        append(" · 邀请码 ${team.inviteCode}")
                    }
                }
                Text(
                    meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (team.myRole == "owner") Pill("队长") else NeutralPill("成员")
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = Muted,
                modifier = Modifier.size(18.dp),
            )
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
    initialValue: String = "",
) {
    var value by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
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
