package com.suishouban.app.data.repository

import com.suishouban.app.data.local.NotificationCandidateEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationCandidateExpiryTest {
    @Test
    fun activeAtUsesCurrentClockInsteadOfSubscriptionClock() {
        val candidate = NotificationCandidateEntity(
            id = "one",
            notificationKey = "key",
            packageName = "example.app",
            appLabel = "Example",
            title = "Title",
            body = "Body",
            postedAtMillis = 1_000L,
            contentHash = "hash",
            expiresAtMillis = 2_000L,
        )

        assertEquals(listOf(candidate), NotificationCandidateRepository.activeAt(listOf(candidate), 1_999L))
        assertEquals(emptyList<NotificationCandidateEntity>(), NotificationCandidateRepository.activeAt(listOf(candidate), 2_000L))
    }
}
