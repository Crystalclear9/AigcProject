package com.suishouban.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
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
import com.suishouban.app.ui.components.GradientScreen
import com.suishouban.app.ui.screens.CalendarScreen
import com.suishouban.app.ui.screens.CardsScreen
import com.suishouban.app.ui.screens.HomeScreen
import com.suishouban.app.ui.screens.ImportScreen
import com.suishouban.app.ui.screens.PreviewScreen
import com.suishouban.app.ui.screens.SettingsScreen
import com.suishouban.app.ui.theme.SuiShouBanTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private var skipPendingPromptOnce: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        val openedScreenshotPrompt = openProcessScreenshotIntent(intent)
        val sharedImageUri = if (openedScreenshotPrompt) null else extractSharedImage(intent)

        setContent {
            SuiShouBanTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
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
                            )
                            Screen.Preview.route -> PreviewScreen(
                                state = state,
                                onUpdateDraft = viewModel::updateDraft,
                                onRemoveDraft = viewModel::removeDraft,
                                onConfirm = { viewModel.confirmDrafts { current = Screen.Cards.route } },
                                onImport = { current = Screen.Import.route },
                            )
                            Screen.Cards.route -> CardsScreen(
                                state = state,
                                onUpdate = viewModel::updateCard,
                                onComplete = viewModel::completeCard,
                                onArchive = viewModel::archiveCard,
                                onImport = { current = Screen.Import.route },
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
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (skipPendingPromptOnce) {
            skipPendingPromptOnce = false
            return
        }
        openPendingScreenshotPrompt()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openProcessScreenshotIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) openPendingScreenshotPrompt()
    }

    private fun openPendingScreenshotPrompt() {
        ScreenshotMonitorService.consumePendingPreviewIntent(this)?.let { intent ->
            startActivity(intent)
        }
    }

    private fun openProcessScreenshotIntent(source: Intent?): Boolean {
        if (source?.action != ScreenshotMonitorService.ACTION_PROCESS_SCREENSHOT) return false
        skipPendingPromptOnce = true
        ScreenshotMonitorService.clearPendingPreview(this)
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

    private fun requestRuntimePermissions() {
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
