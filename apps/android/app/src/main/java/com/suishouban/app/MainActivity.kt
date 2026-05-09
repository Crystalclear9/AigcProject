package com.suishouban.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        val sharedImageUri = extractSharedImage(intent)

        setContent {
            SuiShouBanTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                var current by rememberSaveable { mutableStateOf(Screen.Home.route) }
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(sharedImageUri) {
                    if (sharedImageUri != null) {
                        viewModel.analyzeImage(sharedImageUri) { current = Screen.Preview.route }
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
                                onPickImage = { uri -> viewModel.analyzeImage(uri) { current = Screen.Preview.route } },
                                onAnalyzeText = { text -> viewModel.analyzeText(text) { current = Screen.Preview.route } },
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
                            )
                            else -> HomeScreen(
                                state = state,
                                onImport = { current = Screen.Import.route },
                                onCards = { current = Screen.Cards.route },
                                onCalendar = { current = Screen.Calendar.route },
                                onComplete = viewModel::completeCard,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.WRITE_CALENDAR)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1001)
        }
    }

    @Suppress("DEPRECATION")
    private fun extractSharedImage(intent: Intent?): Uri? {
        if (intent?.action == ScreenshotMonitorService.ACTION_PROCESS_SCREENSHOT) return intent.data
        if (intent?.action != Intent.ACTION_SEND) return null
        return intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }
}

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
