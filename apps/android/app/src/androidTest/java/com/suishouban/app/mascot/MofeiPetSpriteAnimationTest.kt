package com.suishouban.app.mascot

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test

class MofeiPetSpriteAnimationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun floatingPetAdvancesPastItsFirstFrame() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MofeiPetSprite(
                state = MascotState(
                    mood = MascotMood.IDLE,
                    userMessage = "",
                    colorRole = MascotColorRole.DEFAULT,
                    animationHint = MascotAnimationHint.BREATHE,
                ),
                reduceMotion = false,
            )
        }

        compose.onNodeWithTag("mofei-pet-sprite")
            .assert(hasStateDescription("frame 1 of 8"))
        compose.mainClock.advanceTimeBy(400L)
        compose.waitForIdle()
        compose.onNodeWithTag("mofei-pet-sprite")
            .assert(hasStateDescription("frame 2 of 8"))
    }
}
