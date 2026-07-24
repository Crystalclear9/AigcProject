package com.suishouban.app.mascot

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/** Bounds mascot state staleness while waking exactly after a nearby DDL boundary. */
internal object MascotRefreshPolicy {
    const val MAX_REFRESH_DELAY_MILLIS = 60_000L

    fun nextDelayMillis(deadlines: Iterable<String?>, now: Instant): Long {
        val nearestFutureDeadline = deadlines
            .mapNotNull(::parseDeadline)
            .filterNot { it.isBefore(now) }
            .minOrNull()
            ?: return MAX_REFRESH_DELAY_MILLIS

        // Resolve once just after the boundary so an exactly-due card becomes expired.
        return (Duration.between(now, nearestFutureDeadline).toMillis() + 1L)
            .coerceIn(1L, MAX_REFRESH_DELAY_MILLIS)
    }

    private fun parseDeadline(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(value).toInstant() }.getOrNull()
    }
}

/** Decides when persisted cards may replace transient focus/completion states in the overlay. */
internal object MascotBackgroundStatePolicy {
    private val CARD_BACKED_MOODS = setOf(
        MascotMood.REMINDER,
        MascotMood.DUE_SOON,
        MascotMood.URGENT,
    )

    fun shouldApply(currentMood: MascotMood, resolvedMood: MascotMood): Boolean =
        resolvedMood in CARD_BACKED_MOODS || currentMood in CARD_BACKED_MOODS
}
