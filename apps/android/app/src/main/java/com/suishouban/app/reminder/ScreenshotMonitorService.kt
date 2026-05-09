package com.suishouban.app.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.suishouban.app.MainActivity
import com.suishouban.app.R

class ScreenshotMonitorService : Service() {
    private var observer: ContentObserver? = null
    private var lastNotifiedId: Long = -1

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(
            SERVICE_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("随手办")
                .setContentText("截图入口已开启")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
        registerObserver()
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerObserver() {
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                detectLatestScreenshot()
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer ?: return,
        )
    }

    private fun detectLatestScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Images.Media.RELATIVE_PATH)
            add(MediaStore.Images.Media.DATE_ADDED)
        }.toTypedArray()
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        ) ?: return

        cursor.use {
            if (!it.moveToFirst()) return
            val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
            if (id == lastNotifiedId) return
            val name = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)).orEmpty()
            val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)).orEmpty()
            } else {
                ""
            }
            val isScreenshot = listOf("Screenshots", "ScreenRecord", "截图", "截屏", "screenshot")
                .any { keyword -> name.contains(keyword, ignoreCase = true) || path.contains(keyword, ignoreCase = true) }
            if (!isScreenshot) return
            lastNotifiedId = id
            val imageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            notifyScreenshot(imageUri)
        }
    }

    private fun notifyScreenshot(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_PROCESS_SCREENSHOT
            data = uri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            uri.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("检测到新截图")
            .setContentText("生成行动卡")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(this).notify(uri.hashCode(), notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "截图入口",
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
    }

    companion object {
        const val ACTION_PROCESS_SCREENSHOT = "com.suishouban.app.action.PROCESS_SCREENSHOT"
        private const val CHANNEL_ID = "suishouban_screenshot_monitor"
        private const val SERVICE_NOTIFICATION_ID = 2026
    }
}
