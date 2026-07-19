package com.suishouban.app.mascot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.suishouban.app.MainActivity
import com.suishouban.app.R
import com.suishouban.app.SuiShouBanApp
import com.suishouban.app.data.repository.AppSettings
import kotlin.math.abs

/**
 * Explicit, opt-in system overlay. It is intentionally non-sticky: Android must never resurrect
 * it after a process or service stop without a new user action in the foreground app.
 */
class MascotOverlayService : Service() {
    private val controller = MascotOverlayController()
    private lateinit var windowManager: WindowManager
    private lateinit var settingsRepository: com.suishouban.app.data.repository.AppSettingsRepository
    private var overlayView: FrameLayout? = null
    private var controlsView: LinearLayout? = null
    private var displayMode = OverlayDisplayMode.COLLAPSED
    private var placement = OverlayPlacement(OverlayDockSide.RIGHT, 0.5f)
    private var currentLayoutParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        settingsRepository = (application as SuiShouBanApp).settingsRepository
        placement = settingsRepository.settings.value.toOverlayPlacement()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                disableOverlay()
                return START_NOT_STICKY
            }
            ACTION_HIDE_ONE_HOUR -> {
                hideForOneHour()
                return START_NOT_STICKY
            }
        }
        if (!canShowOverlay()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundWithNotification()
        showCollapsedOverlay()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // A removed task is not consent to keep an always-on overlay running in the background.
        stopSelf()
    }

    override fun onDestroy() {
        removeControls()
        removeOverlay()
        super.onDestroy()
    }

    private fun canShowOverlay(): Boolean {
        val settings = settingsRepository.settings.value
        return controller.canStart(
            enabled = settings.mascotOverlayEnabled,
            overlayPermissionGranted = Settings.canDrawOverlays(this),
            hiddenUntilMillis = settings.mascotHiddenUntilMillis,
            nowMillis = System.currentTimeMillis(),
        )
    }

    private fun showCollapsedOverlay() {
        displayMode = OverlayDisplayMode.COLLAPSED
        updateOverlayView()
    }

    private fun showExpandedPreview() {
        displayMode = OverlayDisplayMode.EXPANDED
        updateOverlayView()
    }

    private fun updateOverlayView() {
        removeControls()
        removeOverlay()
        val root = FrameLayout(this).apply {
            background = capsuleBackground(displayMode)
            contentDescription = "墨斐悬浮助手"
            addView(createMascotContent(), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            setOnTouchListener(OverlayTouchListener())
        }
        val params = createLayoutParams(displayMode, placement)
        overlayView = root
        currentLayoutParams = params
        windowManager.addView(root, params)
    }

    private fun createMascotContent(): View = ComposeView(this).apply {
        setContent {
            val mascot = idleMascotState()
            MaterialTheme {
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 5.dp),
                    contentAlignment = if (displayMode == OverlayDisplayMode.COLLAPSED) Alignment.Center else Alignment.CenterStart,
                ) {
                    MofeiVisual(
                        state = mascot,
                        modifier = Modifier.fillMaxSize(),
                        reduceMotion = settingsRepository.settings.value.reduceMascotMotion,
                    )
                    if (displayMode == OverlayDisplayMode.EXPANDED) {
                        Text(
                            text = MascotVisuals.profileFor(mascot, settingsRepository.settings.value.reduceMascotMotion).message,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 8.dp)
                                .padding(start = 76.dp),
                        )
                    }
                }
            }
        }
    }

    private fun createLayoutParams(
        mode: OverlayDisplayMode,
        placement: OverlayPlacement,
    ): WindowManager.LayoutParams {
        val metrics = screenMetrics()
        val density = resources.displayMetrics.density
        val position = controller.windowPosition(
            placement = placement,
            mode = mode,
            screenWidthPx = metrics.width,
            screenHeightPx = metrics.height,
            density = density,
        )
        return WindowManager.LayoutParams(
            if (mode == OverlayDisplayMode.COLLAPSED) controller.collapsedWidthPx(density) else controller.expandedWidthPx(density),
            if (mode == OverlayDisplayMode.COLLAPSED) controller.collapsedHeightPx(density) else controller.expandedHeightPx(density),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = position.x
            y = position.y
        }
    }

    private fun openCurrentAction() {
        // Task 5 supplies action-card routing. Until then, this explicit intent opens the app only.
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_CURRENT
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        showCollapsedOverlay()
    }

    private fun showControls() {
        if (controlsView != null) return
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(242, 14, 35, 78))
            }
            addView(Button(this@MascotOverlayService).apply {
                text = "隐藏 1 小时"
                setOnClickListener { hideForOneHour() }
            })
            addView(Button(this@MascotOverlayService).apply {
                text = "关闭悬浮墨斐"
                setOnClickListener { disableOverlay() }
            })
        }
        val anchor = currentLayoutParams ?: return
        val params = WindowManager.LayoutParams(
            dp(144), dp(132), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (placement.dockSide == OverlayDockSide.LEFT) dp(24) else (screenMetrics().width - dp(168)).coerceAtLeast(0)
            y = anchor.y.coerceAtMost((screenMetrics().height - dp(132)).coerceAtLeast(0))
        }
        controlsView = controls
        windowManager.addView(controls, params)
    }

    private fun hideForOneHour() {
        updateSettings { it.copy(mascotHiddenUntilMillis = System.currentTimeMillis() + ONE_HOUR_MILLIS) }
        stopSelf()
    }

    private fun disableOverlay() {
        updateSettings { it.copy(mascotOverlayEnabled = false, mascotHiddenUntilMillis = 0L) }
        stopSelf()
    }

    private fun savePlacement() {
        updateSettings {
            it.copy(
                mascotDockSide = if (placement.dockSide == OverlayDockSide.LEFT) "left" else "right",
                mascotVerticalFraction = placement.verticalFraction,
            )
        }
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settingsRepository.update(transform(settingsRepository.settings.value))
    }

    private fun removeOverlay() {
        overlayView?.let { view -> runCatching { windowManager.removeView(view) } }
        overlayView = null
        currentLayoutParams = null
    }

    private fun removeControls() {
        controlsView?.let { view -> runCatching { windowManager.removeView(view) } }
        controlsView = null
    }

    private fun capsuleBackground(mode: OverlayDisplayMode): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(if (mode == OverlayDisplayMode.COLLAPSED) 22 else 18).toFloat()
        setColor(Color.argb(if (mode == OverlayDisplayMode.COLLAPSED) 228 else 244, 225, 242, 255))
        setStroke(dp(1), Color.argb(150, 112, 178, 255))
    }

    private fun startForegroundWithNotification() {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { action = ACTION_OPEN_CURRENT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("墨斐悬浮助手正在运行")
            .setContentText("轻点侧边胶囊查看当前事项")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "墨斐悬浮助手",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "墨斐在系统侧边以悬浮胶囊显示时的常驻通知"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun screenMetrics(): ScreenMetrics {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.let { ScreenMetrics(it.width(), it.height()) }
        } else {
            resources.displayMetrics.let { ScreenMetrics(it.widthPixels, it.heightPixels) }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class OverlayTouchListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startWindowX = 0
        private var startWindowY = 0
        private var dragging = false
        private var longPressTriggered = false
        private val longPress = Runnable {
            if (!dragging) {
                longPressTriggered = true
                if (controller.commandForLongPress() == OverlayCommand.ShowControls) showControls()
            }
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startWindowX = currentLayoutParams?.x ?: 0
                startWindowY = currentLayoutParams?.y ?: 0
                dragging = false
                longPressTriggered = false
                view.postDelayed(longPress, LONG_PRESS_TIMEOUT_MILLIS)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - downX
                val deltaY = event.rawY - downY
                if (abs(deltaX) > TOUCH_SLOP_PX || abs(deltaY) > TOUCH_SLOP_PX) {
                    dragging = true
                    view.removeCallbacks(longPress)
                    currentLayoutParams?.let { params ->
                        params.x = startWindowX + deltaX.toInt()
                        params.y = (startWindowY + deltaY.toInt()).coerceIn(0, screenMetrics().height)
                        windowManager.updateViewLayout(view, params)
                    }
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(longPress)
                if (dragging) {
                    val params = currentLayoutParams
                    if (params != null) {
                        val screen = screenMetrics()
                        placement = controller.snapPlacement(
                            releasedX = params.x,
                            releasedY = params.y,
                            screenWidthPx = screen.width,
                            screenHeightPx = screen.height,
                            density = resources.displayMetrics.density,
                        )
                        savePlacement()
                        showCollapsedOverlay()
                    }
                } else if (!longPressTriggered && event.actionMasked == MotionEvent.ACTION_UP) {
                    when (controller.commandForTap(displayMode)) {
                        OverlayCommand.Expand -> showExpandedPreview()
                        OverlayCommand.OpenCurrentAction -> openCurrentAction()
                        OverlayCommand.ShowControls -> showControls()
                    }
                }
                true
            }
            else -> true
        }
    }

    private fun AppSettings.toOverlayPlacement(): OverlayPlacement = OverlayPlacement(
        dockSide = if (mascotDockSide == "left") OverlayDockSide.LEFT else OverlayDockSide.RIGHT,
        verticalFraction = mascotVerticalFraction,
    )

    private fun idleMascotState() = MascotState(
        mood = MascotMood.IDLE,
        userMessage = "墨斐正在待命",
        colorRole = MascotColorRole.DEFAULT,
        animationHint = MascotAnimationHint.BREATHE,
    )

    private data class ScreenMetrics(val width: Int, val height: Int)

    companion object {
        const val ACTION_START = "com.suishouban.app.action.START_MOFEI_OVERLAY"
        const val ACTION_STOP = "com.suishouban.app.action.STOP_MOFEI_OVERLAY"
        const val ACTION_HIDE_ONE_HOUR = "com.suishouban.app.action.HIDE_MOFEI_ONE_HOUR"
        const val ACTION_OPEN_CURRENT = "com.suishouban.app.action.OPEN_MOFEI_CURRENT"
        private const val NOTIFICATION_CHANNEL_ID = "suishouban_mofei_overlay"
        private const val NOTIFICATION_ID = 2030
        private const val ONE_HOUR_MILLIS = 60 * 60 * 1_000L
        private const val LONG_PRESS_TIMEOUT_MILLIS = 550L
        private const val TOUCH_SLOP_PX = 12f

        /**
         * Task 5 calls this only from a foreground user gesture after the Settings permission
         * screen returns. The service performs the permission and opt-in checks again defensively.
         */
        fun startFromForegroundUserAction(context: Context): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            val settings = (context.applicationContext as SuiShouBanApp).settingsRepository.settings.value
            val controller = MascotOverlayController()
            if (!controller.canStart(settings.mascotOverlayEnabled, true, settings.mascotHiddenUntilMillis, System.currentTimeMillis())) {
                return false
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, MascotOverlayService::class.java).setAction(ACTION_START),
            )
            return true
        }

        fun overlayPermissionIntent(context: Context): Intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
    }
}
