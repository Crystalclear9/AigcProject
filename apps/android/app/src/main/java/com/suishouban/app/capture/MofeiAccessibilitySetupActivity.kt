package com.suishouban.app.capture

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.util.UUID
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.suishouban.app.ScreenshotPreviewActivity
import com.suishouban.app.mascot.MascotOverlayService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Explains and opens the only system permission used by external one-shot screenshot capture. */
class MofeiAccessibilitySetupActivity : ComponentActivity() {
    private var waitingForSettings = false
    private var overlayRestored = false
    private var captureRequested = false
    private val captureResultConsumed = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "当前 Android 版本不支持墨斐直接截屏", Toast.LENGTH_LONG).show()
            finishAndRestore()
            return
        }
        val connectionState = currentConnectionState()
        if (connectionState == MofeiAccessibilityConnectionState.CONNECTED) {
            continueCaptureWhenReady()
            return
        }

        // Keep Mofei visible while explaining first-time permission, but collapse the action arc
        // so it cannot cover the system-facing setup controls.
        MascotOverlayService.collapseVisibleForCaptureSetup(this)
        val reconnectRequired =
            connectionState == MofeiAccessibilityConnectionState.CONFIGURED_NOT_CONNECTED
        Log.i(TAG, "external_capture stage=setup_required state=$connectionState")
        AlertDialog.Builder(this)
            .setTitle(if (reconnectRequired) "重新连接墨斐一键截屏" else "开启墨斐一键截屏")
            .setMessage(
                if (reconnectRequired) {
                    "系统仍保留着开启记录，但截屏服务没有连接。请在下一页先关闭“墨斐一键截屏”，" +
                        "再重新开启一次。完成后返回，墨斐会继续刚才的截屏。"
                } else {
                    "系统需要启用“墨斐一键截屏”服务。它只在你点击截屏后读取一次当前画面，" +
                        "用于识别和预览待办，不读取页面控件或输入内容。"
                },
            )
            .setPositiveButton(if (reconnectRequired) "前往重新连接" else "前往开启") { _, _ ->
                if (openAccessibilitySettings()) {
                    waitingForSettings = true
                } else {
                    Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_LONG).show()
                    finishAndRestore()
                }
            }
            .setNegativeButton("暂不开启") { _, _ -> finishAndRestore() }
            .setOnCancelListener { finishAndRestore() }
            .show()
    }

    private fun currentConnectionState(): MofeiAccessibilityConnectionState {
        val component = ComponentName(this, MofeiScreenshotAccessibilityService::class.java)
        val masterEnabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        val serviceListed = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == component }
        return AccessibilityScreenshotPolicy.connectionState(
            serviceConnected = MofeiScreenshotAccessibilityService.isConnected(),
            accessibilityMasterEnabled = masterEnabled,
            serviceListedAsEnabled = serviceListed,
        )
    }

    private fun openAccessibilitySettings(): Boolean {
        val component = ComponentName(this, MofeiScreenshotAccessibilityService::class.java)
        val details = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
        if (details.resolveActivity(packageManager) != null) {
            runCatching { startActivity(details) }
                .onSuccess {
                    Log.i(TAG, "external_capture stage=settings_opened destination=details")
                    return true
                }
                .onFailure {
                    // Android 16 protects this activity with
                    // OPEN_ACCESSIBILITY_DETAILS_SETTINGS on some OEM builds.
                    Log.i(
                        TAG,
                        "external_capture stage=settings_details_unavailable " +
                            "reason=${it.javaClass.simpleName}",
                    )
                }
        }
        return runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onSuccess {
            Log.i(TAG, "external_capture stage=settings_opened destination=list")
        }.onFailure {
            Log.w(TAG, "external_capture stage=settings_open_failed", it)
        }.isSuccess
    }

    override fun onResume() {
        super.onResume()
        if (!waitingForSettings) return
        waitingForSettings = false
        continueCaptureWhenReady()
    }

    private fun continueCaptureWhenReady() {
        if (captureRequested) return
        captureRequested = true
        lifecycleScope.launch {
            repeat(ACCESSIBILITY_CONNECT_RETRIES) {
                if (MofeiScreenshotAccessibilityService.isConnected()) {
                    Log.i(TAG, "external_capture stage=bridge_ready")
                    // The bridge is already foreground. Hide the overlay only now, then sample the
                    // fully transparent window over the app the user was viewing.
                    MascotOverlayService.hideVisibleForCapture(this@MofeiAccessibilitySetupActivity)
                    delay(SCREEN_SETTLE_MILLIS)
                    requestCurrentScreen()
                    return@launch
                }
                delay(ACCESSIBILITY_CONNECT_RETRY_MILLIS)
            }
            Toast.makeText(
                this@MofeiAccessibilitySetupActivity,
                "一键截屏尚未开启，请在系统设置中启用后重试",
                Toast.LENGTH_LONG,
            ).show()
            finishAndRestore()
        }
    }

    private fun requestCurrentScreen() {
        Log.i(TAG, "external_capture stage=requested")
        lifecycleScope.launch {
            delay(CAPTURE_TIMEOUT_MILLIS)
            if (captureResultConsumed.compareAndSet(false, true)) {
                Log.w(TAG, "external_capture stage=timeout")
                Toast.makeText(
                    this@MofeiAccessibilitySetupActivity,
                    "截屏响应超时，墨斐已恢复，请重试",
                    Toast.LENGTH_LONG,
                ).show()
                finishAndRestore()
            }
        }
        val started = MofeiScreenshotAccessibilityService.requestScreenshot { result ->
            if (!captureResultConsumed.compareAndSet(false, true)) {
                if (result is AccessibilityCaptureResult.Success) {
                    runCatching { contentResolver.delete(result.uri, null, null) }
                }
                return@requestScreenshot
            }
            runOnUiThread {
                when (result) {
                    is AccessibilityCaptureResult.Success -> {
                        Log.i(TAG, "external_capture stage=captured")
                        openPreview(result)
                    }
                    is AccessibilityCaptureResult.Failure -> {
                        Log.w(TAG, "external_capture stage=failed reason=${result.message}")
                        Toast.makeText(
                            this,
                            result.message.ifBlank { "无法读取当前屏幕，请重试" },
                            Toast.LENGTH_LONG,
                        ).show()
                        finishAndRestore()
                    }
                }
            }
        }
        if (!started) {
            captureResultConsumed.set(true)
            Log.w(TAG, "external_capture stage=service_disconnected")
            Toast.makeText(this, "一键截屏连接已中断，请重试", Toast.LENGTH_LONG).show()
            finishAndRestore()
        }
    }

    private fun openPreview(result: AccessibilityCaptureResult.Success) {
        val intent = ScreenshotPreviewActivity.captureIntent(
            context = this,
            uri = result.uri,
            restoreOverlayAfterCapture = true,
            intakeSessionId = UUID.randomUUID().toString(),
        ).addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        runCatching {
            // This Activity is foreground, so the preview launch cannot be silently denied by
            // Android's background-activity-start restrictions.
            startActivity(intent)
        }.onSuccess {
            Log.i(TAG, "external_capture stage=preview_opened")
            // ScreenshotPreviewActivity now owns restoring the external assistant.
            overlayRestored = true
            finish()
        }.onFailure {
            Log.e(TAG, "external_capture stage=preview_failed", it)
            runCatching { contentResolver.delete(result.uri, null, null) }
            Toast.makeText(this, "无法打开截图预览，请重试", Toast.LENGTH_LONG).show()
            finishAndRestore()
        }
    }

    private fun finishAndRestore() {
        if (!overlayRestored) {
            overlayRestored = true
            MascotOverlayService.restoreVisibleAfterCapture(this)
        }
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations && !overlayRestored) {
            overlayRestored = true
            MascotOverlayService.restoreVisibleAfterCapture(this)
        }
        super.onDestroy()
    }

    companion object {
        private const val ACCESSIBILITY_CONNECT_RETRIES = 15
        private const val ACCESSIBILITY_CONNECT_RETRY_MILLIS = 200L
        private const val SCREEN_SETTLE_MILLIS = 350L
        private const val CAPTURE_TIMEOUT_MILLIS = 8_000L
        private const val TAG = "MofeiCapture"
        private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
            "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

        fun intent(context: Context): Intent =
            Intent(context, MofeiAccessibilitySetupActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
    }
}
