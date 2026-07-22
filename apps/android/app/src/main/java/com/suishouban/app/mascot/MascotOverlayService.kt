package com.suishouban.app.mascot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Rect
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
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.suishouban.app.MainActivity
import com.suishouban.app.R
import com.suishouban.app.ScreenshotPreviewActivity
import com.suishouban.app.SuiShouBanApp
import com.suishouban.app.capture.AccessibilityCaptureResult
import com.suishouban.app.capture.MofeiAccessibilitySetupActivity
import com.suishouban.app.capture.MofeiScreenshotAccessibilityService
import com.suishouban.app.data.model.ActionCard
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
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
    private var revealedOverlayAction: MofeiAction? = null
    private var backgroundCards: List<ActionCard> = emptyList()

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
        // LifecycleService dispatches service lifecycle events from the superclass implementation.
        super.onStartCommand(intent, flags, startId)
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
                backgroundCards = cards
                refreshBackgroundCardState()
            }
        }
        serviceScope.launch {
            while (isActive) {
                delay(
                    MascotRefreshPolicy.nextDelayMillis(
                        deadlines = backgroundCards.map { it.deadline },
                        now = java.time.Instant.now(),
                    ),
                )
                refreshBackgroundCardState()
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
        revealedOverlayAction = null
        displayMode = OverlayDisplayMode.COLLAPSED
        updateOverlayView()
    }

    private fun refreshBackgroundCardState() {
        val cardState = resolver.resolve(cards = backgroundCards, workflowStatus = null)
        // New cards may take ownership, and an expired owner must release its stale alert state.
        // Otherwise preserve transient focus/confirmation/completion feedback from the app.
        if (MascotBackgroundStatePolicy.shouldApply(currentMascotState.mood, cardState.mood)) {
            updateMascotState(cardState)
        }
    }

    private fun showExpandedPreview() {
        revealedOverlayAction = null
        displayMode = OverlayDisplayMode.EXPANDED
        updateOverlayView()
    }

    private fun updateOverlayView() {
        removeControls()
        removeOverlay()
        val root = OverlayGestureFrameLayout().apply {
            // The resting overlay is only a half-visible Mofei; chrome appears after expansion.
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            if (displayMode == OverlayDisplayMode.COLLAPSED) {
                contentDescription = overlayContentDescription()
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                isClickable = true
                isFocusable = true
                setOnClickListener { handleTap() }
            } else {
                // Let each Compose action expose and handle its own click semantics.
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                isClickable = false
                isFocusable = false
            }
            val content = createMascotContent()
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            // This parent intercepts only Mofei itself. The arc orbs continue to receive Compose
            // clicks, while Mofei remains a reliable drag handle in both visual states.
            setOnTouchListener(OverlayTouchListener())
        }
        // WindowRecomposer searches from the WindowManager root during attachment.
        MofeiOverlayViewTreeOwners.install(root, this, this, this)
        val params = createLayoutParams(displayMode, placement)
        overlayView = root
        currentLayoutParams = params
        windowManager.addView(root, params)
        excludeMofeiFromSystemEdgeGestures(root)
    }

    /** Keeps vivo's edge assistant/back gesture from stealing a drag that starts on Mofei. */
    private fun excludeMofeiFromSystemEdgeGestures(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        root.post {
            val mascotPx = dp(MofeiSideArcGeometry.MASCOT_SIZE_DP.toInt())
            val top = ((root.height - mascotPx) / 2).coerceAtLeast(0)
            val left = if (displayMode == OverlayDisplayMode.COLLAPSED || placement.dockSide == OverlayDockSide.LEFT) {
                0
            } else {
                (root.width - mascotPx).coerceAtLeast(0)
            }
            root.systemGestureExclusionRects = listOf(
                Rect(
                    left,
                    top,
                    (left + mascotPx).coerceAtMost(root.width),
                    (top + mascotPx).coerceAtMost(root.height),
                ),
            )
        }
    }

    private fun createMascotContent(): View {
        // This WindowManager view is rebuilt whenever service state changes. Capture one coherent
        // snapshot here instead of reading non-Compose StateFlow values during composition.
        val mascot = currentMascotState
        val mode = displayMode
        val dockSide = placement.dockSide
        val settings = settingsRepository.settings.value
        val notificationDraftCount = pendingNotificationDrafts
        val actionPreview = revealedOverlayAction
        return ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (mode == OverlayDisplayMode.EXPANDED) {
                        val items = MofeiActionCoordinator().actionsFor(
                            MofeiSurface.OVERLAY,
                            MofeiCapabilityState(
                                overlayGranted = Settings.canDrawOverlays(this@MascotOverlayService),
                                notificationAccessGranted = MofeiPermissionState.notificationAccessGranted(this@MascotOverlayService),
                                notificationDraftsEnabled = settings.mofeiNotificationDraftsEnabled,
                                latestScreenshotAvailable = true,
                                pendingNotificationDrafts = notificationDraftCount,
                                currentActionCardAvailable = !mascot.actionCardId.isNullOrBlank(),
                            ),
                        )
                        MofeiActionRing(
                            surface = MofeiSurface.OVERLAY,
                            items = items,
                            expanded = true,
                            reduceMotion = settings.reduceMascotMotion,
                            onAction = ::executeOverlayAction,
                            onDismiss = ::showCollapsedOverlay,
                            revealedActionOverride = actionPreview,
                            onActionPreview = ::previewOverlayAction,
                            dockSide = dockSide,
                            modifier = Modifier.size(
                                MofeiSideArcGeometry.WIDTH_DP.dp,
                                MofeiSideArcGeometry.HEIGHT_DP.dp,
                            ).zIndex(2f),
                        )
                    }
                    MofeiVisual(
                        state = mascot,
                        modifier = if (mode == OverlayDisplayMode.COLLAPSED) {
                            Modifier.size(MofeiSideArcGeometry.MASCOT_SIZE_DP.dp)
                        } else {
                            Modifier
                                .align(
                                    if (dockSide == OverlayDockSide.LEFT) {
                                        Alignment.CenterStart
                                    } else {
                                        Alignment.CenterEnd
                                    },
                                )
                                .size(MofeiSideArcGeometry.MASCOT_SIZE_DP.dp)
                        },
                        reduceMotion = settings.reduceMascotMotion,
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = position.x
            y = position.y
        }
    }

    private fun executeOverlayAction(action: MofeiAction) {
        val command = controller.commandForAction(action, currentMascotState.actionCardId)
        if (command == MofeiActionCommand.RequestScreenCapture) {
            executeAccessibilityScreenshot()
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            this.action = ACTION_OPEN_MOFEI_ACTION
            putExtra(EXTRA_MOFEI_ACTION, action.name)
            putExtra(EXTRA_ACTION_CARD_ID, currentMascotState.actionCardId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            this,
            4000 + action.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { pending.send() }
            .onFailure { showActionFallback("无法打开" + actionFallbackLabel(action) + "，请进入随手办重试") }
        showCollapsedOverlay()
    }

    private fun executeAccessibilityScreenshot() {
        val plan = MofeiOverlayCapturePlan.begin(
            apiLevel = Build.VERSION.SDK_INT,
            accessibilityConnected = MofeiScreenshotAccessibilityService.isConnected(),
        )
        if (plan.removeOverlay) {
            // Both controls and Mofei must be detached before Android samples the display.
            removeControls()
            removeOverlay()
        }

        when (plan.start) {
            MofeiOverlayCaptureStart.CAPTURE_ACCESSIBILITY -> {
                // WindowManager removal is asynchronous; one short frame delay keeps Mofei out.
                mainHandler.postDelayed(::requestAccessibilityScreenshot, SCREENSHOT_SETTLE_MILLIS)
            }
            MofeiOverlayCaptureStart.OPEN_ACCESSIBILITY_SETUP -> openAccessibilitySetup()
            MofeiOverlayCaptureStart.SHOW_UNSUPPORTED -> {
                showCaptureFailure("当前 Android 版本不支持墨斐直接截屏")
            }
        }
    }

    private fun requestAccessibilityScreenshot() {
        val started = MofeiScreenshotAccessibilityService.requestScreenshot { result ->
            // Accessibility callbacks run on the capture executor; WindowManager and Activities
            // must be touched from the service main thread.
            mainHandler.post {
                when (MofeiOverlayCapturePlan.finish(result is AccessibilityCaptureResult.Success)) {
                    MofeiOverlayCaptureFinish.OPEN_PREVIEW ->
                        openScreenshotPreview((result as AccessibilityCaptureResult.Success).uri)
                    MofeiOverlayCaptureFinish.RESTORE_AND_REPORT_ERROR ->
                        showCaptureFailure((result as AccessibilityCaptureResult.Failure).message)
                }
            }
        }
        if (!started) openAccessibilitySetup()
    }

    private fun openScreenshotPreview(uri: Uri) {
        val intent = ScreenshotPreviewActivity.captureIntent(
            context = this,
            uri = uri,
            restoreOverlayAfterCapture = true,
        ).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
        )
        runCatching {
            PendingIntent.getActivity(
                this,
                SCREENSHOT_PREVIEW_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ).send()
        }.onFailure {
            runCatching { contentResolver.delete(uri, null, null) }
            showCaptureFailure("无法打开截屏识别预览，请重试")
        }
    }

    private fun openAccessibilitySetup() {
        runCatching {
            PendingIntent.getActivity(
                this,
                ACCESSIBILITY_SETUP_REQUEST_CODE,
                MofeiAccessibilitySetupActivity.intent(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ).send()
        }.onFailure {
            showCaptureFailure("无法打开一键截屏设置，请进入系统无障碍设置")
        }
    }

    private fun showCaptureFailure(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        showActionFallback(message)
        if (canShowOverlay()) showCollapsedOverlay()
    }

    /** Rebuilds the WindowManager-hosted composition so OEM lifecycle quirks cannot hide feedback. */
    private fun previewOverlayAction(action: MofeiAction) {
        revealedOverlayAction = action
        mainHandler.post {
            if (displayMode == OverlayDisplayMode.EXPANDED && overlayView != null) updateOverlayView()
        }
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
        MofeiAction.CAPTURE_CURRENT_SCREEN -> "截屏"
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
            .setContentText("轻点侧边墨斐展开行动中心")
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
                description = "墨斐在系统侧边半隐藏显示时的常驻通知"
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
        private var acceptingGesture = false
        private val longPress = Runnable {
            if (!dragging) {
                longPressTriggered = true
                if (controller.commandForLongPress() == OverlayCommand.ShowControls) showControls()
            }
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                acceptingGesture = controller.shouldCaptureRootGesture(
                    mode = displayMode,
                    dockSide = placement.dockSide,
                    localX = event.x,
                    localY = event.y,
                    windowWidthPx = view.width,
                    windowHeightPx = view.height,
                    density = resources.displayMetrics.density,
                )
                if (!acceptingGesture) return false
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
                if (!acceptingGesture) return false
                val deltaX = event.rawX - downX
                val deltaY = event.rawY - downY
                if (abs(deltaX) > TOUCH_SLOP_PX || abs(deltaY) > TOUCH_SLOP_PX) {
                    dragging = true
                    view.removeCallbacks(longPress)
                    currentLayoutParams?.let { params ->
                        params.x = startWindowX + deltaX.toInt()
                        params.y = (startWindowY + deltaY.toInt()).coerceIn(0, screenMetrics().height)
                        overlayView?.let { root -> windowManager.updateViewLayout(root, params) }
                    }
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!acceptingGesture) return false
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
                    // performClick preserves accessibility behavior in the resting state. The
                    // expanded root has no click listener, so tapping Mofei collapses explicitly.
                    if (overlayView?.performClick() != true) handleTap()
                }
                acceptingGesture = false
                true
            }
                else -> true
            }
        }
    }

    /**
     * A normal FrameLayout lets Compose consume the pointer stream before a parent drag listener
     * sees movement. Claiming the stream at dispatch time fixes dragging without covering the
     * expanded action buttons with one large invisible touch target.
     */
    private inner class OverlayGestureFrameLayout : FrameLayout(this@MascotOverlayService) {
        private var captureGesture = false

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    captureGesture = controller.shouldCaptureRootGesture(
                        mode = displayMode,
                        dockSide = placement.dockSide,
                        localX = event.x,
                        localY = event.y,
                        windowWidthPx = width,
                        windowHeightPx = height,
                        density = resources.displayMetrics.density,
                    )
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val captured = captureGesture
                    captureGesture = false
                    return captured
                }
            }
            return captureGesture
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
        private const val SCREENSHOT_SETTLE_MILLIS = 180L
        private const val SCREENSHOT_PREVIEW_REQUEST_CODE = 4090
        private const val ACCESSIBILITY_SETUP_REQUEST_CODE = 4091
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
