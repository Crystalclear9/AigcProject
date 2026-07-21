package com.suishouban.app.domain.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/** Small average hash used only to recognize the same captured frame across Android capture routes. */
object ScreenshotImageFingerprint {
    private const val GRID_SIZE = 8

    fun fromUri(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bitmap = BitmapFactory.decodeStream(stream) ?: return@use null
            try {
                val sample = Bitmap.createScaledBitmap(bitmap, GRID_SIZE, GRID_SIZE, true)
                try {
                    val pixels = IntArray(GRID_SIZE * GRID_SIZE)
                    sample.getPixels(pixels, 0, GRID_SIZE, 0, 0, GRID_SIZE, GRID_SIZE)
                    fromArgb(pixels, GRID_SIZE, GRID_SIZE)
                } finally {
                    if (sample !== bitmap) sample.recycle()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }.getOrNull()

    fun fromArgb(pixels: IntArray, width: Int, height: Int): String {
        require(width > 0 && height > 0 && pixels.size >= width * height) { "Invalid pixel buffer" }
        val luminance = IntArray(GRID_SIZE * GRID_SIZE)
        for (y in 0 until GRID_SIZE) {
            val sourceY = ((y + 0.5f) * height / GRID_SIZE).toInt().coerceIn(0, height - 1)
            for (x in 0 until GRID_SIZE) {
                val sourceX = ((x + 0.5f) * width / GRID_SIZE).toInt().coerceIn(0, width - 1)
                val color = pixels[sourceY * width + sourceX]
                val red = color ushr 16 and 0xff
                val green = color ushr 8 and 0xff
                val blue = color and 0xff
                luminance[y * GRID_SIZE + x] = (red * 299 + green * 587 + blue * 114) / 1_000
            }
        }
        val average = luminance.average()
        return buildString(luminance.size / 4) {
            for (offset in luminance.indices step 4) {
                var nibble = 0
                for (index in offset until offset + 4) {
                    nibble = (nibble shl 1) or if (luminance[index] >= average) 1 else 0
                }
                append(nibble.toString(16))
            }
        }
    }
}
