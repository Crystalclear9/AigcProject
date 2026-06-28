package com.suishouban.app.reminder

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.CardTypes
import com.suishouban.app.data.model.primaryTime
import java.time.Duration
import java.time.OffsetDateTime

enum class CalendarSyncStatus {
    SYNCED,
    SKIPPED,
    FAILED,
}

data class CalendarSyncResult(
    val status: CalendarSyncStatus,
    val message: String,
    val eventUri: Uri? = null,
) {
    val synced: Boolean get() = status == CalendarSyncStatus.SYNCED
    val failed: Boolean get() = status == CalendarSyncStatus.FAILED
}

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

    fun insertIfPermitted(card: ActionCard): CalendarSyncResult {
        if (card.cardType != CardTypes.EVENT) {
            return CalendarSyncResult(CalendarSyncStatus.SKIPPED, "非日程卡片无需写入日历")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return CalendarSyncResult(CalendarSyncStatus.FAILED, "日历同步未完成：缺少日历写入权限")
        }
        val start = parseTime(card.startTime)
            ?: return CalendarSyncResult(CalendarSyncStatus.FAILED, "日历同步未完成：缺少可用开始时间")
        val end = parseTime(card.endTime) ?: start.plus(defaultDuration(card.cardType))
        val calendarId = writableCalendarId()
            ?: return CalendarSyncResult(CalendarSyncStatus.FAILED, "日历同步未完成：未找到可写入的系统日历")
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, start.toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, end.toInstant().toEpochMilli())
            put(CalendarContract.Events.TITLE, card.title)
            put(CalendarContract.Events.DESCRIPTION, card.summary)
            put(CalendarContract.Events.EVENT_LOCATION, card.location)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, start.offset.id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        return if (uri != null) {
            CalendarSyncResult(CalendarSyncStatus.SYNCED, "已写入系统日历", uri)
        } else {
            CalendarSyncResult(CalendarSyncStatus.FAILED, "日历同步未完成：系统日历写入失败")
        }
    }

    private fun writableCalendarId(): Long? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
        )
        return runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.VISIBLE}=1",
                null,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val accessIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                while (cursor.moveToNext()) {
                    val access = cursor.getInt(accessIndex)
                    if (access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                        return@use cursor.getLong(idIndex)
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun parseTime(value: String?): OffsetDateTime? {
        if (value.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(value) }.getOrNull()
    }

    private fun defaultDuration(cardType: String): Duration {
        return if (cardType == CardTypes.EVENT) Duration.ofHours(1) else Duration.ofMinutes(30)
    }
}
