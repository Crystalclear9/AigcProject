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
import android.widget.RemoteViews
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.suishouban.app.R
import com.suishouban.app.ScreenshotPreviewActivity
import com.suishouban.app.domain.screenshot.ScreenshotActionGate
import com.suishouban.app.domain.screenshot.ScreenshotActionGateResult
import com.suishouban.app.ocr.TextRecognitionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
            NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("随手办")
                .setContentText("截图识别在本机静默运行")
                .setGroup(SERVICE_GROUP_KEY)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
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
        when (intent?.action) {
            ACTION_IGNORE_SCREENSHOT -> {
                val mediaId = intent.getLongExtra(EXTRA_MEDIA_ID, -1L)
                if (mediaId > 0) ignoredScreenshotIds += mediaId
                clearPendingPrompt(mediaId)
                NotificationManagerCompat.from(this).cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
                Log.i(TAG, "Screenshot prompt ignored: mediaId=$mediaId")
            }
            ACTION_GENERATE_SCREENSHOT -> {
                NotificationManagerCompat.from(this).cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
                Log.i(TAG, "Screenshot prompt generate action received: uri=${intent.data}")
                clearPendingPrompt(intent.getLongExtra(EXTRA_MEDIA_ID, -1L))
                startActivity(buildPreviewIntentFromAction(intent))
            }
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
            var inspected = 0
            while (it.moveToNext() && inspected < RECENT_MEDIA_SCAN_LIMIT) {
                inspected += 1
                val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                if (id <= lastNotifiedId || id in pendingScreenshotIds || id in ignoredScreenshotIds) continue
                val name = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)).orEmpty()
                val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)).orEmpty()
                } else {
                    ""
                }
                if (!looksLikeScreenshot(name, path)) continue
                Log.i(TAG, "Screenshot candidate detected: id=$id name=$name path=$path")
                val imageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                pendingScreenshotIds.add(id)
                waitForScreenshotReady(
                    id,
                    imageUri,
                    lastSize = null,
                    deadlineMs = System.currentTimeMillis() + READY_TIMEOUT_MS,
                )
                return
            }
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
            var inspected = 0
            while (it.moveToNext() && inspected < RECENT_MEDIA_SCAN_LIMIT) {
                inspected += 1
                val name = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)).orEmpty()
                val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)).orEmpty()
                } else {
                    ""
                }
                if (looksLikeScreenshot(name, path)) {
                    lastNotifiedId = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    Log.i(TAG, "Seeded latest screenshot id=$lastNotifiedId")
                    return
                }
            }
        }
    }

    private fun looksLikeScreenshot(name: String, path: String): Boolean {
        return listOf("Screenshots", "ScreenRecord", "截图", "截屏", "screenshot")
            .any { keyword -> name.contains(keyword, ignoreCase = true) || path.contains(keyword, ignoreCase = true) }
    }

    private fun inspectScreenshot(id: Long, uri: Uri) {
        serviceScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { ocr.recognize(this@ScreenshotMonitorService, uri) }.getOrNull()
            }.orEmpty()
            val gate = actionGate.evaluate(text, uri.toString(), System.currentTimeMillis())
            lastNotifiedId = maxOf(lastNotifiedId, id)
            Log.i(TAG, "Screenshot gate completed: id=$id prompt=${gate.shouldPrompt} confidence=${gate.confidence}")
            if (gate.shouldPrompt) {
                notifyScreenshot(id, uri, text.take(MAX_OCR_EXTRA_CHARS), gate)
            } else {
                Log.i(TAG, "Screenshot ignored by action gate")
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
        val ocrToken = cachePendingOcrText(ocrText)
        val previewIntent = buildPreviewIntent(uri, ocrToken, gate).apply {
            putExtra(ScreenshotPreviewActivity.EXTRA_NOTIFICATION_ID, notificationId)
        }
        persistPendingPrompt(mediaId, uri, ocrToken, ocrText, gate, notificationId)
        val generatePendingIntent = PendingIntent.getActivity(
            this,
            notificationId + 2,
            previewIntent,
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
        val compactView = RemoteViews(packageName, R.layout.notification_action_suggestion).apply {
            setTextViewText(R.id.notification_action_title, "可能有待办")
            setTextViewText(R.id.notification_action_content, content)
            setOnClickPendingIntent(R.id.notification_action_root, generatePendingIntent)
            setOnClickPendingIntent(R.id.notification_action_title, generatePendingIntent)
            setOnClickPendingIntent(R.id.notification_action_content, generatePendingIntent)
            setOnClickPendingIntent(R.id.notification_generate, generatePendingIntent)
            setOnClickPendingIntent(R.id.notification_ignore, ignorePendingIntent)
        }
        val notification = NotificationCompat.Builder(this, PROMPT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("可能有待办")
            .setContentText(content)
            .setContentIntent(generatePendingIntent)
            .setCustomContentView(compactView)
            .addAction(R.drawable.ic_launcher_foreground, "生成", generatePendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "忽略", ignorePendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setTimeoutAfter(PROMPT_TIMEOUT_MS)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(this).notify(notificationId, notification)
    }

    private fun buildPreviewIntent(
        uri: Uri,
        ocrToken: String,
        gate: ScreenshotActionGateResult,
    ): Intent {
        return Intent(this, ScreenshotPreviewActivity::class.java).apply {
            action = ACTION_PROCESS_SCREENSHOT
            data = uri
            putExtra(ScreenshotPreviewActivity.EXTRA_OCR_TOKEN, ocrToken)
            putExtra(ScreenshotPreviewActivity.EXTRA_GATE_REASON, gate.reason)
            putExtra(ScreenshotPreviewActivity.EXTRA_DEADLINE_HINT, gate.deadlineHint)
            putExtra(ScreenshotPreviewActivity.EXTRA_PROMPT_SUMMARY, gate.promptSummary)
            putExtra(ScreenshotPreviewActivity.EXTRA_CONFIDENCE_BAND, gate.confidenceBand)
            putExtra(ScreenshotPreviewActivity.EXTRA_SCENARIO_TYPE, gate.scenarioType)
            putStringArrayListExtra(
                ScreenshotPreviewActivity.EXTRA_PRIMARY_EVIDENCE,
                ArrayList(gate.primaryEvidence),
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun buildPreviewIntentFromAction(source: Intent): Intent {
        return Intent(this, ScreenshotPreviewActivity::class.java).apply {
            action = ACTION_PROCESS_SCREENSHOT
            data = source.data
            source.extras?.let { putExtras(it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun buildPromptContent(gate: ScreenshotActionGateResult): String {
        return gate.promptSummary
            ?: gate.deadlineHint?.takeIf { it.isNotBlank() }?.let { "可能的行动事项 · $it" }
            ?: gate.suggestedTitle
            ?: "可能的行动事项"
    }

    private fun persistPendingPrompt(
        mediaId: Long,
        uri: Uri,
        ocrToken: String,
        ocrText: String,
        gate: ScreenshotActionGateResult,
        notificationId: Int,
    ) {
        getSharedPreferences(PENDING_PROMPT_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PENDING_MEDIA_ID, mediaId)
            .putString(KEY_PENDING_URI, uri.toString())
            .putString(KEY_PENDING_OCR_TOKEN, ocrToken)
            .putString(KEY_PENDING_OCR_SUMMARY, ocrText.take(PENDING_OCR_SUMMARY_CHARS))
            .putString(KEY_PENDING_GATE_REASON, gate.reason)
            .putString(KEY_PENDING_DEADLINE_HINT, gate.deadlineHint)
            .putString(KEY_PENDING_PROMPT_SUMMARY, gate.promptSummary)
            .putString(KEY_PENDING_CONFIDENCE_BAND, gate.confidenceBand)
            .putString(KEY_PENDING_SCENARIO_TYPE, gate.scenarioType)
            .putStringSet(KEY_PENDING_PRIMARY_EVIDENCE, gate.primaryEvidence.toSet())
            .putInt(KEY_PENDING_NOTIFICATION_ID, notificationId)
            .putLong(KEY_PENDING_CREATED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun clearPendingPrompt(mediaId: Long) {
        val prefs = getSharedPreferences(PENDING_PROMPT_PREFS, Context.MODE_PRIVATE)
        if (mediaId <= 0 || prefs.getLong(KEY_PENDING_MEDIA_ID, -1L) == mediaId) {
            clearPendingOcrText(prefs.getString(KEY_PENDING_OCR_TOKEN, null))
            prefs.edit().clear().apply()
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                SERVICE_CHANNEL_ID,
                "截图识别服务",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                PROMPT_CHANNEL_ID,
                "行动建议",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "仅在截图里发现明确行动事项时提示"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    companion object {
        const val ACTION_PROCESS_SCREENSHOT = "com.suishouban.app.action.PROCESS_SCREENSHOT"
        fun consumePendingOcrText(token: String?): String? {
            if (token.isNullOrBlank()) return null
            prunePendingOcrCache()
            return pendingOcrText.remove(token)?.text
        }

        fun consumePendingPreviewIntent(context: Context): Intent? {
            val prefs = context.getSharedPreferences(PENDING_PROMPT_PREFS, Context.MODE_PRIVATE)
            val createdAt = prefs.getLong(KEY_PENDING_CREATED_AT, 0L)
            if (createdAt <= 0L) return null
            if (System.currentTimeMillis() - createdAt > PROMPT_TIMEOUT_MS) {
                clearPendingOcrText(prefs.getString(KEY_PENDING_OCR_TOKEN, null))
                prefs.edit().clear().apply()
                return null
            }
            val uri = prefs.getString(KEY_PENDING_URI, null)?.let(Uri::parse) ?: return null
            val evidence = prefs.getStringSet(KEY_PENDING_PRIMARY_EVIDENCE, emptySet()).orEmpty()
            val ocrToken = prefs.getString(KEY_PENDING_OCR_TOKEN, null)
            val intent = Intent(context, ScreenshotPreviewActivity::class.java).apply {
                action = ACTION_PROCESS_SCREENSHOT
                data = uri
                putExtra(ScreenshotPreviewActivity.EXTRA_OCR_TOKEN, ocrToken)
                putExtra(ScreenshotPreviewActivity.EXTRA_GATE_REASON, prefs.getString(KEY_PENDING_GATE_REASON, null))
                putExtra(ScreenshotPreviewActivity.EXTRA_DEADLINE_HINT, prefs.getString(KEY_PENDING_DEADLINE_HINT, null))
                putExtra(ScreenshotPreviewActivity.EXTRA_PROMPT_SUMMARY, prefs.getString(KEY_PENDING_PROMPT_SUMMARY, null))
                putExtra(ScreenshotPreviewActivity.EXTRA_CONFIDENCE_BAND, prefs.getString(KEY_PENDING_CONFIDENCE_BAND, null))
                putExtra(ScreenshotPreviewActivity.EXTRA_SCENARIO_TYPE, prefs.getString(KEY_PENDING_SCENARIO_TYPE, null))
                putStringArrayListExtra(ScreenshotPreviewActivity.EXTRA_PRIMARY_EVIDENCE, ArrayList(evidence.toList()))
                putExtra(ScreenshotPreviewActivity.EXTRA_NOTIFICATION_ID, prefs.getInt(KEY_PENDING_NOTIFICATION_ID, 0))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            prefs.edit().clear().apply()
            return intent
        }

        fun clearPendingPreview(context: Context) {
            val prefs = context.getSharedPreferences(PENDING_PROMPT_PREFS, Context.MODE_PRIVATE)
            clearPendingOcrText(prefs.getString(KEY_PENDING_OCR_TOKEN, null))
            prefs.edit().clear().apply()
        }

        private fun cachePendingOcrText(text: String): String {
            prunePendingOcrCache()
            val token = UUID.randomUUID().toString()
            pendingOcrText[token] = PendingOcrText(text = text, createdAt = System.currentTimeMillis())
            return token
        }

        private fun clearPendingOcrText(token: String?) {
            if (!token.isNullOrBlank()) {
                pendingOcrText.remove(token)
            }
        }

        private fun prunePendingOcrCache() {
            val now = System.currentTimeMillis()
            pendingOcrText.entries.removeIf { now - it.value.createdAt > PROMPT_TIMEOUT_MS }
        }

        private const val ACTION_GENERATE_SCREENSHOT = "com.suishouban.app.action.GENERATE_SCREENSHOT"
        private const val ACTION_IGNORE_SCREENSHOT = "com.suishouban.app.action.IGNORE_SCREENSHOT"
        private const val EXTRA_MEDIA_ID = "com.suishouban.app.extra.MEDIA_ID"
        private const val EXTRA_NOTIFICATION_ID = "com.suishouban.app.extra.NOTIFICATION_ID"
        private const val MAX_OCR_EXTRA_CHARS = 8_000
        private const val SERVICE_CHANNEL_ID = "suishouban_screenshot_monitor"
        private const val PROMPT_CHANNEL_ID = "suishouban_action_suggestions"
        private const val SERVICE_GROUP_KEY = "suishouban.monitor.service"
        private const val SERVICE_NOTIFICATION_ID = 2026
        private const val READY_TIMEOUT_MS = 3_000L
        private const val READY_POLL_INTERVAL_MS = 250L
        private const val FALLBACK_SCAN_INTERVAL_MS = 2_500L
        private const val RECENT_MEDIA_SCAN_LIMIT = 20
        private const val PROMPT_TIMEOUT_MS = 10 * 60 * 1000L
        private const val PENDING_PROMPT_PREFS = "screenshot_prompt_pending"
        private const val KEY_PENDING_MEDIA_ID = "media_id"
        private const val KEY_PENDING_URI = "uri"
        private const val KEY_PENDING_OCR_TOKEN = "ocr_token"
        private const val KEY_PENDING_OCR_SUMMARY = "ocr_summary"
        private const val KEY_PENDING_GATE_REASON = "gate_reason"
        private const val KEY_PENDING_DEADLINE_HINT = "deadline_hint"
        private const val KEY_PENDING_PROMPT_SUMMARY = "prompt_summary"
        private const val KEY_PENDING_CONFIDENCE_BAND = "confidence_band"
        private const val KEY_PENDING_SCENARIO_TYPE = "scenario_type"
        private const val KEY_PENDING_PRIMARY_EVIDENCE = "primary_evidence"
        private const val KEY_PENDING_NOTIFICATION_ID = "notification_id"
        private const val KEY_PENDING_CREATED_AT = "created_at"
        private const val PENDING_OCR_SUMMARY_CHARS = 160
        private const val TAG = "ScreenshotMonitor"
        private val pendingOcrText = ConcurrentHashMap<String, PendingOcrText>()
    }
}

private data class PendingOcrText(
    val text: String,
    val createdAt: Long,
)

private data class ImageState(
    val size: Long = 0L,
    val isPending: Boolean = false,
    val canDecode: Boolean = false,
)
