package com.suishouban.app.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.suishouban.app.domain.screenshot.ScreenshotCaptureSource
import com.suishouban.app.domain.screenshot.ScreenshotFingerprintStore
import com.suishouban.app.domain.screenshot.ScreenshotImageFingerprint
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

sealed interface AccessibilityCaptureResult {
    data class Success(val uri: Uri) : AccessibilityCaptureResult
    data class Failure(val message: String) : AccessibilityCaptureResult
}

/**
 * Provides one-shot screenshots for the external Mofei ring.
 *
 * It deliberately ignores accessibility events and never inspects window content. The system
 * service is used only because Android exposes consent-persistent screenshot capture through this
 * API, avoiding MediaProjection's screen-sharing flow.
 */
class MofeiScreenshotAccessibilityService : AccessibilityService() {
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Metadata must declare an event type, but this service does not need event delivery.
        serviceInfo = serviceInfo.apply { eventTypes = 0 }
        activeService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (activeService === this) activeService = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        captureExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun capture(callback: (AccessibilityCaptureResult) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(AccessibilityCaptureResult.Failure("当前系统版本不支持直接截屏"))
            return
        }

        runCatching {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                captureExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        runCatching { AccessibilityScreenshotWriter.write(this@MofeiScreenshotAccessibilityService, screenshot) }
                            .onSuccess { uri ->
                                val store = ScreenshotFingerprintStore.sharedPreferences(
                                    this@MofeiScreenshotAccessibilityService,
                                )
                                val imageHash = ScreenshotImageFingerprint.fromUri(
                                    this@MofeiScreenshotAccessibilityService,
                                    uri,
                                )
                                val isNewImage = imageHash == null || store.checkAndRecordImage(
                                    imageHash,
                                    ScreenshotCaptureSource.ACCESSIBILITY,
                                    System.currentTimeMillis(),
                                )
                                if (isNewImage) {
                                    callback(AccessibilityCaptureResult.Success(uri))
                                } else {
                                    // The URI is an app-private temporary capture and is safe to remove.
                                    runCatching { contentResolver.delete(uri, null, null) }
                                    callback(AccessibilityCaptureResult.Failure("同一画面刚刚已经处理"))
                                }
                            }
                            .onFailure { error ->
                                val message = if (error is ProtectedContentException) {
                                    "当前页面禁止截屏或截屏内容为空"
                                } else {
                                    error.message ?: "无法保存截屏"
                                }
                                callback(AccessibilityCaptureResult.Failure(message))
                            }
                    }

                    override fun onFailure(errorCode: Int) {
                        callback(AccessibilityCaptureResult.Failure("系统截屏失败（错误码 $errorCode）"))
                    }
                },
            )
        }.onFailure { error ->
            callback(AccessibilityCaptureResult.Failure(error.message ?: "系统截屏调用失败"))
        }
    }

    companion object {
        @Volatile
        private var activeService: MofeiScreenshotAccessibilityService? = null

        fun isConnected(): Boolean = activeService != null

        /**
         * Returns false when the service is not connected; callers must route to accessibility
         * setup instead of silently falling back to MediaProjection.
         */
        fun requestScreenshot(callback: (AccessibilityCaptureResult) -> Unit): Boolean {
            val service = activeService ?: return false
            service.capture(callback)
            return true
        }
    }
}
