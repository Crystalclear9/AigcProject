package com.suishouban.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardStatus
import com.suishouban.app.data.model.CardTypes
import com.suishouban.app.ui.theme.Line
import com.suishouban.app.ui.theme.Warning
import com.suishouban.app.ui.theme.labelForPriority
import com.suishouban.app.ui.theme.visualForCardType

@Composable
fun ActionCardItem(
    card: ActionCard,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onEdit: (() -> Unit)? = null,
    onComplete: (() -> Unit)? = null,
) {
    val visual = visualForCardType(card.cardType)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Line.copy(alpha = 0.65f)),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.94f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(text = visual.label, color = visual.color, soft = visual.soft)
                Spacer(Modifier.width(8.dp))
                if (card.needConfirm.isNotEmpty()) {
                    Pill(text = "待确认", color = Warning, soft = Color(0xFFFFF6DE))
                    Spacer(Modifier.width(8.dp))
                }
                Pill(text = labelForPriority(card.priority), color = visual.color, soft = visual.soft.copy(alpha = 0.62f))
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
                Text(
                    text = formatSmartTime(if (card.cardType == CardTypes.EVENT) card.startTime else card.deadline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
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
