package com.suishouban.app.mascot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MascotBackgroundStatePolicyTest {
    @Test
    fun appliesANewCardBackedState() {
        assertTrue(
            MascotBackgroundStatePolicy.shouldApply(
                currentMood = MascotMood.IDLE,
                resolvedMood = MascotMood.DUE_SOON,
            ),
        )
    }

    @Test
    fun clearsThePreviousCardStateWhenNoEligibleCardRemains() {
        assertTrue(
            MascotBackgroundStatePolicy.shouldApply(
                currentMood = MascotMood.URGENT,
                resolvedMood = MascotMood.IDLE,
            ),
        )
    }

    @Test
    fun preservesEphemeralStateWhenCardsDoNotOwnEitherState() {
        assertFalse(
            MascotBackgroundStatePolicy.shouldApply(
                currentMood = MascotMood.FOCUS,
                resolvedMood = MascotMood.IDLE,
            ),
        )
    }
}
