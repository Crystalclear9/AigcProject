package com.suishouban.app.mascot

import com.suishouban.app.R
import com.suishouban.app.mascot.action.MofeiAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MofeiActionAssetCatalogTest {
    @Test
    fun everyActionHasOneDistinctGeneratedGlyph() {
        val assets = MofeiActionAssets.glyphs

        assertEquals(MofeiAction.entries.toSet(), assets.keys)
        assertEquals(assets.size, assets.values.toSet().size)
        assertTrue(assets.values.all { it != 0 })
    }

    @Test
    fun catalogExposesFullCompactAndSealAssets() {
        assertEquals(R.drawable.mofei_action_ring_full, MofeiActionAssets.fullRing)
        assertEquals(R.drawable.mofei_action_ring_compact, MofeiActionAssets.compactRing)
        assertEquals(R.drawable.mofei_action_seal, MofeiActionAssets.seal)
        assertEquals(R.drawable.mofei_action_side_arc, MofeiActionAssets.sideArc)
    }
}
