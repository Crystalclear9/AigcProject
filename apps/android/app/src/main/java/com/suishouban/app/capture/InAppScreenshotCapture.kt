package com.suishouban.app.capture

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

sealed interface InAppScreenshotResult {
    data class Success(val uri: Uri) : InAppScreenshotResult
    data class Failure(val message: String) : InAppScreenshotResult
}

/**
 * Captures only this app's current window. This produces one private PNG for OCR and never starts
 * screen sharing or reads another app.
 */
object InAppScreenshotCapture {
    suspend fun capture(activity: Activity): InAppScreenshotResult {
        val decor = activity.window.decorView
        if (decor.width <= 0 || decor.height <= 0) {
            return InAppScreenshotResult.Failure("当前页面尚未准备好，请稍后重试")
        }

        val bitmap = runCatching {
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        }.getOrElse {
            return InAppScreenshotResult.Failure("当前页面过大，暂时无法生成截图")
        }
        val copyResult = try {
            requestPixelCopy(activity, bitmap)
        } catch (cancelled: CancellationException) {
            bitmap.recycle()
            throw cancelled
        }
        if (copyResult != PixelCopy.SUCCESS) {
            bitmap.recycle()
            return InAppScreenshotResult.Failure(pixelCopyFailure(copyResult))
        }

        return try {
            val uri = withContext(Dispatchers.IO) {
                ScreenCaptureImageWriter.write(activity, bitmap)
            }
            InAppScreenshotResult.Success(uri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            InAppScreenshotResult.Failure(error.message ?: "无法保存当前页面截图")
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun requestPixelCopy(
        activity: Activity,
        bitmap: Bitmap,
    ): Int = suspendCancellableCoroutine { continuation ->
        PixelCopy.request(
            activity.window,
            bitmap,
            { result -> continuation.resumeIfActive(result) },
            Handler(Looper.getMainLooper()),
        )
    }

    private fun CancellableContinuation<Int>.resumeIfActive(result: Int) {
        if (isActive) resume(result)
    }

    internal fun pixelCopyFailure(code: Int): String = when (code) {
        PixelCopy.ERROR_SOURCE_NO_DATA -> "当前页面还没有可截图内容，请稍后重试"
        PixelCopy.ERROR_SOURCE_INVALID -> "当前页面不支持截图"
        PixelCopy.ERROR_TIMEOUT -> "截图超时，请重试"
        else -> "截图失败（错误码 $code）"
    }
}
