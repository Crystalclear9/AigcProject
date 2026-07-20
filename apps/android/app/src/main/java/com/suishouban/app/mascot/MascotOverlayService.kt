package com.suishouban.app.mascot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import com.suishouban.app.MainActivity
import com.suishouban.app.R
import com.suishouban.app.SuiShouBanApp
import com.suishouban.app.capture.MofeiScreenCaptureActivity
import com.suishouban.app.data.repository.AppSettings
import com.suishouban.app.mascot.action.MofeiAction
import com.suishouban.app.mascot.action.MofeiActionCommand
import com.suishouban.app.mascot.action.MofeiActionCoordinator
import com.suishouban.app.mascot.action.MofeiCapabilityState
import com.suishouban.app.mascot.action.MofeiPermissionState
import com.suishouban.app.mascot.action.MofeiSurface
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Explicit, opt-in system overlay. It is intentionally non-sticky: Android must never resurrect
 * it after a process or service stop without a new user action in the foreground app.
 */
class MascotOverlayService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {
    private val controller = MascotOverlayController()
    private val resolver = MascotStateResolver()
    private val overlayViewModelStore = ViewModelStore()
    private val overlaySavedStateController = SavedStateRegistryController.create(this)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var settingsRepository: com.suishouban.app.data.repository.AppSettingsRepository
    private var overlayView: FrameLayout? = null
    private var controlsView: LinearLayout? = null
    private var displayMode = OverlayDisplayMode.COLLAPSED
    private var placement = OverlayPlacement(OverlayDockSide.RIGHT, 0.5f)
    private var currentLayoutParams: WindowManager.LayoutParams? = null
    private var currentMascotState = idleMascotState()
    private var foregroundStarted = false
    private var hiddenRestore: Runnable? = null
    private var pendingNotificationDrafts: Int = 0

    override val viewModelStore: ViewModelStore
        get() = overlayViewModelStore
    override val savedStateRegistry: SavedStateRegistry
        get() = overlaySavedStateController.savedStateRegistry

    override fun onCreate() {
        overlaySavedStateController.performAttach()
        overlaySavedStateController.performRestore(null)
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        settingsRepository = (application as SuiShouBanApp).settingsRepository
        placement = settingsRepository.settings.value.toOverlayPlacement()
        ensureNotificationChannel()
        observeBackgroundState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Both start and update intents may carry the latest state snapshot from AppViewModel.
        intent?.toMascotState()?.let { currentMascotState = it }
        when (intent?.action) {
            ACTION_DISMISS_FOR_FOREGROUND -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                disableOverlay()
                return START_NOT_STICKY
            }
            ACTION_HIDE_ONE_HOUR -> {
                hideForOneHour()
                return START_NOT_STICKY
            }
            ACTION_RESTORE_AFTER_CAPTURE -> {
                if (canShowOverlay()) {
                    if (!foregroundStarted) startForegroundWithNotification()
                    foregroundStarted = true
                    showCollapsedOverlay()
                } else {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> Unit
        }
        if (!canShowOverlay()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundWithNotification()
        foregroundStarted = true
        showCollapsedOverlay()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // A removed task is not consent to keep an always-on overlay running in the background.
        stopSelf()
    }

    override fun onDestroy() {
        cancelHiddenRestore()
        serviceScope.cancel()
        removeControls()
        removeOverlay()
        overlayViewModelStore.clear()
        super.onDestroy()
    }

    /** Keeps the overlay semantic state live after the Activity's lifecycle collector has stopped. */
    private fun observeBackgroundState() {
        val app = application as SuiShouBanApp
        serviceScope.launch {
            app.mascotStateStore.state.collect { state -> updateMascotState(state) }
        }
        serviceScope.launch {
            app.cardRepository.observeAll().collect { cards ->
                val cardState = resolver.resolve(cards = cards, workflowStatus = null)
                // Persisted deadlines are authoritative while the app is backgrounded. Preserve
                // ephemeral focus/confirmation/completion state when there is no timed card.
                if (cardState.mood in CARD_BACKED_MOODS) updateMascotState(cardState)
            }
        }
        serviceScope.launch {
            settingsRepository.settings.collect { settings -> handleSettingsChanged(settings) }
        }
        serviceScope.launch {
            app.notificationCandidateRepository.observeActiveCount().collect { count ->
                pendingNotificationDrafts = count
                if (displayMode == OverlayDisplayMode.EXPANDED && overlayView != null) updateOverlayView()
            }
        }
    }

    private fun updateMascotState(next: MascotState) {
        if (currentMascotState == next) return
        currentMascotState = next
        if (overlayView != null) updateOverlayView()
    }

    private fun handleSettingsChanged(settings: AppSettings) {
        placement = settings.toOverlayPlacement()
        if (!settings.mascotOverlayEnabled || !Settings.canDrawOverlays(this)) {
            if (foregroundStarted) stopSelf()
            return
        }
        if (settings.mascotHiddenUntilMillis > System.currentTimeMillis()) {
            removeControls()
            removeOverlay()
            scheduleHiddenRestore(settings.mascotHiddenUntilMillis)
        } else {
            cancelHiddenRestore()
            if (foregroundStarted && overlayView == null) showCollapsedOverlay()
        }
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
            background = if (displayMode == OverlayDisplayMode.COLLAPSED) {
                capsuleBackground(displayMode)
            } else {
                GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            }
            contentDescription = overlayContentDescription()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable = true
            isFocusable = true
            setOnClickListener { handleTap() }
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
        // System overlays have no Activity decor tree, so install all owners explicitly.
        setViewTreeLifecycleOwner(this@MascotOverlayService)
        setViewTreeViewModelStoreOwner(this@MascotOverlayService)
        setViewTreeSavedStateRegistryOwner(this@MascotOverlayService)
        setContent {
            val mascot = currentMascotState
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (displayMode == OverlayDisplayMode.EXPANDED) {
                        val settings = settingsRepository.settings.value
                        val items = MofeiActionCoordinator().actionsFor(
                            MofeiSurface.OVERLAY,
                            MofeiCapabilityState(
                                overlayGranted = Settings.canDrawOverlays(this@MascotOverlayService),
                                notificationAccessGranted = MofeiPermissionState.notificationAccessGranted(this@MascotOverlayService),
                                notificationDraftsEnabled = settings.mofeiNotificationDraftsEnabled,
                                latestScreenshotAvailable = true,
                                pendingNotificationDrafts = pendingNotificationDrafts,
                            ),
                        )
                        MofeiActionRing(
                            surface = MofeiSurface.OVERLAY,
                            items = items,
                            expanded = true,
                            reduceMotion = settings.reduceMascotMotion,
                            onAction = ::executeOverlayAction,
                            onDismiss = ::showCollapsedOverlay,
                            mirrorCompact = controller.shouldMirrorCompactRing(placement.dockSide),
                            modifier = Modifier.size(276.dp),
                        )
                    }
                    MofeiVisual(
                        state = mascot,
                        modifier = if (displayMode == OverlayDisplayMode.COLLAPSED) {
                            Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 5.dp)
                        } else {
                            Modifier.size(66.dp)
                        },
                        reduceMotion = settingsRepository.settings.value.reduceMascotMotion,
                    )
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = position.x
            y = position.y
        }
    }

    private fun executeOverlayAction(action: MofeiAction) {
        val command = controller.commandForAction(action, currentMascotState.actionCardId)
        val intent = when (command) {
            MofeiActionCommand.RequestScreenCapture -> MofeiScreenCaptureActivity.intent(
                this,
                restoreOverlayAfter = true,
            )
            else -> Intent(this, MainActivity::class.java).apply {
                this.action = ACTION_OPEN_MOFEI_ACTION
                putExtra(EXTRA_MOFEI_ACTION, action.name)
                putExtra(EXTRA_ACTION_CARD_ID, currentMascotState.actionCardId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
        val pending = PendingIntent.getActivity(
            this,
            4000 + action.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (command == MofeiActionCommand.RequestScreenCapture) {
            // Keep Mofei out of captured pixels. Consent/preview restores it on every exit path.
            removeControls()
            removeOverlay()
        }
        runCatching { pending.send() }
            .onFailure { showActionFallback("无法打开" + actionFallbackLabel(action) + "，请进入随手办重试") }
            .onFailure {
                if (command == MofeiActionCommand.RequestScreenCapture && canShowOverlay()) showCollapsedOverlay()
            }
        if (command != MofeiActionCommand.RequestScreenCapture) showCollapsedOverlay()
    }

    private fun showActionFallback(message: String) {
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("墨斐需要你打开随手办")
            .setContentText(message)
            .setOngoing(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
    }

    private fun actionFallbackLabel(action: MofeiAction): String = when (action) {
        MofeiAction.CAPTURE_CURRENT_SCREEN -> "当前屏幕识别"
        MofeiAction.ANALYZE_LATEST_SCREENSHOT -> "最近截图"
        MofeiAction.REVIEW_NOTIFICATION_DRAFTS -> "通知草稿"
        MofeiAction.OPEN_CURRENT_CARD -> "当前事项"
        MofeiAction.OPEN_SETTINGS -> "设置"
        MofeiAction.PICK_IMAGE -> "相册"
        MofeiAction.TAKE_PHOTO -> "相机"
    }

    private fun handleTap() {
        when (controller.commandForTap(displayMode)) {
            OverlayCommand.Expand -> showExpandedPreview()
            OverlayCommand.Collapse -> showCollapsedOverlay()
            OverlayCommand.ShowControls -> showControls()
        }
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
        val hiddenUntil = System.currentTimeMillis() + ONE_HOUR_MILLIS
        updateSettings { it.copy(mascotHiddenUntilMillis = hiddenUntil) }
        removeControls()
        removeOverlay()
        scheduleHiddenRestore(hiddenUntil)
    }

    /** The opted-in foreground service remains alive, but never self-restarts after process death. */
    private fun scheduleHiddenRestore(hiddenUntilMillis: Long) {
        cancelHiddenRestore()
        val restore = Runnable {
            updateSettings { it.copy(mascotHiddenUntilMillis = 0L) }
            if (canShowOverlay()) showCollapsedOverlay() else stopSelf()
        }
        hiddenRestore = restore
        mainHandler.postDelayed(restore, (hiddenUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    private fun cancelHiddenRestore() {
        hiddenRestore?.let(mainHandler::removeCallbacks)
        hiddenRestore = null
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
            MotionEvent.ACTION_OUTSIDE -> {
                if (displayMode == OverlayDisplayMode.EXPANDED) showCollapsedOverlay()
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
                    view.performClick()
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

    private fun overlayContentDescription(): String {
        val profile = MascotVisuals.profileFor(
            currentMascotState,
            settingsRepository.settings.value.reduceMascotMotion,
        )
        val action = if (displayMode == OverlayDisplayMode.COLLAPSED) "轻点查看提醒，长按显示控制" else "轻点打开当前事项"
        return "${profile.contentDescription}。$action"
    }

    /** Restores the semantic snapshot sent by the foreground app without exposing card contents. */
    private fun Intent.toMascotState(): MascotState? {
        val mood = getStringExtra(EXTRA_MOOD)?.let { runCatching { MascotMood.valueOf(it) }.getOrNull() } ?: return null
        val color = getStringExtra(EXTRA_COLOR_ROLE)?.let { runCatching { MascotColorRole.valueOf(it) }.getOrNull() } ?: return null
        val animation = getStringExtra(EXTRA_ANIMATION_HINT)?.let { runCatching { MascotAnimationHint.valueOf(it) }.getOrNull() } ?: return null
        return MascotState(
            mood = mood,
            actionCardId = getStringExtra(EXTRA_ACTION_CARD_ID),
            userMessage = getStringExtra(EXTRA_MESSAGE).orEmpty(),
            colorRole = color,
            animationHint = animation,
        )
    }

    private data class ScreenMetrics(val width: Int, val height: Int)

    companion object {
        const val ACTION_START = "com.suishouban.app.action.START_MOFEI_OVERLAY"
        const val ACTION_STOP = "com.suishouban.app.action.STOP_MOFEI_OVERLAY"
        const val ACTION_HIDE_ONE_HOUR = "com.suishouban.app.action.HIDE_MOFEI_ONE_HOUR"
        const val ACTION_OPEN_CURRENT = "com.suishouban.app.action.OPEN_MOFEI_CURRENT"
        const val ACTION_OPEN_MOFEI_ACTION = "com.suishouban.app.action.OPEN_MOFEI_ACTION"
        const val ACTION_UPDATE = "com.suishouban.app.action.UPDATE_MOFEI_OVERLAY"
        private const val ACTION_RESTORE_AFTER_CAPTURE = "com.suishouban.app.action.RESTORE_MOFEI_AFTER_CAPTURE"
        const val ACTION_DISMISS_FOR_FOREGROUND = "com.suishouban.app.action.DISMISS_MOFEI_FOR_FOREGROUND"
        const val EXTRA_ACTION_CARD_ID = "com.suishouban.app.extra.MOFEI_ACTION_CARD_ID"
        const val EXTRA_MOFEI_ACTION = "com.suishouban.app.extra.MOFEI_ACTION"
        private const val EXTRA_MOOD = "com.suishouban.app.extra.MOFEI_MOOD"
        private const val EXTRA_COLOR_ROLE = "com.suishouban.app.extra.MOFEI_COLOR_ROLE"
        private const val EXTRA_ANIMATION_HINT = "com.suishouban.app.extra.MOFEI_ANIMATION_HINT"
        private const val EXTRA_MESSAGE = "com.suishouban.app.extra.MOFEI_MESSAGE"
        private const val NOTIFICATION_CHANNEL_ID = "suishouban_mofei_overlay"
        private const val NOTIFICATION_ID = 2030
        private const val ONE_HOUR_MILLIS = 60 * 60 * 1_000L
        private const val LONG_PRESS_TIMEOUT_MILLIS = 550L
        private const val TOUCH_SLOP_PX = 12f
        private val CARD_BACKED_MOODS = setOf(MascotMood.REMINDER, MascotMood.DUE_SOON, MascotMood.URGENT)

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

        /** Starts only after the user has opted in; activity lifecycle uses it to restore the edge pill. */
        fun restoreAfterAppBackground(context: Context, mascotState: MascotState): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            val settings = (context.applicationContext as SuiShouBanApp).settingsRepository.settings.value
            val controller = MascotOverlayController()
            if (!controller.canStart(settings.mascotOverlayEnabled, true, settings.mascotHiddenUntilMillis, System.currentTimeMillis())) {
                return false
            }
            // Android can reject foreground-service starts after the short lifecycle grace
            // window. The opt-in remains saved and the next foreground/background transition
            // retries it; a rejected restore must never crash the host activity.
            return runCatching {
                ContextCompat.startForegroundService(
                    context,
                    overlayIntent(context, ACTION_START, mascotState),
                )
                true
            }.getOrDefault(false)
        }

        /** Foreground content replaces the overlay without revoking the user's opt-in setting. */
        fun dismissForForeground(context: Context) {
            context.startService(Intent(context, MascotOverlayService::class.java).setAction(ACTION_DISMISS_FOR_FOREGROUND))
        }

        fun updateState(context: Context, mascotState: MascotState) {
            context.startService(overlayIntent(context, ACTION_UPDATE, mascotState))
        }

        fun restoreVisibleAfterCapture(context: Context) {
            context.startService(
                Intent(context, MascotOverlayService::class.java).setAction(ACTION_RESTORE_AFTER_CAPTURE),
            )
        }

        private fun overlayIntent(context: Context, action: String, mascotState: MascotState): Intent =
            Intent(context, MascotOverlayService::class.java).apply {
                this.action = action
                putExtra(EXTRA_ACTION_CARD_ID, mascotState.actionCardId)
                putExtra(EXTRA_MOOD, mascotState.mood.name)
                putExtra(EXTRA_COLOR_ROLE, mascotState.colorRole.name)
                putExtra(EXTRA_ANIMATION_HINT, mascotState.animationHint.name)
                putExtra(EXTRA_MESSAGE, mascotState.userMessage)
            }

        fun overlayPermissionIntent(context: Context): Intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
    }
}
