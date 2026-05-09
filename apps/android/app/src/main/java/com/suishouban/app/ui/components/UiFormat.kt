package com.suishouban.app.ui.components

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormatter = DateTimeFormatter.ofPattern("M 月 d 日", Locale.CHINA)
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

fun formatSmartTime(value: String?): String {
    if (value.isNullOrBlank()) return "时间待确认"
    val time = runCatching { OffsetDateTime.parse(value) }.getOrNull() ?: return value
    return "${time.format(dayFormatter)} ${time.format(timeFormatter)}"
}

fun formatDay(value: String?): String {
    if (value.isNullOrBlank()) return "未定日期"
    val time = runCatching { OffsetDateTime.parse(value) }.getOrNull() ?: return "未定日期"
    return time.format(dayFormatter)
}
