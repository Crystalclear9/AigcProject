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
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.suishouban.app.R
import com.suishouban.app.ScreenshotPreviewActivity
import com.suishouban.app.domain.ScreenshotActionGate
import com.suishouban.app.domain.ScreenshotActionGateResult
import com.suishouban.app.ocr.TextRecognitionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenshotMonitorService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pendingScreenshotIds = mutableSetOf<Long>()
    private val ignoredScreenshotIds = mutableSetOf<Long>()
    private val ocr = TextRecognitionService()
    private val actionGate = ScreenshotActionGate()
    private var observer: ContentObserver? = null
    private var lastNotifiedId: Long = -1
    private val periodicScan = object : Runnable {
        override fun run() {
            detectLatestScreenshot()
            mainHandler.postDelayed(this, FALLBACK_SCAN_INTERVAL_MS)
        }
    }

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
        seedLatestScreenshotId()
        registerObserver()
        mainHandler.postDelayed(periodicScan, FALLBACK_SCAN_INTERVAL_MS)
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        pendingScreenshotIds.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_IGNORE_SCREENSHOT) {
            val mediaId = intent.getLongExtra(EXTRA_MEDIA_ID, -1L)
            if (mediaId > 0) ignoredScreenshotIds += mediaId
            NotificationManagerCompat.from(this).cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
        }
        return START_STICKY
    }

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
            add(MediaStore.Images.Media.SIZE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Images.Media.IS_PENDING)
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
            if (id == lastNotifiedId || id in pendingScreenshotIds || id in ignoredScreenshotIds) return
            val name = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)).orEmpty()
            val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)).orEmpty()
            } else {
                ""
            }
            val isScreenshot = listOf("Screenshots", "ScreenRecord", "截图", "截屏", "screenshot")
                .any { keyword -> name.contains(keyword, ignoreCase = true) || path.contains(keyword, ignoreCase = true) }
            if (!isScreenshot) return
            Log.i(TAG, "Screenshot candidate detected: id=$id name=$name path=$path")
            val imageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            pendingScreenshotIds.add(id)
            waitForScreenshotReady(id, imageUri, lastSize = null, deadlineMs = System.currentTimeMillis() + READY_TIMEOUT_MS)
        }
    }

    private fun waitForScreenshotReady(id: Long, uri: Uri, lastSize: Long?, deadlineMs: Long) {
        val state = readImageState(uri)
        val isStableSize = state.size > 0 && state.size == lastSize
        if (!state.isPending && isStableSize && state.canDecode) {
            pendingScreenshotIds.remove(id)
            inspectScreenshot(id, uri)
            return
        }
        if (System.currentTimeMillis() >= deadlineMs) {
            pendingScreenshotIds.remove(id)
            return
        }

        // MediaStore 可能先发变更事件再完成落盘；短轮询能避免截图未写完就弹消息。
        mainHandler.postDelayed(
            { waitForScreenshotReady(id, uri, state.size, deadlineMs) },
            READY_POLL_INTERVAL_MS,
        )
    }

    private fun readImageState(uri: Uri): ImageState {
        val projection = buildList {
            add(MediaStore.Images.Media.SIZE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Images.Media.IS_PENDING)
        }.toTypedArray()
        val cursor = contentResolver.query(uri, projection, null, null, null)
        val fromStore = cursor?.use {
            if (!it.moveToFirst()) return@use ImageState()
            val size = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))
            val isPending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.getInt(it.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)) == 1
            } else {
                false
            }
            ImageState(size = size, isPending = isPending)
        } ?: ImageState()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val canDecode = runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            bounds.outWidth > 0 && bounds.outHeight > 0
        }.getOrDefault(false)

        return fromStore.copy(canDecode = canDecode)
    }

    private fun seedLatestScreenshotId() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Images.Media.RELATIVE_PATH)
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
            val name = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)).orEmpty()
            val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)).orEmpty()
            } else {
                ""
            }
            val isScreenshot = listOf("Screenshots", "ScreenRecord", "截图", "截屏", "screenshot")
                .any { keyword -> name.contains(keyword, ignoreCase = true) || path.contains(keyword, ignoreCase = true) }
            if (isScreenshot) {
                lastNotifiedId = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                Log.i(TAG, "Seeded latest screenshot id=$lastNotifiedId")
            }
        }
    }

    private fun inspectScreenshot(id: Long, uri: Uri) {
        serviceScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { ocr.recognize(this@ScreenshotMonitorService, uri) }.getOrNull()
            }.orEmpty()
            val gate = actionGate.evaluate(text, uri.toString(), System.currentTimeMillis())
            lastNotifiedId = id
            Log.i(TAG, "Screenshot gate completed: id=$id prompt=${gate.shouldPrompt} reason=${gate.reason}")
            if (gate.shouldPrompt) {
                notifyScreenshot(id, uri, text.take(MAX_OCR_EXTRA_CHARS), gate)
            } else {
                Log.i(TAG, "Screenshot ignored by action gate: ${gate.reason}")
            }
        }
    }

    private fun notifyScreenshot(
        mediaId: Long,
        uri: Uri,
        ocrText: String,
        gate: ScreenshotActionGateResult,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notificationId = uri.hashCode()
        val intent = Intent(this, ScreenshotPreviewActivity::class.java).apply {
            action = ACTION_PROCESS_SCREENSHOT
            data = uri
            putExtra(ScreenshotPreviewActivity.EXTRA_OCR_TEXT, ocrText)
            putExtra(ScreenshotPreviewActivity.EXTRA_GATE_REASON, gate.reason)
            putExtra(ScreenshotPreviewActivity.EXTRA_DEADLINE_HINT, gate.deadlineHint)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val ignoreIntent = Intent(this, ScreenshotMonitorService::class.java).apply {
            action = ACTION_IGNORE_SCREENSHOT
            putExtra(EXTRA_MEDIA_ID, mediaId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val ignorePendingIntent = PendingIntent.getService(
            this,
            notificationId + 1,
            ignoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val content = buildPromptContent(gate)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("识别到可能的待办")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "生成行动卡", pendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "忽略", ignorePendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(this).notify(notificationId, notification)
    }

    private fun buildPromptContent(gate: ScreenshotActionGateResult): String {
        val title = gate.suggestedTitle ?: "可能的行动事项"
        return gate.deadlineHint?.takeIf { it.isNotBlank() }?.let {
            "$title，$it。是否生成行动卡？"
        } ?: "$title。是否生成行动卡？"
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
        private const val ACTION_IGNORE_SCREENSHOT = "com.suishouban.app.action.IGNORE_SCREENSHOT"
        private const val EXTRA_MEDIA_ID = "com.suishouban.app.extra.MEDIA_ID"
        private const val EXTRA_NOTIFICATION_ID = "com.suishouban.app.extra.NOTIFICATION_ID"
        private const val MAX_OCR_EXTRA_CHARS = 8_000
        private const val CHANNEL_ID = "suishouban_screenshot_monitor"
        private const val SERVICE_NOTIFICATION_ID = 2026
        private const val READY_TIMEOUT_MS = 3_000L
        private const val READY_POLL_INTERVAL_MS = 250L
        private const val FALLBACK_SCAN_INTERVAL_MS = 2_500L
        private const val TAG = "ScreenshotMonitor"
    }
}

private data class ImageState(
    val size: Long = 0L,
    val isPending: Boolean = false,
    val canDecode: Boolean = false,
)
