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

/** Owns the per-session Android screen-capture consent and receives exactly one result. */
class MofeiScreenCaptureActivity : ComponentActivity() {
    private val captureConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            Toast.makeText(this, "已取消当前屏幕识别", Toast.LENGTH_SHORT).show()
            finish()
            return@registerForActivityResult
        }
        ContextCompat.startForegroundService(
            this,
            MofeiScreenCaptureService.captureIntent(
                context = this,
                resultCode = result.resultCode,
                resultData = result.data ?: return@registerForActivityResult,
                receiver = resultReceiver,
            ),
        )
    }

    private val resultReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            when (resultCode) {
                ScreenCaptureResult.SUCCESS -> {
                    val uri = resultData?.getString(ScreenCaptureResult.KEY_URI)?.let(android.net.Uri::parse)
                    if (uri != null) startActivity(ScreenshotPreviewActivity.captureIntent(this@MofeiScreenCaptureActivity, uri))
                }
                ScreenCaptureResult.PROTECTED_CONTENT -> show("该页面禁止截屏，墨斐没有读取到画面")
                ScreenCaptureResult.TIMEOUT -> show("当前屏幕读取超时，请重试")
                ScreenCaptureResult.CANCELLED -> show("当前屏幕读取已停止")
                else -> show(resultData?.getString(ScreenCaptureResult.KEY_MESSAGE) ?: "当前屏幕读取失败")
            }
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        captureConsent.launch(manager.createScreenCaptureIntent())
    }

    private fun show(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, MofeiScreenCaptureActivity::class.java)
    }
}
