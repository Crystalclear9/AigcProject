package com.suishouban.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.suishouban.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val apiBaseUrl: String = BuildConfig.DEFAULT_API_BASE_URL,
    val autoDetectScreenshots: Boolean = false,
    val privacyMask: Boolean = true,
    val calendarSync: Boolean = false,
    val keepOriginalScreenshot: Boolean = false,
    val preferCloudModel: Boolean = BuildConfig.DEFAULT_API_BASE_URL.trim().startsWith("https://"),
    val mascotOverlayEnabled: Boolean = false,
    val mascotInAppEnabled: Boolean = true,
    val mascotHiddenUntilMillis: Long = 0L,
    val mascotDockSide: String = DEFAULT_MASCOT_DOCK_SIDE,
    val mascotVerticalFraction: Float = DEFAULT_MASCOT_VERTICAL_FRACTION,
    val reduceMascotMotion: Boolean = false,
)

class AppSettingsRepository(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE),
    )

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings

    private fun load(): AppSettings {
        val apiBaseUrl = prefs.getString("api_base_url", BuildConfig.DEFAULT_API_BASE_URL)
            ?: BuildConfig.DEFAULT_API_BASE_URL
        return AppSettings(
            apiBaseUrl = apiBaseUrl,
            autoDetectScreenshots = prefs.getBoolean("auto_detect", false),
            privacyMask = prefs.getBoolean("privacy_mask", true),
            calendarSync = prefs.getBoolean("calendar_sync", false),
            keepOriginalScreenshot = prefs.getBoolean("keep_screenshot", false),
            preferCloudModel = prefs.getBoolean("prefer_cloud", apiBaseUrl.trim().startsWith("https://")),
            mascotOverlayEnabled = prefs.getBoolean("mascot_overlay_enabled", false),
            mascotInAppEnabled = prefs.getBoolean("mascot_in_app_enabled", true),
            mascotHiddenUntilMillis = prefs.getLong("mascot_hidden_until_millis", 0L),
            mascotDockSide = normalizeMascotDockSide(
                prefs.getString("mascot_dock_side", DEFAULT_MASCOT_DOCK_SIDE)
                    ?: DEFAULT_MASCOT_DOCK_SIDE,
            ),
            mascotVerticalFraction = normalizeMascotVerticalFraction(
                prefs.getFloat("mascot_vertical_fraction", DEFAULT_MASCOT_VERTICAL_FRACTION),
            ),
            reduceMascotMotion = prefs.getBoolean("reduce_mascot_motion", false),
        )
    }

    fun update(settings: AppSettings) {
        val normalizedSettings = settings.copy(
            mascotDockSide = normalizeMascotDockSide(settings.mascotDockSide),
            mascotVerticalFraction = normalizeMascotVerticalFraction(settings.mascotVerticalFraction),
        )
        prefs.edit()
            .putString("api_base_url", normalizedSettings.apiBaseUrl)
            .putBoolean("auto_detect", normalizedSettings.autoDetectScreenshots)
            .putBoolean("privacy_mask", normalizedSettings.privacyMask)
            .putBoolean("calendar_sync", normalizedSettings.calendarSync)
            .putBoolean("keep_screenshot", normalizedSettings.keepOriginalScreenshot)
            .putBoolean("prefer_cloud", normalizedSettings.preferCloudModel)
            .putBoolean("mascot_overlay_enabled", normalizedSettings.mascotOverlayEnabled)
            .putBoolean("mascot_in_app_enabled", normalizedSettings.mascotInAppEnabled)
            .putLong("mascot_hidden_until_millis", normalizedSettings.mascotHiddenUntilMillis)
            .putString("mascot_dock_side", normalizedSettings.mascotDockSide)
            .putFloat("mascot_vertical_fraction", normalizedSettings.mascotVerticalFraction)
            .putBoolean("reduce_mascot_motion", normalizedSettings.reduceMascotMotion)
            .apply()
        _settings.value = normalizedSettings
    }

    private fun normalizeMascotDockSide(value: String): String =
        if (value == MASCOT_DOCK_SIDE_LEFT || value == DEFAULT_MASCOT_DOCK_SIDE) value else DEFAULT_MASCOT_DOCK_SIDE

    /** Keeps a draggable overlay reachable after stale or invalid preferences are restored. */
    private fun normalizeMascotVerticalFraction(value: Float): Float =
        if (value.isFinite()) value.coerceIn(MASCOT_MIN_VERTICAL_FRACTION, MASCOT_MAX_VERTICAL_FRACTION)
        else DEFAULT_MASCOT_VERTICAL_FRACTION

    private companion object {
        const val SETTINGS_PREFERENCES = "suishouban_settings"
        const val MASCOT_DOCK_SIDE_LEFT = "left"
        const val DEFAULT_MASCOT_DOCK_SIDE = "right"
        const val DEFAULT_MASCOT_VERTICAL_FRACTION = 0.5f
        const val MASCOT_MIN_VERTICAL_FRACTION = 0.1f
        const val MASCOT_MAX_VERTICAL_FRACTION = 0.9f
    }
}

private const val DEFAULT_MASCOT_DOCK_SIDE = "right"
private const val DEFAULT_MASCOT_VERTICAL_FRACTION = 0.5f
