package com.suishouban.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardStatus
import com.suishouban.app.data.model.CardTypes
import com.suishouban.app.ui.theme.BrandBlue
import com.suishouban.app.ui.theme.DS
import com.suishouban.app.ui.theme.Line
import com.suishouban.app.ui.theme.MistBlue
import com.suishouban.app.ui.theme.Warning
import com.suishouban.app.ui.theme.labelForPriority
import com.suishouban.app.ui.theme.softCardShadow
import com.suishouban.app.ui.theme.visualForCardType
import com.suishouban.app.ui.theme.visualForPriority

@Composable
fun ActionCardItem(
    card: ActionCard,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onOpen: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onPriorityClick: (() -> Unit)? = null,
    onComplete: (() -> Unit)? = null,
    teamBadge: String? = null,
) {
    val visual = visualForCardType(card.cardType)
    val priorityVisual = visualForPriority(card.priority)
    val cardContainer by animateColorAsState(
        targetValue = priorityVisual.container,
        label = "card-priority-container",
    )
    val priorityAccent by animateColorAsState(
        targetValue = priorityVisual.accent,
        label = "card-priority-accent",
    )
    val timeSummary = temporalSummary(
        start = card.startTime,
        end = card.endTime,
        deadline = card.deadline,
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            .softCardShadow(DS.RadiusCard)
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
        shape = RoundedCornerShape(DS.RadiusCard),
        border = BorderStroke(1.dp, priorityAccent.copy(alpha = 0.36f)),
        colors = CardDefaults.cardColors(containerColor = cardContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        // Type-coded accent spine painted directly on the card's left edge. Using drawBehind
        // (not an IntrinsicSize Row) keeps the inner LazyRow of chips measurable — intrinsic
        // measurement of a lazy list throws at runtime.
        val spineColor = priorityAccent
        val spineColorFade = priorityAccent.copy(alpha = 0.48f)
        Column(
            Modifier
                .drawBehind {
                    drawRect(
                        brush = Brush.verticalGradient(listOf(spineColor, spineColorFade)),
                        size = androidx.compose.ui.geometry.Size(5.dp.toPx(), size.height),
                    )
                }
                .padding(start = 15.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // One quiet team badge: the team name's first character on a mist-blue chip.
                if (teamBadge != null) {
                    Text(
                        text = teamBadge,
                        color = BrandBlue,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(DS.RadiusChipBadge))
                            .background(MistBlue)
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Pill(text = visual.label, color = visual.color, soft = visual.soft)
                Spacer(Modifier.width(8.dp))
                if (card.needConfirm.isNotEmpty()) {
                    Pill(text = "待确认", color = Warning, soft = Color(0xFFFFF6DE))
                    Spacer(Modifier.width(8.dp))
                }
                Pill(
                    text = if (card.priorityLocked) {
                        labelForPriority(card.priority)
                    } else {
                        "${labelForPriority(card.priority)} · 自动"
                    },
                    color = priorityVisual.content,
                    soft = priorityVisual.accent.copy(alpha = 0.13f),
                    onClick = onPriorityClick,
                )
                Spacer(Modifier.weight(1f))
                if (onEdit != null) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Text(
                text = card.title,
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (card.summary.isNotBlank()) {
                Text(
                    text = card.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccessTime, contentDescription = null, tint = visual.color)
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        text = timeSummary.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    timeSummary.secondary?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (!card.location.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Place, contentDescription = null, tint = visual.color)
                    Spacer(Modifier.width(6.dp))
                    Text(card.location, style = MaterialTheme.typography.bodyMedium)
                }
            }

            val chips = card.tags + card.materials + card.reminders.take(2)
            if (chips.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chips) { chip ->
                        Pill(text = chip, color = visual.color, soft = visual.soft.copy(alpha = 0.72f))
                    }
                }
            }

            if (onComplete != null && card.status != CardStatus.DONE) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onComplete,
                        colors = ButtonDefaults.buttonColors(containerColor = visual.color),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("完成")
                    }
                    OutlinedButton(
                        onClick = { },
                        enabled = false,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(if (card.status == CardStatus.CONFIRMED) "已创建提醒" else "待确认")
                    }
                }
            }
        }
    }
}
