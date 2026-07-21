package com.suishouban.app.mascot

import org.junit.Assert.assertEquals
import org.junit.Test

class MascotAssetCatalogTest {
    @Test
    fun everySupportedMoodMapsToItsEightImage2Frames() {
        assertEquals(
            (1..8).map { "mofei_urgent_f%02d".format(it) },
            MascotAssetCatalog.frameNamesFor(MascotMood.URGENT),
        )
        assertEquals(
            (1..8).map { "mofei_complete_f%02d".format(it) },
            MascotAssetCatalog.frameNamesFor(MascotMood.COMPLETE),
        )
    }

    @Test
    fun everyMoodExposesEightFrames() {
        MascotMood.entries.forEach { mood ->
            assertEquals("$mood should animate over eight frames", 8, MascotAssetCatalog.framesFor(mood).size)
            assertEquals("$mood in-app should animate over eight frames", 8, InAppMofeiAssetCatalog.framesFor(mood).size)
        }
    }

    @Test
    fun unavailableMoodFallsBackToTheIdleImage2Frames() {
        assertEquals(
            MascotAssetCatalog.frameNamesFor(MascotMood.IDLE),
            MascotAssetCatalog.frameNamesFor(MascotMood.UNAVAILABLE),
        )
        assertEquals(
            MascotAssetCatalog.framesFor(MascotMood.IDLE),
            MascotAssetCatalog.framesFor(MascotMood.UNAVAILABLE),
        )
    }
}
