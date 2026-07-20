package com.suishouban.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationDraftAssociationTest {
    @Test
    fun candidateIsConsumedOnlyWhenItsOwnDraftWasSaved() {
        val association = NotificationDraftAssociation(
            candidateId = "candidate-1",
            draftIds = setOf("draft-from-notification"),
        )

        assertNull(association.candidateToConsume(setOf("unrelated-draft")))
        assertEquals(
            "candidate-1",
            association.candidateToConsume(setOf("draft-from-notification")),
        )
    }
}
