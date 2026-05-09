package com.suishouban.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.suishouban.app.data.model.CardTypes
import com.suishouban.app.data.model.Priority

data class CardVisual(
    val label: String,
    val color: Color,
    val soft: Color,
)

fun visualForCardType(type: String): CardVisual = when (type) {
    CardTypes.EVENT -> CardVisual("事件", EventBlue, Color(0xFFEAF2FF))
    CardTypes.PROMISE -> CardVisual("承诺", PromiseOrange, Color(0xFFFFF0E6))
    CardTypes.NOTE -> CardVisual("资料", NoteGreen, Color(0xFFEAF8F1))
    else -> CardVisual("任务", TaskRed, Color(0xFFFFECEC))
}

fun labelForPriority(priority: String): String = when (priority) {
    Priority.HIGH -> "高优先级"
    Priority.LOW -> "低优先级"
    else -> "普通"
}
