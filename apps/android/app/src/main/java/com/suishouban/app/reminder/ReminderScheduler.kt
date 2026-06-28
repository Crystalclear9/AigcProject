package com.suishouban.app.reminder

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardTypes
import com.suishouban.app.data.model.primaryTime
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class ReminderScheduleResult(
    val scheduledCount: Int,
    val message: String,
) {
    val scheduled: Boolean get() = scheduledCount > 0
}

class ReminderScheduler(private val context: Context) {
    fun schedule(card: ActionCard): ReminderScheduleResult {
        val scheduledTime = card.deadline?.takeIf { it.isNotBlank() } ?: card.primaryTime()
        val time = parseTime(scheduledTime)
            ?: return ReminderScheduleResult(0, "已保存，未设置提醒：缺少可用时间")
        val now = OffsetDateTime.now()
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(cardTag(card.id))

        var scheduledCount = 0
        reminderOffsetsFor(card, now, time).forEach { offset ->
            val triggerAt = if (offset.isZero) now.plusSeconds(5) else time.minus(offset)
            val delay = Duration.between(now, triggerAt)
            if (delay.isNegative) return@forEach

            val data = Data.Builder()
                .putString(CardReminderWorker.KEY_TITLE, "随手办：${card.title}")
                .putString(CardReminderWorker.KEY_BODY, buildBody(card, offset, time))
                .build()
            val request = OneTimeWorkRequestBuilder<CardReminderWorker>()
                .setInitialDelay(delay.toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(data)
                .addTag(cardTag(card.id))
                .build()
            workManager.enqueueUniqueWork(
                uniqueWorkName(card, offset),
                ExistingWorkPolicy.REPLACE,
                request,
            )
            scheduledCount += 1
        }
        return if (scheduledCount > 0) {
            ReminderScheduleResult(scheduledCount, "已创建 $scheduledCount 个截止提醒：${card.title}")
        } else {
            ReminderScheduleResult(0, "已保存，未设置提醒：时间已过或提醒时间不可用")
        }
    }

    private fun uniqueWorkName(card: ActionCard, offset: Duration): String {
        val prefix = if (card.hasDeadline()) "deadline" else "reminder"
        return "$prefix:${card.id}:${offset.toMinutes()}"
    }

    private fun cardTag(cardId: String): String = "card:$cardId"

    private fun parseTime(value: String?): OffsetDateTime? {
        if (value.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(value) }.getOrNull()
    }

    private fun buildBody(card: ActionCard, offset: Duration, time: OffsetDateTime): String {
        val title = card.title.ifBlank { "行动事项" }
        if (card.hasDeadline()) {
            val prefix = if (offset.isZero) "截止时间快到了" else "还有约${formatOffset(offset)}截止"
            return "$prefix：$title（${time.format(DISPLAY_TIME)}）"
        }
        val typeName = when (card.cardType) {
            CardTypes.EVENT -> "日程"
            CardTypes.PROMISE -> "承诺"
            CardTypes.COMPARISON -> "对比"
            CardTypes.COLLECTION -> "收藏"
            else -> "任务"
        }
        val reminder = if (offset.isZero) "现在" else "${formatOffset(offset)}前"
        return "$typeName：$title，${reminder}提醒"
    }

    companion object {
        private val DISPLAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
        private val THIRTY_MINUTES: Duration = Duration.ofMinutes(30)
        private val THREE_HOURS: Duration = Duration.ofHours(3)
        private val ONE_DAY: Duration = Duration.ofDays(1)

        internal fun reminderOffsetsFor(
            card: ActionCard,
            now: OffsetDateTime,
            time: OffsetDateTime,
        ): List<Duration> {
            val parsedExisting = card.reminders.mapNotNull { offsetFor(it) }
            val defaults = if (card.hasDeadline()) {
                smartDeadlineOffsets(now, time)
            } else {
                parsedExisting.ifEmpty { defaultOffsets(card.cardType) }
            }
            val merged = if (card.hasDeadline()) parsedExisting + defaults else defaults
            return merged
                .distinctBy { it.toMinutes() }
                .sortedByDescending { it.toMillis() }
        }

        internal fun smartDeadlineOffsets(now: OffsetDateTime, deadline: OffsetDateTime): List<Duration> {
            val untilDeadline = Duration.between(now, deadline)
            return when {
                untilDeadline.isNegative || untilDeadline.isZero -> listOf(Duration.ZERO)
                untilDeadline > ONE_DAY -> listOf(ONE_DAY, THREE_HOURS, THIRTY_MINUTES)
                untilDeadline > THREE_HOURS -> listOf(THREE_HOURS, THIRTY_MINUTES)
                untilDeadline > THIRTY_MINUTES -> listOf(THIRTY_MINUTES)
                else -> listOf(Duration.ZERO)
            }
        }

        internal fun offsetFor(reminder: String): Duration? {
            if (reminder.isBlank()) return null
            val number = Regex("(\\d+)").find(reminder)?.value?.toLongOrNull() ?: 1L
            return when {
                "天" in reminder || "日" in reminder -> Duration.ofDays(number)
                "小时" in reminder || "时" in reminder -> Duration.ofHours(number)
                "分钟" in reminder || "分" in reminder -> Duration.ofMinutes(number)
                "尽快" in reminder || "马上" in reminder || "现在" in reminder -> Duration.ZERO
                else -> null
            }
        }

        private fun defaultOffsets(cardType: String): List<Duration> {
            return when (cardType) {
                CardTypes.EVENT -> listOf(THIRTY_MINUTES)
                CardTypes.COMPARISON, CardTypes.COLLECTION -> emptyList()
                else -> listOf(THREE_HOURS, THIRTY_MINUTES)
            }
        }

        private fun formatOffset(offset: Duration): String {
            return when {
                offset.toDays() >= 1 -> "${offset.toDays()}天"
                offset.toHours() >= 1 -> "${offset.toHours()}小时"
                else -> "${offset.toMinutes().coerceAtLeast(1)}分钟"
            }
        }
    }
}

private fun ActionCard.hasDeadline(): Boolean = !deadline.isNullOrBlank()
