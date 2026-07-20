package com.suishouban.app.mascot.action

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class MofeiRecoveryPolicyTest {
    @Test
    fun cancellationDoesNotRequestPermissionAgain() {
        val decision = MofeiRecoveryPolicy.forFailure(MofeiFailure.PROJECTION_CANCELLED)
        assertEquals("已取消当前屏幕识别", decision.message)
        assertFalse(decision.retryPermissionAutomatically)
        assertTrue(decision.clearBusyAction)
    }

    @Test
    fun protectedContentAndRevokedNotificationAccessSealOnlyAffectedCapability() {
        val protected = MofeiRecoveryPolicy.forFailure(MofeiFailure.PROTECTED_CONTENT)
        assertEquals(MofeiAction.CAPTURE_CURRENT_SCREEN, protected.sealedAction)
        val revoked = MofeiRecoveryPolicy.forFailure(MofeiFailure.NOTIFICATION_ACCESS_REVOKED)
        assertEquals(MofeiAction.REVIEW_NOTIFICATION_DRAFTS, revoked.sealedAction)
    }

    @Test
    fun staleAndExpiredDataAreCleanedWithoutCreatingCards() {
        assertTrue(MofeiRecoveryPolicy.forFailure(MofeiFailure.STALE_BUSY_STATE).clearBusyAction)
        assertTrue(MofeiRecoveryPolicy.forFailure(MofeiFailure.EXPIRED_CANDIDATES).deleteExpiredCandidates)
        assertTrue(MofeiRecoveryPolicy.forFailure(MofeiFailure.STALE_CAPTURE_CACHE).deleteCaptureCache)
    }
}
