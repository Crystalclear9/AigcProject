package com.suishouban.app.capture

/** ResultReceiver protocol shared by the consent Activity and one-shot capture service. */
object ScreenCaptureResult {
    const val SUCCESS = 1
    const val CANCELLED = 2
    const val PROTECTED_CONTENT = 3
    const val TIMEOUT = 4
    const val FAILURE = 5
    const val DUPLICATE = 6

    const val KEY_URI = "capture_uri"
    const val KEY_MESSAGE = "capture_message"
}
