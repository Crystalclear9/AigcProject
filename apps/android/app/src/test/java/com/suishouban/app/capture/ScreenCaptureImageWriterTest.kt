package com.suishouban.app.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenCaptureImageWriterTest {
    @Test
    fun convertsRgbaRowsWithoutCopyingPaddingPixels() {
        val bytes = byteArrayOf(
            1, 2, 3, -1, 4, 5, 6, -1, 99, 99, 99, 99,
            7, 8, 9, -1, 10, 11, 12, -1, 88, 88, 88, 88,
        )

        val pixels = ScreenCaptureImageWriter.rgbaToArgb(
            bytes = bytes,
            width = 2,
            height = 2,
            pixelStride = 4,
            rowStride = 12,
        )

        assertArrayEquals(
            intArrayOf(0xFF010203.toInt(), 0xFF040506.toInt(), 0xFF070809.toInt(), 0xFF0A0B0C.toInt()),
            pixels,
        )
    }

    @Test
    fun detectsProtectedBlackOrTransparentFramesButKeepsNormalFrames() {
        assertTrue(ScreenCaptureImageWriter.isBlankOrProtected(IntArray(100) { 0xFF000000.toInt() }))
        assertTrue(ScreenCaptureImageWriter.isBlankOrProtected(IntArray(100) { 0x00000000 }))
        val normal = IntArray(100) { if (it < 10) 0xFF33CCEE.toInt() else 0xFF000000.toInt() }
        assertFalse(ScreenCaptureImageWriter.isBlankOrProtected(normal))
    }

    @Test
    fun cacheNameContainsOnlyStableSafeCharacters() {
        assertEquals("mofei_capture_123456.png", ScreenCaptureImageWriter.fileName(123456L))
    }
}
