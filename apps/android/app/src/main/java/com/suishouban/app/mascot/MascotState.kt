package com.suishouban.app.mascot

/** The visual moods supported by the mascot asset catalog. */
enum class MascotMood {
    IDLE,
    FOCUS,
    CONFIRM,
    REMINDER,
    DUE_SOON,
    URGENT,
    COMPLETE,
    REST,
    UNAVAILABLE,
}

/** Semantic colors keep state resolution independent from Compose color values. */
enum class MascotColorRole {
    DEFAULT,
    FOCUS,
    CONFIRM,
    REMINDER,
    WARNING,
    URGENT,
    SUCCESS,
    REST,
    MUTED,
}

enum class MascotAnimationHint {
    BREATHE,
    SCAN,
    PEEK,
    NUDGE,
    WARNING_PULSE,
    ALERT_PULSE,
    CELEBRATE,
    SETTLE,
    DIM,
}

/** A UI-ready snapshot that can be shared by the overlay and in-app companion. */
data class MascotState(
    val mood: MascotMood,
    val actionCardId: String? = null,
    val userMessage: String,
    val colorRole: MascotColorRole,
    val animationHint: MascotAnimationHint,
)
