package com.suishouban.app.mascot

import org.junit.Assert.assertEquals
import org.junit.Test

class MascotAssetCatalogTest {
    @Test
    fun everySupportedMoodMapsToItsThreeImage2Frames() {
        assertEquals(
            listOf("mofei_urgent_f01", "mofei_urgent_f02", "mofei_urgent_f03"),
            MascotAssetCatalog.frameNamesFor(MascotMood.URGENT),
        )
        assertEquals(
            listOf("mofei_complete_f01", "mofei_complete_f02", "mofei_complete_f03"),
            MascotAssetCatalog.frameNamesFor(MascotMood.COMPLETE),
        )
    }

    @Test
    fun unavailableMoodFallsBackToTheIdleImage2Frames() {
        assertEquals(
            MascotAssetCatalog.frameNamesFor(MascotMood.IDLE),
            MascotAssetCatalog.frameNamesFor(MascotMood.UNAVAILABLE),
        )
    }
}
