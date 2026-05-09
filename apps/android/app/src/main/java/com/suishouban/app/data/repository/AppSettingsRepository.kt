package com.suishouban.app.data.repository

import android.content.Context
import com.suishouban.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val apiBaseUrl: String = BuildConfig.DEFAULT_API_BASE_URL,
    val autoDetectScreenshots: Boolean = true,
    val privacyMask: Boolean = true,
    val calendarSync: Boolean = false,
    val keepOriginalScreenshot: Boolean = false,
    val preferCloudModel: Boolean = true,
)

class AppSettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("suishouban_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings

    private fun load(): AppSettings = AppSettings(
        apiBaseUrl = prefs.getString("api_base_url", BuildConfig.DEFAULT_API_BASE_URL) ?: BuildConfig.DEFAULT_API_BASE_URL,
        autoDetectScreenshots = prefs.getBoolean("auto_detect", true),
        privacyMask = prefs.getBoolean("privacy_mask", true),
        calendarSync = prefs.getBoolean("calendar_sync", false),
        keepOriginalScreenshot = prefs.getBoolean("keep_screenshot", false),
        preferCloudModel = prefs.getBoolean("prefer_cloud", true),
    )

    fun update(settings: AppSettings) {
        prefs.edit()
            .putString("api_base_url", settings.apiBaseUrl)
            .putBoolean("auto_detect", settings.autoDetectScreenshots)
            .putBoolean("privacy_mask", settings.privacyMask)
            .putBoolean("calendar_sync", settings.calendarSync)
            .putBoolean("keep_screenshot", settings.keepOriginalScreenshot)
            .putBoolean("prefer_cloud", settings.preferCloudModel)
            .apply()
        _settings.value = settings
    }
}
