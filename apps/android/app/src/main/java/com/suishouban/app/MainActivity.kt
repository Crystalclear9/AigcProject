package com.suishouban.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suishouban.app.reminder.ScreenshotMonitorService
import com.suishouban.app.mascot.FloatingMascot
import com.suishouban.app.mascot.MascotOverlayService
import com.suishouban.app.mascot.OverlayDockSide
import com.suishouban.app.mascot.action.MofeiPermissionState
import com.suishouban.app.notification.InstalledAppInfo
import com.suishouban.app.notification.InstalledAppRepository
import com.suishouban.app.ui.components.GradientScreen
import com.suishouban.app.ui.screens.CalendarScreen
import com.suishouban.app.ui.screens.CardsScreen
import com.suishouban.app.ui.screens.HomeScreen
import com.suishouban.app.ui.screens.ImportScreen
import com.suishouban.app.ui.screens.PreviewScreen
import com.suishouban.app.ui.screens.SettingsScreen
import com.suishouban.app.ui.theme.SuiShouBanTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

private data class OverlayNavigation(
    val actionCardId: String? = null,
    val requestId: Long = 0L,
)

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private val overlayNavigation = MutableStateFlow(OverlayNavigation())
    private val permissionRevision = MutableStateFlow(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openedScreenshotPrompt = openProcessScreenshotIntent(intent)
        val sharedImageUri = if (openedScreenshotPrompt) null else extractSharedImage(intent)
        handleOverlayNavigationIntent(intent)

        setContent {
            SuiShouBanTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val mascotState by viewModel.mascotState.collectAsStateWithLifecycle()
                val requestedOverlayNavigation by overlayNavigation.collectAsStateWithLifecycle()
                val currentPermissionRevision by permissionRevision.collectAsStateWithLifecycle()
                val notificationAccessGranted = remember(currentPermissionRevision) {
                    MofeiPermissionState.notificationAccessGranted(this@MainActivity)
                }
                var notificationApps by remember { mutableStateOf(emptyList<InstalledAppInfo>()) }
                LaunchedEffect(Unit) {
                    // PackageManager enumeration can touch disk; keep it off the Compose thread.
                    notificationApps = withContext(Dispatchers.IO) {
                        InstalledAppRepository(applicationContext).listSelectableApps()
                    }
                }
                // A monotonically increasing counter drives the pet's one-shot celebration burst.
                var celebrationSignal by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) {
                    viewModel.mascotInteractions.collect { celebrationSignal++ }
                }
                var current by rememberSaveable { mutableStateOf(Screen.Home.route) }
                var pendingCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
                val snackbarHostState = remember { SnackbarHostState() }
                val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    if (uri != null) {
                        viewModel.analyzeImage(uri) { hasCards ->
                            if (hasCards) current = Screen.Preview.route
                        }
                    }
                }
                val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
                    val uri = pendingCameraUri
                    if (captured && uri != null) {
                        viewModel.analyzeImage(uri) { hasCards ->
                            if (hasCards) current = Screen.Preview.route
                        }
                    }
                    pendingCameraUri = null
                }
                val overlayPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) {
                    if (Settings.canDrawOverlays(this@MainActivity)) {
                        // The overlay will appear after the app backgrounds; it remains hidden
                        // while this activity owns the foreground.
                        viewModel.updateSettings(state.settings.copy(mascotOverlayEnabled = true))
                    } else {
                        viewModel.updateSettings(state.settings.copy(mascotOverlayEnabled = false))
                        Toast.makeText(this@MainActivity, "未授予悬浮窗权限，墨斐仅在应用内显示", Toast.LENGTH_SHORT).show()
                    }
                }
                fun launchCameraCapture() {
                    val uri = createCameraImageUri()
                    // TakePicture 需要预先给相机一个可写入的 content URI。
                    pendingCameraUri = uri
                    cameraLauncher.launch(uri)
                }

                LaunchedEffect(sharedImageUri) {
                    if (sharedImageUri != null) {
                        viewModel.analyzeImage(sharedImageUri, notifyWhenEmpty = false) { hasCards ->
                            if (hasCards) current = Screen.Preview.route
                        }
                    }
                }
                LaunchedEffect(state.settings.autoDetectScreenshots) {
                    val serviceIntent = Intent(this@MainActivity, ScreenshotMonitorService::class.java)
                    if (state.settings.autoDetectScreenshots) {
                        requestScreenshotMonitorPermissions()
                        ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                    } else {
                        stopService(serviceIntent)
                    }
                }
                LaunchedEffect(state.error) {
                    val error = state.error
                    if (error != null) {
                        snackbarHostState.showSnackbar(error)
                        viewModel.clearError()
                    }
                }
                LaunchedEffect(requestedOverlayNavigation.requestId) {
                    requestedOverlayNavigation.actionCardId?.let { current = Screen.Cards.route }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar {
                            bottomScreens.forEach { screen ->
                                NavigationBarItem(
                                    selected = current == screen.route,
                                    onClick = { current = screen.route },
                                    icon = { Icon(screen.icon, contentDescription = screen.label) },
                                    label = { Text(screen.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize()) {
                        GradientScreen(padding) {
                            when (current) {
                            Screen.Import.route -> ImportScreen(
                                state = state,
                                onPickImage = { uri ->
                                    viewModel.analyzeImage(uri) { hasCards ->
                                        if (hasCards) current = Screen.Preview.route
                                    }
                                },
                                onAnalyzeText = { text ->
                                    viewModel.analyzeText(text) { hasCards ->
                                        if (hasCards) current = Screen.Preview.route
                                    }
                                },
                                onPreview = { current = Screen.Preview.route },
                                mascotState = mascotState,
                            )
                            Screen.Preview.route -> PreviewScreen(
                                state = state,
                                onUpdateDraft = viewModel::updateDraft,
                                onRemoveDraft = viewModel::removeDraft,
                                onConfirm = { viewModel.confirmDrafts { current = Screen.Cards.route } },
                                onManualAdd = viewModel::addManualDraftFromCurrentText,
                                onImport = { current = Screen.Import.route },
                            )
                            Screen.Cards.route -> CardsScreen(
                                state = state,
                                onUpdate = viewModel::updateCard,
                                onComplete = viewModel::completeCard,
                                onArchive = viewModel::archiveCard,
                                onImport = { current = Screen.Import.route },
                                highlightCardId = requestedOverlayNavigation.actionCardId,
                            )
                            Screen.Calendar.route -> CalendarScreen(
                                state = state,
                                onComplete = viewModel::completeCard,
                            )
                            Screen.Settings.route -> SettingsScreen(
                                state = state,
                                onUpdate = viewModel::updateSettings,
                                onSync = viewModel::syncFromServer,
                                onTestConnection = viewModel::testConnection,
                                mascotState = mascotState,
                                notificationAccessGranted = notificationAccessGranted,
                                notificationApps = notificationApps,
                                onOpenNotificationAccessSettings = {
                                    runCatching {
                                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                    }.onFailure {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "当前系统无法打开通知访问设置",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                                onMascotOverlayToggle = { enabled ->
                                    if (!enabled) {
                                        viewModel.updateSettings(state.settings.copy(mascotOverlayEnabled = false))
                                        startService(
                                            Intent(this@MainActivity, MascotOverlayService::class.java)
                                                .setAction(MascotOverlayService.ACTION_STOP),
                                        )
                                    } else if (Settings.canDrawOverlays(this@MainActivity)) {
                                        viewModel.updateSettings(state.settings.copy(mascotOverlayEnabled = true))
                                    } else {
                                        // This user gesture is the only path that opens Android's
                                        // special overlay permission screen.
                                        overlayPermissionLauncher.launch(
                                            MascotOverlayService.overlayPermissionIntent(this@MainActivity),
                                        )
                                    }
                                },
                            )
                            else -> HomeScreen(
                                state = state,
                                onImportFromGallery = { galleryLauncher.launch("image/*") },
                                onImportFromCamera = { launchCameraCapture() },
                                onCards = { current = Screen.Cards.route },
                                onComplete = viewModel::completeCard,
                            )
                        }
                        }
                        // Resident in-app pet: always visible (no permission), above content and below
                        // the navigation bar. Distinct from the simpler system-edge capsule overlay.
                        if (state.settings.mascotInAppEnabled) {
                            FloatingMascot(
                                state = mascotState,
                                dockSide = if (state.settings.mascotDockSide == "left") {
                                    OverlayDockSide.LEFT
                                } else {
                                    OverlayDockSide.RIGHT
                                },
                                verticalFraction = state.settings.mascotVerticalFraction,
                                reduceMotion = state.settings.reduceMascotMotion,
                                completionSignal = celebrationSignal,
                                onOpenCurrentAction = { cardId ->
                                    overlayNavigation.value = OverlayNavigation(
                                        actionCardId = cardId,
                                        requestId = System.currentTimeMillis(),
                                    )
                                    current = Screen.Cards.route
                                },
                                onOpenSettings = { current = Screen.Settings.route },
                                onDismissForNow = {
                                    viewModel.updateSettings(state.settings.copy(mascotInAppEnabled = false))
                                },
                                onPlacementChange = { side, fraction ->
                                    viewModel.updateSettings(
                                        state.settings.copy(
                                            mascotDockSide = if (side == OverlayDockSide.LEFT) "left" else "right",
                                            mascotVerticalFraction = fraction,
                                        ),
                                    )
                                },
                                modifier = Modifier.padding(padding),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Special-access screens do not return a permission result; refresh from system state.
        permissionRevision.value = System.currentTimeMillis()
        // The app has its own inline companion; do not leave an accessibility-obscuring system
        // window above active forms or lists while the activity is foregrounded.
        MascotOverlayService.dismissForForeground(this)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            MascotOverlayService.restoreAfterAppBackground(this, viewModel.mascotState.value)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOverlayNavigationIntent(intent)
        openProcessScreenshotIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
    }

    private fun openProcessScreenshotIntent(source: Intent?): Boolean {
        if (source?.action != ScreenshotMonitorService.ACTION_PROCESS_SCREENSHOT) return false
        if (!ScreenshotMonitorService.isTrustedPendingPreview(this, source)) return false
        val previewIntent = Intent(this, ScreenshotPreviewActivity::class.java).apply {
            action = ScreenshotMonitorService.ACTION_PROCESS_SCREENSHOT
            data = source.data
            putExtras(source)
            source.getStringExtra(EXTRA_OCR_TEXT_BASE64)
                ?.let(::decodeUtf8Base64)
                ?.takeIf { it.isNotBlank() }
                ?.let { putExtra(ScreenshotPreviewActivity.EXTRA_OCR_TEXT, it) }
        }
        startActivity(previewIntent)
        return true
    }

    private fun decodeUtf8Base64(value: String): String? {
        return runCatching {
            val padded = value.padEnd(value.length + (4 - value.length % 4) % 4, '=')
            String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        }.recoverCatching {
            String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun requestScreenshotMonitorPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1001)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1001) return
        val denied = permissions.zip(grantResults.toTypedArray())
            .filter { (_, result) -> result != PackageManager.PERMISSION_GRANTED }
            .map { (permission, _) -> permission }
        if (denied.any { it == Manifest.permission.POST_NOTIFICATIONS }) {
            Toast.makeText(this, "通知权限未开启，截图建议和截止提醒将不会弹出", Toast.LENGTH_LONG).show()
        }
        if (denied.any { it == Manifest.permission.READ_MEDIA_IMAGES || it == Manifest.permission.READ_EXTERNAL_STORAGE }) {
            Toast.makeText(this, "图片权限未开启，截图监听和相册导入可能不可用", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun extractSharedImage(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    private fun createCameraImageUri(): Uri {
        val imageDir = File(cacheDir, "camera")
        imageDir.mkdirs()
        val imageFile = File.createTempFile("capture_", ".jpg", imageDir)
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", imageFile)
    }

    /** Overlay navigation is intentionally constrained to the existing Cards route and a card ID. */
    private fun handleOverlayNavigationIntent(source: Intent?) {
        if (source?.action != MascotOverlayService.ACTION_OPEN_CURRENT) return
        overlayNavigation.value = OverlayNavigation(
            actionCardId = source.getStringExtra(MascotOverlayService.EXTRA_ACTION_CARD_ID),
            requestId = System.currentTimeMillis(),
        )
    }
}

private const val EXTRA_OCR_TEXT_BASE64 = "com.suishouban.app.extra.OCR_TEXT_BASE64"

private sealed class Screen(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    data object Home : Screen("home", "今日", Icons.Outlined.Dashboard)
    data object Import : Screen("import", "导入", Icons.Outlined.PhotoCamera)
    data object Cards : Screen("cards", "卡片", Icons.Outlined.TaskAlt)
    data object Calendar : Screen("calendar", "日历", Icons.Outlined.CalendarMonth)
    data object Settings : Screen("settings", "设置", Icons.Outlined.Settings)
    data object Preview : Screen("preview", "预览", Icons.Outlined.TaskAlt)
}

private val bottomScreens = listOf(
    Screen.Home,
    Screen.Import,
    Screen.Cards,
    Screen.Calendar,
    Screen.Settings,
)
