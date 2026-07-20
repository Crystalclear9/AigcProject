package com.suishouban.app.domain.screenshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotFingerprintStoreTest {
    private class MemoryPersistence : ScreenshotFingerprintPersistence {
        var state = ScreenshotFingerprintState()
        override fun load(): ScreenshotFingerprintState = state
        override fun save(state: ScreenshotFingerprintState) {
            this.state = state
        }
    }

    @Test
    fun sameContentIsDedupedAcrossCaptureSources() {
        val persistence = MemoryPersistence()
        val store = ScreenshotFingerprintStore(persistence)
        val now = 1_000_000L

        assertTrue(store.canPrompt(" Meeting  at 10 ", ScreenshotCaptureSource.MEDIA_PROJECTION, now))
        store.recordPrompt(" Meeting  at 10 ", ScreenshotCaptureSource.MEDIA_PROJECTION, now)

        assertFalse(store.canPrompt("meetingat10", ScreenshotCaptureSource.MEDIA_STORE, now + 1_000L))
        assertTrue(store.canPrompt("meetingat10", ScreenshotCaptureSource.MEDIA_STORE, now + 10 * 60 * 1000L))
    }

    @Test
    fun ignoredContentUsesLongerCooldown() {
        val persistence = MemoryPersistence()
        val store = ScreenshotFingerprintStore(persistence)
        val now = 2_000_000L
        store.markIgnored(store.contentHash("pay rent"), now)

        assertFalse(store.canPrompt("pay rent", ScreenshotCaptureSource.MEDIA_STORE, now + 59 * 60 * 1000L))
        assertTrue(store.canPrompt("pay rent", ScreenshotCaptureSource.MEDIA_STORE, now + 60 * 60 * 1000L))
    }

    @Test
    fun rateLimitAllowsOnlyTwoPromptsPerWindowAndThenExpires() {
        val persistence = MemoryPersistence()
        val store = ScreenshotFingerprintStore(persistence)
        val now = 3_000_000L
        store.recordPrompt("one", ScreenshotCaptureSource.MEDIA_STORE, now)
        store.recordPrompt("two", ScreenshotCaptureSource.MEDIA_STORE, now + 1_000L)

        assertFalse(store.canPrompt("three", ScreenshotCaptureSource.MEDIA_STORE, now + 2_000L))
        assertTrue(store.canPrompt("three", ScreenshotCaptureSource.MEDIA_STORE, now + 10 * 60 * 1000L))
    }
}
