package com.suishouban.app.mascot

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class MascotRefreshPolicyTest {
    private val now = Instant.parse("2026-07-22T08:00:00Z")

    @Test
    fun refreshesImmediatelyAfterTheNearestDeadline() {
        val delay = MascotRefreshPolicy.nextDelayMillis(
            deadlines = listOf("2026-07-22T08:00:05Z", "2026-07-22T08:00:20Z"),
            now = now,
        )

        assertEquals(5_001L, delay)
    }

    @Test
    fun capsLongOrMissingDeadlinesToBoundStateStaleness() {
        assertEquals(
            MascotRefreshPolicy.MAX_REFRESH_DELAY_MILLIS,
            MascotRefreshPolicy.nextDelayMillis(listOf("2026-07-23T08:00:00Z"), now),
        )
        assertEquals(
            MascotRefreshPolicy.MAX_REFRESH_DELAY_MILLIS,
            MascotRefreshPolicy.nextDelayMillis(listOf(null, "invalid"), now),
        )
    }

    @Test
    fun expiredDeadlinesDoNotCreateABusyRefreshLoop() {
        val delay = MascotRefreshPolicy.nextDelayMillis(
            deadlines = listOf("2026-07-22T07:59:59Z"),
            now = now,
        )

        assertEquals(MascotRefreshPolicy.MAX_REFRESH_DELAY_MILLIS, delay)
    }
}
