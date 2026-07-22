package com.suishouban.app.capture

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.suishouban.app.mascot.MascotOverlayService

/** Explains and opens the only system permission used by external one-shot screenshot capture. */
class MofeiAccessibilitySetupActivity : ComponentActivity() {
    private var waitingForSettings = false
    private var overlayRestored = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "当前 Android 版本不支持墨斐直接截屏", Toast.LENGTH_LONG).show()
            finishAndRestore()
            return
        }
        if (MofeiScreenshotAccessibilityService.isConnected()) {
            Toast.makeText(this, "一键截屏已开启，请回到目标页面再次点击截屏", Toast.LENGTH_LONG).show()
            finishAndRestore()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("开启墨斐一键截屏")
            .setMessage(
                "系统需要启用“墨斐一键截屏”服务。它只在你点击截屏后读取一次当前画面，" +
                    "用于识别和预览待办，不读取页面控件或输入内容。",
            )
            .setPositiveButton("前往开启") { _, _ ->
                waitingForSettings = true
                runCatching {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }.onFailure {
                    Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_LONG).show()
                    finishAndRestore()
                }
            }
            .setNegativeButton("暂不开启") { _, _ -> finishAndRestore() }
            .setOnCancelListener { finishAndRestore() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (!waitingForSettings) return
        waitingForSettings = false
        val message = if (MofeiScreenshotAccessibilityService.isConnected()) {
            "一键截屏已开启，请回到目标页面再次点击截屏"
        } else {
            "未开启一键截屏，墨斐不会改用共享屏幕"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finishAndRestore()
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
        fun intent(context: Context): Intent =
            Intent(context, MofeiAccessibilitySetupActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
    }
}
