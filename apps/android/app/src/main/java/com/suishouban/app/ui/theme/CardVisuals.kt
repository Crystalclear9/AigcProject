package com.suishouban.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.suishouban.app.data.model.CardTypes
import com.suishouban.app.data.model.Priority

data class CardVisual(
    val label: String,
    val color: Color,
    val soft: Color,
)

data class PriorityVisual(
    val label: String,
    val accent: Color,
    val container: Color,
    val content: Color,
)

fun visualForCardType(type: String): CardVisual = when (type) {
    CardTypes.EVENT -> CardVisual("事件", EventBlue, Color(0xFFEAF2FF))
    CardTypes.PROMISE -> CardVisual("承诺", PromiseOrange, Color(0xFFFFF0E6))
    CardTypes.COMPARISON -> CardVisual("对比", ComparisonGray, Color(0xFFF0F2F5))
    CardTypes.COLLECTION -> CardVisual("收藏", CollectionBrown, Color(0xFFFFF7E6))
    else -> CardVisual("任务", TaskRed, Color(0xFFFFECEC))
}

fun labelForPriority(priority: String): String = when (priority) {
    Priority.HIGH -> "高优先级"
    Priority.LOW -> "低优先级"
    else -> "普通"
}

fun visualForPriority(priority: String): PriorityVisual = when (priority) {
    Priority.HIGH -> PriorityVisual(
        label = "高优先级",
        accent = Color(0xFFD44A4A),
        container = Color(0xFFFFF1F0),
        content = Color(0xFF8C2025),
    )
    Priority.LOW -> PriorityVisual(
        label = "低优先级",
        accent = Color(0xFF6A7D91),
        container = Color(0xFFF1F5F8),
        content = Color(0xFF34495D),
    )
    else -> PriorityVisual(
        label = "普通优先级",
        accent = Color(0xFFC9821A),
        container = Color(0xFFFFF7E7),
        content = Color(0xFF714A0A),
    )
}
