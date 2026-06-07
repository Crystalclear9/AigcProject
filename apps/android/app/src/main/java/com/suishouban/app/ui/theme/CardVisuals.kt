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
    CardTypes.COMPARISON -> CardVisual("对比", ComparisonGray, Color(0xFFF0F2F5))
    CardTypes.COLLECTION -> CardVisual("收藏", CollectionBrown, Color(0xFFFFF7E6))
    else -> CardVisual("任务", TaskRed, Color(0xFFFFECEC))
}

fun labelForPriority(priority: String): String = when (priority) {
    Priority.HIGH -> "高优先级"
    Priority.LOW -> "低优先级"
    else -> "普通"
}
