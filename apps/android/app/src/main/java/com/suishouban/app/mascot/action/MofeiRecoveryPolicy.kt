package com.suishouban.app.mascot.action

enum class MofeiFailure {
    PROJECTION_CANCELLED,
    PROTECTED_CONTENT,
    NOTIFICATION_ACCESS_REVOKED,
    STALE_BUSY_STATE,
    EXPIRED_CANDIDATES,
    STALE_CAPTURE_CACHE,
}

data class MofeiRecoveryDecision(
    val message: String,
    val sealedAction: MofeiAction? = null,
    val clearBusyAction: Boolean = false,
    val deleteExpiredCandidates: Boolean = false,
    val deleteCaptureCache: Boolean = false,
    val retryPermissionAutomatically: Boolean = false,
)

/** Stable, non-looping recovery behavior shared by Activity, listener, and capture service. */
object MofeiRecoveryPolicy {
    fun forFailure(failure: MofeiFailure): MofeiRecoveryDecision = when (failure) {
        MofeiFailure.PROJECTION_CANCELLED -> MofeiRecoveryDecision(
            message = "已取消当前屏幕识别",
            clearBusyAction = true,
        )
        MofeiFailure.PROTECTED_CONTENT -> MofeiRecoveryDecision(
            message = "该页面禁止截屏，墨斐没有读取到画面",
            sealedAction = MofeiAction.CAPTURE_CURRENT_SCREEN,
            clearBusyAction = true,
        )
        MofeiFailure.NOTIFICATION_ACCESS_REVOKED -> MofeiRecoveryDecision(
            message = "通知读取权限已关闭",
            sealedAction = MofeiAction.REVIEW_NOTIFICATION_DRAFTS,
        )
        MofeiFailure.STALE_BUSY_STATE -> MofeiRecoveryDecision(
            message = "上一次操作已结束，可以重新尝试",
            clearBusyAction = true,
        )
        MofeiFailure.EXPIRED_CANDIDATES -> MofeiRecoveryDecision(
            message = "已清理过期通知草稿",
            deleteExpiredCandidates = true,
        )
        MofeiFailure.STALE_CAPTURE_CACHE -> MofeiRecoveryDecision(
            message = "已清理过期截屏缓存",
            deleteCaptureCache = true,
        )
    }
}
