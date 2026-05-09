package com.suishouban.app.reminder

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
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

    fun insertIfPermitted(card: ActionCard): Boolean {
        if (card.cardType != CardTypes.EVENT) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val start = parseTime(card.startTime) ?: return false
        val end = parseTime(card.endTime) ?: start.plus(defaultDuration(card.cardType))
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, start.toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, end.toInstant().toEpochMilli())
            put(CalendarContract.Events.TITLE, card.title)
            put(CalendarContract.Events.DESCRIPTION, card.summary)
            put(CalendarContract.Events.EVENT_LOCATION, card.location)
            put(CalendarContract.Events.CALENDAR_ID, 1)
            put(CalendarContract.Events.EVENT_TIMEZONE, start.offset.id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        return uri != null
    }

    private fun parseTime(value: String?): OffsetDateTime? {
        if (value.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(value) }.getOrNull()
    }

    private fun defaultDuration(cardType: String): Duration {
        return if (cardType == CardTypes.EVENT) Duration.ofHours(1) else Duration.ofMinutes(30)
    }
}
