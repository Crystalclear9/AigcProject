package com.suishouban.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageUploadPolicyTest {
    @Test
    fun smallImageKeepsOriginalSampleSize() {
        assertEquals(1, ImageUploadPolicy.calculateSampleSize(1080, 1200))
    }

    @Test
    fun largeImageUsesPowerOfTwoSampleSize() {
        assertEquals(8, ImageUploadPolicy.calculateSampleSize(4320, 7680))
    }

    @Test
    fun mediumImageIsNotOverCompressed() {
        assertEquals(2, ImageUploadPolicy.calculateSampleSize(2160, 2400))
    }
}
