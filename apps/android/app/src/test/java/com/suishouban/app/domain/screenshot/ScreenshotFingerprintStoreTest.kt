package com.suishouban.app.domain.screenshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    @Test
    fun sameImageIsDedupedAcrossActiveAndSystemCapture() {
        val persistence = MemoryPersistence()
        val store = ScreenshotFingerprintStore(persistence)
        val now = 4_000_000L

        assertTrue(store.checkAndRecordImage("00ff", ScreenshotCaptureSource.MEDIA_PROJECTION, now))
        assertFalse(store.checkAndRecordImage("00ff", ScreenshotCaptureSource.MEDIA_STORE, now + 1_000L))
        assertTrue(store.checkAndRecordImage("0fff", ScreenshotCaptureSource.MEDIA_STORE, now + 1_000L))
        assertTrue(store.checkAndRecordImage("00ff", ScreenshotCaptureSource.MEDIA_STORE, now + 10 * 60 * 1000L))
    }

    @Test
    fun concurrentSourcesCannotBothClaimTheSameImage() {
        val persistence = MemoryPersistence()
        val first = ScreenshotFingerprintStore(persistence)
        val second = ScreenshotFingerprintStore(persistence)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(first to ScreenshotCaptureSource.MEDIA_PROJECTION, second to ScreenshotCaptureSource.MEDIA_STORE)
                .map { (store, source) ->
                    executor.submit<Boolean> {
                        start.await()
                        store.checkAndRecordImage("same-frame", source, 5_000_000L)
                    }
                }
            start.countDown()

            assertEquals(1, results.count { it.get(2, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun perceptualFingerprintIsStableForEquivalentPixelBuffers() {
        val pixels = intArrayOf(
            0xff000000.toInt(), 0xffffffff.toInt(),
            0xff000000.toInt(), 0xffffffff.toInt(),
        )

        assertEquals(
            ScreenshotImageFingerprint.fromArgb(pixels, 2, 2),
            ScreenshotImageFingerprint.fromArgb(pixels.copyOf(), 2, 2),
        )
    }
}
