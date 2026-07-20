package com.suishouban.app.domain.screenshot

import android.content.Context
import java.security.MessageDigest

enum class ScreenshotCaptureSource { MEDIA_STORE, MEDIA_PROJECTION }

data class ScreenshotFingerprintState(
    val lastHash: String? = null,
    val lastAt: Long = 0L,
    val lastSource: ScreenshotCaptureSource? = null,
    val ignoredHash: String? = null,
    val ignoredAt: Long = 0L,
    val windowStart: Long = 0L,
    val windowCount: Int = 0,
    val lastImageHash: String? = null,
    val lastImageAt: Long = 0L,
    val lastImageSource: ScreenshotCaptureSource? = null,
)

interface ScreenshotFingerprintPersistence {
    fun load(): ScreenshotFingerprintState
    fun save(state: ScreenshotFingerprintState)
}

/** Shared content-level dedupe for MediaStore screenshots and active screen capture. */
class ScreenshotFingerprintStore(
    private val persistence: ScreenshotFingerprintPersistence,
) {
    fun canPrompt(text: String, source: ScreenshotCaptureSource, now: Long): Boolean = synchronized(LOCK) {
        // Source is deliberately diagnostic only: the same pixels must dedupe across both routes.
        source.name
        val hash = contentHash(text)
        val state = persistence.load()
        if (state.lastHash == hash && now - state.lastAt < DUPLICATE_COOLDOWN_MS) return@synchronized false
        if (state.ignoredHash == hash && now - state.ignoredAt < IGNORED_COOLDOWN_MS) return@synchronized false
        if (now - state.windowStart < RATE_WINDOW_MS && state.windowCount >= MAX_PROMPTS_PER_WINDOW) return@synchronized false
        true
    }

    fun recordPrompt(text: String, source: ScreenshotCaptureSource, now: Long) {
        recordPromptHash(contentHash(text), source, now)
    }

    /** Records a hash already computed for a pending prompt, avoiding divergent normalization. */
    fun recordPromptHash(contentHash: String, source: ScreenshotCaptureSource, now: Long) = synchronized(LOCK) {
        val current = persistence.load()
        val expiredWindow = now - current.windowStart >= RATE_WINDOW_MS
        persistence.save(
            current.copy(
                lastHash = contentHash,
                lastAt = now,
                lastSource = source,
                windowStart = if (expiredWindow) now else current.windowStart,
                windowCount = if (expiredWindow) 1 else current.windowCount + 1,
            ),
        )
    }

    fun markIgnored(contentHash: String, now: Long) = synchronized(LOCK) {
        persistence.save(persistence.load().copy(ignoredHash = contentHash, ignoredAt = now))
    }

    /** Atomically claims a frame before OCR so concurrent Android capture routes cannot both process it. */
    fun checkAndRecordImage(
        imageHash: String,
        source: ScreenshotCaptureSource,
        now: Long,
    ): Boolean = synchronized(LOCK) {
        val state = persistence.load()
        if (state.lastImageHash == imageHash && now - state.lastImageAt < DUPLICATE_COOLDOWN_MS) {
            return@synchronized false
        }
        persistence.save(
            state.copy(
                lastImageHash = imageHash,
                lastImageAt = now,
                lastImageSource = source,
            ),
        )
        true
    }

    fun contentHash(text: String): String {
        val normalized = text.lowercase().replace(Regex("\\s+"), "")
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val LOCK = Any()
        const val DUPLICATE_COOLDOWN_MS = 10 * 60 * 1000L
        const val IGNORED_COOLDOWN_MS = 60 * 60 * 1000L
        const val RATE_WINDOW_MS = 10 * 60 * 1000L
        const val MAX_PROMPTS_PER_WINDOW = 2

        fun sharedPreferences(context: Context): ScreenshotFingerprintStore =
            ScreenshotFingerprintStore(SharedPreferencesScreenshotPersistence(context))
    }
}

private class SharedPreferencesScreenshotPersistence(context: Context) : ScreenshotFingerprintPersistence {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): ScreenshotFingerprintState = ScreenshotFingerprintState(
        lastHash = preferences.getString(KEY_LAST_HASH, null),
        lastAt = preferences.getLong(KEY_LAST_AT, 0L),
        lastSource = preferences.getString(KEY_LAST_SOURCE, null)?.let {
            runCatching { ScreenshotCaptureSource.valueOf(it) }.getOrNull()
        },
        ignoredHash = preferences.getString(KEY_IGNORED_HASH, null),
        ignoredAt = preferences.getLong(KEY_IGNORED_AT, 0L),
        windowStart = preferences.getLong(KEY_WINDOW_START, 0L),
        windowCount = preferences.getInt(KEY_WINDOW_COUNT, 0),
        lastImageHash = preferences.getString(KEY_LAST_IMAGE_HASH, null),
        lastImageAt = preferences.getLong(KEY_LAST_IMAGE_AT, 0L),
        lastImageSource = preferences.getString(KEY_LAST_IMAGE_SOURCE, null)?.let {
            runCatching { ScreenshotCaptureSource.valueOf(it) }.getOrNull()
        },
    )

    override fun save(state: ScreenshotFingerprintState) {
        preferences.edit()
            .putString(KEY_LAST_HASH, state.lastHash)
            .putLong(KEY_LAST_AT, state.lastAt)
            .putString(KEY_LAST_SOURCE, state.lastSource?.name)
            .putString(KEY_IGNORED_HASH, state.ignoredHash)
            .putLong(KEY_IGNORED_AT, state.ignoredAt)
            .putLong(KEY_WINDOW_START, state.windowStart)
            .putInt(KEY_WINDOW_COUNT, state.windowCount)
            .putString(KEY_LAST_IMAGE_HASH, state.lastImageHash)
            .putLong(KEY_LAST_IMAGE_AT, state.lastImageAt)
            .putString(KEY_LAST_IMAGE_SOURCE, state.lastImageSource?.name)
            // Cross-component capture can follow immediately, so persistence must be synchronous.
            .commit()
    }

    private companion object {
        const val PREFS_NAME = "screenshot_prompt_policy"
        const val KEY_LAST_HASH = "last_hash"
        const val KEY_LAST_AT = "last_at"
        const val KEY_LAST_SOURCE = "last_source"
        const val KEY_IGNORED_HASH = "ignored_hash"
        const val KEY_IGNORED_AT = "ignored_at"
        const val KEY_WINDOW_START = "window_start"
        const val KEY_WINDOW_COUNT = "window_count"
        const val KEY_LAST_IMAGE_HASH = "last_image_hash"
        const val KEY_LAST_IMAGE_AT = "last_image_at"
        const val KEY_LAST_IMAGE_SOURCE = "last_image_source"
    }
}
