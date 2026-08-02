package com.suishouban.app.data.model

enum class AiConnectionMode(val value: String) {
    LOCAL("local"),
    WORKFLOW_GATEWAY("workflow_gateway"),
    DIRECT_API("direct_api");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.value == value } ?: LOCAL
    }
}

data class ProviderProfile(
    val chatUrl: String = "",
    val ocrUrl: String = "",
    val modelName: String = "Doubao-Seed-2.0-mini",
    val businessId: String = "1990173156ceb8a09eee80c293135279",
    val allowInsecureVivoOcr: Boolean = false,
)

data class ProviderCapabilityStatus(
    val modelAuthenticated: Boolean = false,
    val ocrAuthenticated: Boolean = false,
    val schemaSupported: Boolean = false,
    val message: String = "未检测",
)

enum class OcrEnhancementPolicy(val value: String) {
    LOCAL_ONLY("local_only"),
    LOW_QUALITY("low_quality"),
    ALWAYS_COMPARE("always_compare");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.value == value } ?: LOW_QUALITY
    }
}

enum class WorkflowDepthPolicy(val value: String) {
    FAST("fast"), BALANCED("balanced"), DEEP("deep");
    companion object {
        fun from(value: String?) = entries.firstOrNull { it.value == value } ?: BALANCED
    }
}

enum class ReminderPreset(val value: String) {
    LIGHT("light"), STANDARD("standard"), MULTI_STAGE("multi_stage");
    companion object {
        fun from(value: String?) = entries.firstOrNull { it.value == value } ?: STANDARD
    }
}

enum class AutoReactPolicy(val value: String) {
    OFF("off"), LOW_CONFIDENCE("low_confidence"), COMPLEX_TASKS("complex_tasks");
    companion object {
        fun from(value: String?) = entries.firstOrNull { it.value == value } ?: LOW_CONFIDENCE
    }
}

data class ImportSourcePreferences(
    val screenshots: Boolean = true,
    val galleryImages: Boolean = true,
    val text: Boolean = true,
    val documents: Boolean = true,
)
