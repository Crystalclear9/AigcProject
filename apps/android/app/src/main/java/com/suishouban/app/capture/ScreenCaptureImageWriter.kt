package com.suishouban.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** Converts the padded RGBA ImageReader plane into a private-cache PNG. */
object ScreenCaptureImageWriter {
    fun rgbaToArgb(
        bytes: ByteArray,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int,
    ): IntArray {
        require(width > 0 && height > 0)
        require(pixelStride >= 4 && rowStride >= width * pixelStride)
        require(bytes.size >= rowStride * height)
        return IntArray(width * height) { outputIndex ->
            val x = outputIndex % width
            val y = outputIndex / width
            val offset = y * rowStride + x * pixelStride
            val red = bytes[offset].toInt() and 0xFF
            val green = bytes[offset + 1].toInt() and 0xFF
            val blue = bytes[offset + 2].toInt() and 0xFF
            val alpha = bytes[offset + 3].toInt() and 0xFF
            (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }

    /** Protected windows commonly produce entirely black or transparent projection frames. */
    fun isBlankOrProtected(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return true
        val visible = pixels.count { pixel ->
            val alpha = pixel ushr 24
            val red = pixel shr 16 and 0xFF
            val green = pixel shr 8 and 0xFF
            val blue = pixel and 0xFF
            alpha > 16 && maxOf(red, green, blue) > 12
        }
        return visible < maxOf(2, pixels.size / 100)
    }

    fun fileName(timestampMs: Long): String = "mofei_capture_${timestampMs.coerceAtLeast(0L)}.png"

    fun write(context: Context, image: Image, timestampMs: Long = System.currentTimeMillis()): Uri {
        val plane = image.planes.firstOrNull() ?: error("Screen capture has no image plane")
        val buffer = plane.buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val pixels = rgbaToArgb(bytes, image.width, image.height, plane.pixelStride, plane.rowStride)
        if (isBlankOrProtected(pixels)) throw ProtectedContentException()

        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        return try {
            write(context, bitmap, timestampMs)
        } finally {
            bitmap.recycle()
        }
    }

    /** Persists a software bitmap produced by non-MediaProjection capture APIs. */
    fun write(context: Context, bitmap: Bitmap, timestampMs: Long = System.currentTimeMillis()): Uri {
        require(bitmap.width > 0 && bitmap.height > 0)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        if (isBlankOrProtected(pixels)) throw ProtectedContentException()

        val directory = File(context.cacheDir, CAPTURE_DIRECTORY).apply { mkdirs() }
        val output = File(directory, fileName(timestampMs))
        FileOutputStream(output).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Unable to encode captured frame"
            }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output)
    }

    fun deleteStale(context: Context, olderThanMs: Long, nowMs: Long = System.currentTimeMillis()) {
        File(context.cacheDir, CAPTURE_DIRECTORY).listFiles().orEmpty().forEach { file ->
            if (nowMs - file.lastModified() >= olderThanMs) file.delete()
        }
    }

    const val CAPTURE_DIRECTORY = "mofei_capture"
}

class ProtectedContentException : IllegalStateException("Captured frame is blank or protected")
