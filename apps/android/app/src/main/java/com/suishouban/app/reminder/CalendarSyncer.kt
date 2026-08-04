package com.suishouban.app.reminder

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardTypes
import com.suishouban.app.data.model.primaryTime
import java.time.Duration
import java.time.OffsetDateTime

class CalendarSyncer(private val context: Context) {
    fun buildInsertIntent(card: ActionCard): Intent? {
        val start = parseTime(card.primaryTime()) ?: return null
        val end = parseTime(card.endTime) ?: start.plus(defaultDuration(card.cardType))
        return Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, card.title)
            putExtra(CalendarContract.Events.DESCRIPTION, card.summary)
            putExtra(CalendarContract.Events.EVENT_LOCATION, card.location.orEmpty())
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toInstant().toEpochMilli())
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.toInstant().toEpochMilli())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun parseTime(value: String?): OffsetDateTime? {
        if (value.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(value) }.getOrNull()
    }

    private fun defaultDuration(cardType: String): Duration {
        return if (cardType == CardTypes.EVENT) Duration.ofHours(1) else Duration.ofMinutes(30)
    }
}
