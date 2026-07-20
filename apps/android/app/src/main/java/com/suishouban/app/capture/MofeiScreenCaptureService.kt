package com.suishouban.app.capture

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ResultReceiver
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.suishouban.app.R
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground, one-frame MediaProjection service with one idempotent cleanup path. */
class MofeiScreenCaptureService : Service() {
    private val completed = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private var receiver: ResultReceiver? = null
    private var sawProtectedFrame = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("墨斐正在读取当前屏幕")
                .setContentText("完成一帧后会立即停止")
                .setSilent(true)
                .setOngoing(true)
                .build(),
        )
        if (intent?.action != ACTION_CAPTURE || completed.get()) {
            finishWith(ScreenCaptureResult.FAILURE, "无效的截屏请求")
            return START_NOT_STICKY
        }
        receiver = IntentCompat.getParcelableExtra(intent, EXTRA_RECEIVER, ResultReceiver::class.java)
        val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        if (receiver == null || resultData == null || resultCode != Activity.RESULT_OK) {
            finishWith(ScreenCaptureResult.FAILURE, "截屏授权数据无效")
            return START_NOT_STICKY
        }
        startCapture(resultCode, resultData)
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        runCatching {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val mediaProjection = manager.getMediaProjection(resultCode, resultData)
            projection = mediaProjection
            workerThread = HandlerThread("mofei-screen-capture").also { it.start() }
            worker = Handler(workerThread!!.looper)
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!completed.get()) finishWith(ScreenCaptureResult.CANCELLED, "屏幕读取授权已停止")
                }
            }, worker)

            val metrics = captureMetrics()
            imageReader = ImageReader.newInstance(metrics.width, metrics.height, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                image.use {
                    runCatching { ScreenCaptureImageWriter.write(this, it) }
                        .onSuccess { uri -> finishSuccess(uri.toString()) }
                        .onFailure { error ->
                            if (error is ProtectedContentException) {
                                // Some devices emit an initial black transition frame; wait for the
                                // next frame until the hard timeout before calling it protected.
                                sawProtectedFrame = true
                            } else {
                                finishWith(ScreenCaptureResult.FAILURE, error.message ?: "无法保存截屏")
                            }
                        }
                }
            }, worker)
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "MofeiOneShotCapture",
                metrics.width,
                metrics.height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                worker,
            )
            worker?.postDelayed({
                finishWith(
                    if (sawProtectedFrame) ScreenCaptureResult.PROTECTED_CONTENT else ScreenCaptureResult.TIMEOUT,
                    if (sawProtectedFrame) "页面内容受保护" else "截屏超时",
                )
            }, CAPTURE_TIMEOUT_MS)
        }.onFailure { error ->
            finishWith(ScreenCaptureResult.FAILURE, error.message ?: "无法启动屏幕读取")
        }
    }

    private fun captureMetrics(): CaptureMetrics {
        val windowManager = getSystemService(WindowManager::class.java)
        val densityDpi = resources.configuration.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            CaptureMetrics(bounds.width(), bounds.height(), densityDpi)
        } else {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            CaptureMetrics(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }

    private fun finishSuccess(uri: String) {
        if (!completed.compareAndSet(false, true)) return
        receiver?.send(ScreenCaptureResult.SUCCESS, Bundle().apply { putString(ScreenCaptureResult.KEY_URI, uri) })
        cleanup()
    }

    private fun finishWith(code: Int, message: String) {
        if (!completed.compareAndSet(false, true)) return
        receiver?.send(code, Bundle().apply { putString(ScreenCaptureResult.KEY_MESSAGE, message) })
        cleanup()
    }

    /** Safe from result, timeout, projection callback, startup failure, and onDestroy. */
    private fun cleanup() {
        worker?.removeCallbacksAndMessages(null)
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        virtualDisplay = null
        imageReader = null
        projection = null
        workerThread?.quitSafely()
        workerThread = null
        worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!completed.getAndSet(true)) receiver?.send(ScreenCaptureResult.CANCELLED, Bundle.EMPTY)
        cleanup()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "墨斐屏幕读取", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val ACTION_CAPTURE = "com.suishouban.app.action.MOFEI_CAPTURE_SCREEN"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_RECEIVER = "result_receiver"
        private const val CHANNEL_ID = "mofei_screen_capture"
        private const val NOTIFICATION_ID = 2031
        private const val CAPTURE_TIMEOUT_MS = 6_000L

        fun captureIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            receiver: ResultReceiver,
        ): Intent = Intent(context, MofeiScreenCaptureService::class.java).apply {
            action = ACTION_CAPTURE
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, resultData)
            putExtra(EXTRA_RECEIVER, receiver)
        }
    }
}

private data class CaptureMetrics(val width: Int, val height: Int, val densityDpi: Int)
