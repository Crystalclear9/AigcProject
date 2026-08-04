package com.suishouban.app.ui.components

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormatter = DateTimeFormatter.ofPattern("M 月 d 日", Locale.CHINA)
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
private val weekFormatter = DateTimeFormatter.ofPattern("E", Locale.CHINA)

data class TemporalSummaryText(
    val primary: String,
    val secondary: String? = null,
    val pending: Boolean = false,
)

fun formatSmartTime(value: String?): String {
    return temporalSummary(value = value).primary
}

fun formatDay(value: String?): String {
    if (value.isNullOrBlank()) return "未定日期"
    val time = runCatching { OffsetDateTime.parse(value) }.getOrNull() ?: return "未定日期"
    return time.format(dayFormatter)
}

fun temporalSummary(
    value: String? = null,
    start: String? = null,
    end: String? = null,
    deadline: String? = null,
    zoneId: ZoneId = ZoneId.systemDefault(),
    now: OffsetDateTime = OffsetDateTime.now(zoneId),
): TemporalSummaryText {
    val rawStart = start ?: value ?: deadline
    if (rawStart.isNullOrBlank()) {
        return TemporalSummaryText("时间待确认", pending = true)
    }
    val parsedStart = parseInZone(rawStart, zoneId)
        ?: return TemporalSummaryText("时间待确认", "原时间格式需要检查", pending = true)
    val parsedEnd = end?.let { parseInZone(it, zoneId) }
    val relativeDay = when (parsedStart.toLocalDate().toEpochDay() - now.toLocalDate().toEpochDay()) {
        0L -> "今天"
        1L -> "明天"
        -1L -> "昨天"
        else -> "${parsedStart.format(dayFormatter)} ${parsedStart.format(weekFormatter)}"
    }
    val range = if (parsedEnd != null) {
        if (parsedEnd.toLocalDate() == parsedStart.toLocalDate()) {
            "${parsedStart.format(timeFormatter)}–${parsedEnd.format(timeFormatter)}"
        } else {
            "${parsedStart.format(timeFormatter)}–${parsedEnd.format(dayFormatter)} ${parsedEnd.format(timeFormatter)}"
        }
    } else {
        parsedStart.format(timeFormatter)
    }
    val secondary = when {
        deadline != null && start == null -> "截止 · ${parsedStart.offset.id}"
        parsedEnd != null -> "日程 · ${parsedStart.offset.id}"
        else -> parsedStart.offset.id
    }
    return TemporalSummaryText("$relativeDay $range", secondary)
}

private fun parseInZone(value: String, zoneId: ZoneId): OffsetDateTime? =
    runCatching {
        OffsetDateTime.parse(value).atZoneSameInstant(zoneId).toOffsetDateTime()
    }.getOrNull()
