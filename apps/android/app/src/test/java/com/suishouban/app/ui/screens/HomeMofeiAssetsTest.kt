package com.suishouban.app.ui.screens

import com.suishouban.app.R
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeMofeiAssetsTest {
    @Test
    fun homeMascotVariantsResolveToPackagedDrawables() {
        // Referencing every generated drawable makes an accidental rename or omission fail at compile time.
        listOf(
            R.drawable.mofei_home_hero,
            R.drawable.mofei_home_status,
            R.drawable.mofei_home_empty,
        ).forEach { resourceId ->
            assertNotEquals(0, resourceId)
        }
    }
}
