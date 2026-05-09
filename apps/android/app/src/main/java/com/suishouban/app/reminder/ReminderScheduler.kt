package com.suishouban.app.reminder

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardTypes
import com.suishouban.app.data.model.primaryTime
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

class ReminderScheduler(private val context: Context) {
    fun schedule(card: ActionCard) {
        val time = parseTime(card.primaryTime()) ?: return
        val reminders = if (card.reminders.isEmpty()) listOf(defaultReminder(card.cardType)) else card.reminders
        reminders.forEach { reminder ->
            val delay = Duration.between(OffsetDateTime.now(), time.minus(offsetFor(reminder)))
            if (!delay.isNegative && !delay.isZero) {
                val data = Data.Builder()
                    .putString(CardReminderWorker.KEY_TITLE, "随手办：${card.title}")
                    .putString(CardReminderWorker.KEY_BODY, buildBody(card, reminder))
                    .build()
                val request = OneTimeWorkRequestBuilder<CardReminderWorker>()
                    .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                    .setInputData(data)
                    .addTag("card:${card.id}")
                    .build()
                WorkManager.getInstance(context).enqueue(request)
            }
        }
    }

    private fun parseTime(value: String?): OffsetDateTime? {
        if (value.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(value) }.getOrNull()
    }

    private fun defaultReminder(cardType: String): String {
        return if (cardType == CardTypes.EVENT) "开始前 30 分钟" else "截止前 1 小时"
    }

    private fun offsetFor(reminder: String): Duration {
        val number = Regex("(\\d+)").find(reminder)?.value?.toLongOrNull() ?: 1L
        return when {
            "天" in reminder -> Duration.ofDays(number)
            "小时" in reminder -> Duration.ofHours(number)
            "分钟" in reminder -> Duration.ofMinutes(number)
            else -> Duration.ofHours(1)
        }
    }

    private fun buildBody(card: ActionCard, reminder: String): String {
        val typeName = when (card.cardType) {
            CardTypes.EVENT -> "日程"
            CardTypes.PROMISE -> "承诺"
            CardTypes.NOTE -> "资料"
            else -> "任务"
        }
        return "$typeName「${card.title}」$reminder。${card.summary}"
    }
}
