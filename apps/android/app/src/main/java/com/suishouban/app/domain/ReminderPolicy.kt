package com.suishouban.app.domain

import com.suishouban.app.data.model.CardTypes
import com.suishouban.app.data.model.Priority

object ReminderPolicy {
    fun recommend(cardType: String, priority: String, hasTime: Boolean): List<String> {
        if (!hasTime) return emptyList()
        return when {
            cardType == CardTypes.EVENT -> listOf("开始前 1 天", "开始前 30 分钟")
            cardType == CardTypes.PROMISE -> listOf("约定时间前 1 小时")
            priority == Priority.HIGH -> listOf("截止前 3 天", "截止前 1 天", "截止前 3 小时", "截止前 30 分钟")
            else -> listOf("截止前 1 天", "截止前 3 小时", "截止前 30 分钟")
        }
    }
}
