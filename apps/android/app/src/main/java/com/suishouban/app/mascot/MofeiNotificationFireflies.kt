package com.suishouban.app.mascot

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suishouban.app.notification.NotificationCandidateUiModel

/** Up to three local notification candidates orbit Mofei as review-only message fireflies. */
@Composable
fun MofeiNotificationFireflies(
    candidates: List<NotificationCandidateUiModel>,
    onOpen: (String) -> Unit,
    onReject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (candidates.isEmpty()) return
    val ordered = candidates.sortedByDescending { it.postedAtMillis }
    Box(modifier = modifier.size(width = 270.dp, height = 150.dp).testTag("mofei-fireflies")) {
        ordered.take(3).forEachIndexed { index, candidate ->
            val x = (index * 78).dp
            val y = (if (index == 1) 4 else 42).dp
            Box(
                modifier = Modifier
                    .offset(x, y)
                    .width(112.dp)
                    .testTag("mofei-firefly-${candidate.id}")
                    .semantics {
                        contentDescription = "${candidate.sourceLabel} 的待确认通知：${candidate.summary}"
                        role = Role.Button
                    }
                    .clickable { onOpen(candidate.id) },
            ) {
                Image(
                    painter = painterResource(com.suishouban.app.R.drawable.mofei_action_notification_drafts),
                    contentDescription = null,
                    modifier = Modifier.size(54.dp).align(Alignment.TopCenter),
                )
                Column(
                    modifier = Modifier
                        .padding(top = 43.dp)
                        .background(Color(0xEB092452), RoundedCornerShape(18.dp, 18.dp, 18.dp, 5.dp))
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                ) {
                    Text(
                        candidate.sourceLabel,
                        color = Color(0xFF85EDFF),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        candidate.summary,
                        color = Color.White,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "×",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(28.dp)
                        .background(Color(0xD97A42F4), CircleShape)
                        // Keep the nested reject action as its own semantics boundary. If the
                        // tag precedes clickable, the parent card can merge it with "open".
                        .clickable { onReject(candidate.id) }
                        .testTag("mofei-firefly-reject-${candidate.id}")
                        .semantics { contentDescription = "忽略这条通知草稿" }
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
        }
        val overflow = ordered.size - 3
        if (overflow > 0) {
            Text(
                text = "+$overflow",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(Color(0xE67A42F4), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
    }
}
