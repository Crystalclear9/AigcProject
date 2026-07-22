package com.suishouban.app.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.suishouban.app.ScreenshotPreviewActivity
import com.suishouban.app.mascot.MascotOverlayService
import com.suishouban.app.mascot.action.MofeiFailure
import com.suishouban.app.mascot.action.MofeiRecoveryPolicy

/** Owns the per-session Android screen-capture consent and receives exactly one result. */
class MofeiScreenCaptureActivity : ComponentActivity() {
    private var restoreOverlayAfter = false
    private var restoreDelegatedToPreview = false
    private val captureConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            Toast.makeText(
                this,
                MofeiRecoveryPolicy.forFailure(MofeiFailure.PROJECTION_CANCELLED).message,
                Toast.LENGTH_SHORT,
            ).show()
            restoreOverlayIfNeeded()
            finishAndRemoveTask()
            return@registerForActivityResult
        }
        runCatching {
            ContextCompat.startForegroundService(
                this,
                MofeiScreenCaptureService.captureIntent(
                    context = this,
                    resultCode = result.resultCode,
                    resultData = result.data ?: return@registerForActivityResult,
                    receiver = resultReceiver,
                ),
            )
        }.onFailure {
            show("系统未能启动屏幕读取服务")
            restoreOverlayIfNeeded()
            finishAndRemoveTask()
        }
    }

    private val resultReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            when (resultCode) {
                ScreenCaptureResult.SUCCESS -> {
                    val uri = resultData?.getString(ScreenCaptureResult.KEY_URI)?.let(android.net.Uri::parse)
                    if (uri != null) {
                        startActivity(
                            ScreenshotPreviewActivity.captureIntent(
                                this@MofeiScreenCaptureActivity,
                                uri,
                                restoreOverlayAfter,
                            ),
                        )
                        restoreDelegatedToPreview = restoreOverlayAfter
                    }
                }
                ScreenCaptureResult.PROTECTED_CONTENT -> show(
                    MofeiRecoveryPolicy.forFailure(MofeiFailure.PROTECTED_CONTENT).message,
                )
                ScreenCaptureResult.TIMEOUT -> show("当前屏幕读取超时，请重试")
                ScreenCaptureResult.CANCELLED -> show("当前屏幕读取已停止")
                ScreenCaptureResult.DUPLICATE -> show("同一画面刚刚已经处理，无需重复生成")
                else -> show(resultData?.getString(ScreenCaptureResult.KEY_MESSAGE) ?: "当前屏幕读取失败")
            }
            if (resultCode != ScreenCaptureResult.SUCCESS) restoreOverlayIfNeeded()
            if (resultCode == ScreenCaptureResult.SUCCESS) {
                // The preview now owns this isolated task and the eventual overlay restore.
                finish()
            } else {
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreOverlayAfter = intent.getBooleanExtra(EXTRA_RESTORE_OVERLAY, false)
        if (savedInstanceState != null) {
            // Consent/result receivers cannot be safely reconstructed after process recreation.
            show("屏幕读取会话已中断，请重试")
            restoreOverlayIfNeeded()
            finishAndRemoveTask()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        captureConsent.launch(manager.createScreenCaptureIntent())
    }

    private fun show(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations && !restoreDelegatedToPreview) restoreOverlayIfNeeded()
        super.onDestroy()
    }

    private fun restoreOverlayIfNeeded() {
        if (!restoreOverlayAfter) return
        restoreOverlayAfter = false
        MascotOverlayService.restoreVisibleAfterCapture(this)
    }

    companion object {
        private const val EXTRA_RESTORE_OVERLAY = "restore_overlay_after_capture"

        fun intent(context: Context, restoreOverlayAfter: Boolean = false): Intent =
            Intent(context, MofeiScreenCaptureActivity::class.java)
                .putExtra(EXTRA_RESTORE_OVERLAY, restoreOverlayAfter)
                // A separate transparent task preserves the external app beneath the consent UI.
                // Do not use NO_HISTORY: this Activity must survive to receive the consent result.
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                )
    }
}
