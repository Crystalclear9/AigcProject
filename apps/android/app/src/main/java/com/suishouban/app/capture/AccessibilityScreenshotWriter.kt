package com.suishouban.app.capture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi

/** Converts an AccessibilityService screenshot into the same private capture URI used by OCR. */
object AccessibilityScreenshotWriter {
    @RequiresApi(Build.VERSION_CODES.R)
    fun write(
        context: Context,
        screenshot: AccessibilityService.ScreenshotResult,
    ): Uri {
        val hardwareBuffer = screenshot.hardwareBuffer
        val hardwareBitmap = try {
            Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                ?: error("系统未返回可读取的截屏")
        } catch (error: Throwable) {
            hardwareBuffer.close()
            throw error
        }

        val softwareBitmap = try {
            hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("无法转换截屏图像")
        } finally {
            hardwareBitmap.recycle()
            hardwareBuffer.close()
        }

        return try {
            ScreenCaptureImageWriter.write(context, softwareBitmap)
        } finally {
            softwareBitmap.recycle()
        }
    }
}
