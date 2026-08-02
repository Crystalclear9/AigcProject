package com.suishouban.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.suishouban.app.BuildConfig
import com.suishouban.app.data.model.AiConnectionMode
import com.suishouban.app.data.model.AutoReactPolicy
import com.suishouban.app.data.model.ImportSourcePreferences
import com.suishouban.app.data.model.OcrEnhancementPolicy
import com.suishouban.app.data.model.ProviderProfile
import com.suishouban.app.data.model.ReminderPreset
import com.suishouban.app.data.model.WorkflowDepthPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val apiBaseUrl: String = BuildConfig.DEFAULT_API_BASE_URL,
    val autoDetectScreenshots: Boolean = false,
    val privacyMask: Boolean = true,
    val calendarSync: Boolean = false,
    val keepOriginalScreenshot: Boolean = false,
    val preferCloudModel: Boolean = WorkflowUrlPolicy.isAccepted(BuildConfig.DEFAULT_API_BASE_URL),
    val mascotOverlayEnabled: Boolean = false,
    val mascotInAppEnabled: Boolean = true,
    val mascotHiddenUntilMillis: Long = 0L,
    val mascotDockSide: String = DEFAULT_MASCOT_DOCK_SIDE,
    val mascotVerticalFraction: Float = DEFAULT_MASCOT_VERTICAL_FRACTION,
    val reduceMascotMotion: Boolean = false,
    val mofeiNotificationDraftsEnabled: Boolean = false,
    val mofeiNotificationPackageAllowlist: Set<String> = emptySet(),
    val cardRefinementEnabled: Boolean = true,
    val personalizedPlanningEnabled: Boolean = true,
    val profileLearningEnabled: Boolean = false,
    val milestoneRemindersEnabled: Boolean = true,
    val refinementWorkBlocksEnabled: Boolean = true,
    val defaultRefinementGranularity: String = "balanced",
    val onboardingSeen: Boolean = false,
    val aiConnectionMode: AiConnectionMode = AiConnectionMode.LOCAL,
    val providerProfile: ProviderProfile = ProviderProfile(),
    val reminderPreset: ReminderPreset = ReminderPreset.STANDARD,
    val maxSuggestedReminders: Int = 3,
    val importSources: ImportSourcePreferences = ImportSourcePreferences(),
    val ocrEnhancementPolicy: OcrEnhancementPolicy = OcrEnhancementPolicy.LOW_QUALITY,
    val workflowDepthPolicy: WorkflowDepthPolicy = WorkflowDepthPolicy.BALANCED,
    val autoReactPolicy: AutoReactPolicy = AutoReactPolicy.LOW_CONFIDENCE,
    val historyDuplicateCheckEnabled: Boolean = true,
    val teamDependencyCheckEnabled: Boolean = true,
    val webRetrievalEnabled: Boolean = false,
    val originalImageRetentionDays: Int = 0,
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
        val cloudPreference = prefs.getBoolean(
            "prefer_cloud",
            isCloudModeEnabled(apiBaseUrl, true),
        )
        val connectionMode = prefs.getString("ai_connection_mode", null)?.let(AiConnectionMode::from)
            ?: if (isCloudModeEnabled(apiBaseUrl, cloudPreference)) {
                AiConnectionMode.WORKFLOW_GATEWAY
            } else {
                AiConnectionMode.LOCAL
            }
        return AppSettings(
            apiBaseUrl = apiBaseUrl,
            autoDetectScreenshots = prefs.getBoolean("auto_detect", false),
            privacyMask = prefs.getBoolean("privacy_mask", true),
            calendarSync = prefs.getBoolean("calendar_sync", false),
            keepOriginalScreenshot = prefs.getBoolean("keep_screenshot", false),
            preferCloudModel = connectionMode == AiConnectionMode.WORKFLOW_GATEWAY &&
                isCloudModeEnabled(apiBaseUrl, cloudPreference),
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
            mofeiNotificationDraftsEnabled = prefs.getBoolean("mofei_notification_drafts_enabled", false),
            // SharedPreferences may return a mutable, implementation-owned set. Copy it so callers
            // cannot mutate persisted state without going through update().
            mofeiNotificationPackageAllowlist = prefs
                .getStringSet("mofei_notification_package_allowlist", emptySet())
                .orEmpty()
                .toSet(),
            cardRefinementEnabled = prefs.getBoolean("card_refinement_enabled", true),
            personalizedPlanningEnabled = prefs.getBoolean("personalized_planning_enabled", true),
            profileLearningEnabled = prefs.getBoolean("profile_learning_enabled", false),
            milestoneRemindersEnabled = prefs.getBoolean("milestone_reminders_enabled", true),
            refinementWorkBlocksEnabled = prefs.getBoolean("refinement_work_blocks_enabled", true),
            defaultRefinementGranularity = normalizeGranularity(
                prefs.getString("default_refinement_granularity", "balanced") ?: "balanced",
            ),
            onboardingSeen = prefs.getBoolean("onboarding_seen", false),
            aiConnectionMode = connectionMode,
            providerProfile = ProviderProfile(
                chatUrl = prefs.getString("provider_chat_url", "").orEmpty(),
                ocrUrl = prefs.getString("provider_ocr_url", "").orEmpty(),
                modelName = prefs.getString("provider_model_name", "Doubao-Seed-2.0-mini")
                    ?: "Doubao-Seed-2.0-mini",
                businessId = prefs.getString("provider_business_id", null)
                    ?: prefs.getString("provider_app_id", null)?.let { legacy ->
                        if (legacy.startsWith("aigc")) legacy else "aigc$legacy"
                    }
                    ?: "1990173156ceb8a09eee80c293135279",
                allowInsecureVivoOcr = prefs.getBoolean("provider_allow_insecure_vivo_ocr", false),
            ),
            reminderPreset = ReminderPreset.from(prefs.getString("reminder_preset", null)),
            maxSuggestedReminders = prefs.getInt("max_suggested_reminders", 3).coerceIn(1, 5),
            importSources = ImportSourcePreferences(
                screenshots = prefs.getBoolean("import_screenshots", true),
                galleryImages = prefs.getBoolean("import_gallery_images", true),
                text = prefs.getBoolean("import_text", true),
                documents = prefs.getBoolean("import_documents", true),
            ),
            ocrEnhancementPolicy = OcrEnhancementPolicy.from(prefs.getString("ocr_enhancement_policy", null)),
            workflowDepthPolicy = WorkflowDepthPolicy.from(prefs.getString("workflow_depth_policy", null)),
            autoReactPolicy = AutoReactPolicy.from(prefs.getString("auto_react_policy", null)),
            historyDuplicateCheckEnabled = prefs.getBoolean("history_duplicate_check", true),
            teamDependencyCheckEnabled = prefs.getBoolean("team_dependency_check", true),
            webRetrievalEnabled = prefs.getBoolean("web_retrieval", false),
            originalImageRetentionDays = prefs.getInt("original_image_retention_days", 0)
                .takeIf { it in setOf(0, 1, 7) } ?: 0,
        )
    }

    fun update(settings: AppSettings) {
        val normalizedApiUrl = settings.apiBaseUrl.trim()
        val normalizedSettings = settings.copy(
            apiBaseUrl = normalizedApiUrl,
            preferCloudModel = settings.aiConnectionMode == AiConnectionMode.WORKFLOW_GATEWAY &&
                isCloudModeEnabled(normalizedApiUrl, settings.preferCloudModel),
            mascotDockSide = normalizeMascotDockSide(settings.mascotDockSide),
            mascotVerticalFraction = normalizeMascotVerticalFraction(settings.mascotVerticalFraction),
            mofeiNotificationPackageAllowlist = settings.mofeiNotificationPackageAllowlist.toSet(),
            defaultRefinementGranularity = normalizeGranularity(settings.defaultRefinementGranularity),
            maxSuggestedReminders = settings.maxSuggestedReminders.coerceIn(1, 5),
            originalImageRetentionDays = settings.originalImageRetentionDays
                .takeIf { it in setOf(0, 1, 7) } ?: 0,
            autoDetectScreenshots = settings.autoDetectScreenshots && settings.importSources.screenshots,
            keepOriginalScreenshot = settings.originalImageRetentionDays > 0,
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
            .putBoolean("mofei_notification_drafts_enabled", normalizedSettings.mofeiNotificationDraftsEnabled)
            .putStringSet(
                "mofei_notification_package_allowlist",
                normalizedSettings.mofeiNotificationPackageAllowlist.toSet(),
            )
            .putBoolean("card_refinement_enabled", normalizedSettings.cardRefinementEnabled)
            .putBoolean("personalized_planning_enabled", normalizedSettings.personalizedPlanningEnabled)
            .putBoolean("profile_learning_enabled", normalizedSettings.profileLearningEnabled)
            .putBoolean("milestone_reminders_enabled", normalizedSettings.milestoneRemindersEnabled)
            .putBoolean("refinement_work_blocks_enabled", normalizedSettings.refinementWorkBlocksEnabled)
            .putString(
                "default_refinement_granularity",
                normalizeGranularity(normalizedSettings.defaultRefinementGranularity),
            )
            .putBoolean("onboarding_seen", normalizedSettings.onboardingSeen)
            .putString("ai_connection_mode", normalizedSettings.aiConnectionMode.value)
            .putString("provider_chat_url", normalizedSettings.providerProfile.chatUrl.trim())
            .putString("provider_ocr_url", normalizedSettings.providerProfile.ocrUrl.trim())
            .putString("provider_model_name", normalizedSettings.providerProfile.modelName.trim())
            .putString("provider_business_id", normalizedSettings.providerProfile.businessId.trim())
            .remove("provider_app_id")
            .putBoolean(
                "provider_allow_insecure_vivo_ocr",
                normalizedSettings.providerProfile.allowInsecureVivoOcr,
            )
            .putString("reminder_preset", normalizedSettings.reminderPreset.value)
            .putInt("max_suggested_reminders", normalizedSettings.maxSuggestedReminders)
            .putBoolean("import_screenshots", normalizedSettings.importSources.screenshots)
            .putBoolean("import_gallery_images", normalizedSettings.importSources.galleryImages)
            .putBoolean("import_text", normalizedSettings.importSources.text)
            .putBoolean("import_documents", normalizedSettings.importSources.documents)
            .putString("ocr_enhancement_policy", normalizedSettings.ocrEnhancementPolicy.value)
            .putString("workflow_depth_policy", normalizedSettings.workflowDepthPolicy.value)
            .putString("auto_react_policy", normalizedSettings.autoReactPolicy.value)
            .putBoolean("history_duplicate_check", normalizedSettings.historyDuplicateCheckEnabled)
            .putBoolean("team_dependency_check", normalizedSettings.teamDependencyCheckEnabled)
            .putBoolean("web_retrieval", normalizedSettings.webRetrievalEnabled)
            .putInt("original_image_retention_days", normalizedSettings.originalImageRetentionDays)
            .apply()
        _settings.value = normalizedSettings
    }

    private fun normalizeMascotDockSide(value: String): String =
        if (value == MASCOT_DOCK_SIDE_LEFT || value == DEFAULT_MASCOT_DOCK_SIDE) value else DEFAULT_MASCOT_DOCK_SIDE

    /** Keeps a draggable overlay reachable after stale or invalid preferences are restored. */
    private fun normalizeMascotVerticalFraction(value: Float): Float =
        if (value.isFinite()) value.coerceIn(MASCOT_MIN_VERTICAL_FRACTION, MASCOT_MAX_VERTICAL_FRACTION)
        else DEFAULT_MASCOT_VERTICAL_FRACTION

    private fun normalizeGranularity(value: String): String =
        value.takeIf { it in setOf("concise", "balanced", "detailed") } ?: "balanced"

    private companion object {
        const val SETTINGS_PREFERENCES = "suishouban_settings"
        const val MASCOT_DOCK_SIDE_LEFT = "left"
        const val DEFAULT_MASCOT_DOCK_SIDE = "right"
        const val DEFAULT_MASCOT_VERTICAL_FRACTION = 0.5f
        const val MASCOT_MIN_VERTICAL_FRACTION = 0.1f
        const val MASCOT_MAX_VERTICAL_FRACTION = 0.9f
    }
}

internal fun isCloudModeEnabled(apiBaseUrl: String, preference: Boolean): Boolean =
    preference &&
        apiBaseUrl.isNotBlank() &&
        WorkflowUrlPolicy.isAccepted(apiBaseUrl)

private const val DEFAULT_MASCOT_DOCK_SIDE = "right"
private const val DEFAULT_MASCOT_VERTICAL_FRACTION = 0.5f
