package com.suishouban.app.data.repository

import java.security.MessageDigest

/**
 * Privacy gate applied before a notification can enter local candidate storage.
 *
 * The filter is deliberately conservative: discarded notifications never reach OCR/model code,
 * and accepted notifications are still drafts that require explicit user confirmation.
 */
class NotificationCandidatePolicy(
    private val ownPackageName: String,
) {
    fun evaluate(
        input: NotificationCandidateInput,
        allowlist: Set<String>,
    ): NotificationCandidateDecision = when {
        input.packageName == ownPackageName -> NotificationCandidateDecision.SELF_NOTIFICATION
        input.packageName !in allowlist -> NotificationCandidateDecision.NOT_ALLOWLISTED
        input.isOngoing || input.isGroupSummary -> NotificationCandidateDecision.SYSTEM_NOISE
        normalizedText(input).isBlank() -> NotificationCandidateDecision.EMPTY
        containsSensitiveResult(input) -> NotificationCandidateDecision.SENSITIVE
        else -> NotificationCandidateDecision.ACCEPT
    }

    /** Stable across notification reposts so duplicate system keys do not create duplicate drafts. */
    fun contentHash(input: NotificationCandidateInput): String {
        val canonical = listOf(
            input.packageName.trim().lowercase(),
            normalize(input.title),
            normalize(input.body),
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    fun expiresAt(postedAtMillis: Long): Long = postedAtMillis + CANDIDATE_TTL_MILLIS

    private fun containsSensitiveResult(input: NotificationCandidateInput): Boolean {
        val text = normalizedText(input)
        val containsOtp = OTP_WORDS.any(text::contains) && OTP_DIGITS.containsMatchIn(text)
        val containsPaymentResult = PAYMENT_RESULT_WORDS.any(text::contains)
        return containsOtp || containsPaymentResult
    }

    private fun normalizedText(input: NotificationCandidateInput): String =
        normalize("${input.title} ${input.body}")

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace(WHITESPACE, " ")

    private companion object {
        const val CANDIDATE_TTL_MILLIS = 24L * 60L * 60L * 1_000L
        val WHITESPACE = Regex("\\s+")
        val OTP_DIGITS = Regex("(?<!\\d)\\d{4,8}(?!\\d)")
        val OTP_WORDS = listOf("验证码", "动态码", "校验码", "otp", "verification code")
        val PAYMENT_RESULT_WORDS = listOf("支付成功", "付款成功", "扣款成功", "交易成功", "到账通知")
    }
}
