package com.suishouban.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotificationCandidatePolicyTest {
    private val policy = NotificationCandidatePolicy(ownPackageName = "com.suishouban.app")

    @Test
    fun acceptsActionableNotificationFromAllowlistedApp() {
        val decision = policy.evaluate(
            input(title = "会议调整", body = "项目会改到明天下午三点，请准备周报"),
            allowlist = setOf("com.example.chat"),
        )

        assertEquals(NotificationCandidateDecision.ACCEPT, decision)
    }

    @Test
    fun rejectsAppsOutsideAllowlistAndThisAppItself() {
        assertEquals(
            NotificationCandidateDecision.NOT_ALLOWLISTED,
            policy.evaluate(input(packageName = "com.example.mail"), setOf("com.example.chat")),
        )
        assertEquals(
            NotificationCandidateDecision.SELF_NOTIFICATION,
            policy.evaluate(
                input(packageName = "com.suishouban.app"),
                setOf("com.suishouban.app"),
            ),
        )
    }

    @Test
    fun rejectsOtpAndPaymentResults() {
        assertEquals(
            NotificationCandidateDecision.SENSITIVE,
            policy.evaluate(
                input(title = "验证码", body = "您的验证码是 482913，五分钟内有效"),
                setOf("com.example.chat"),
            ),
        )
        assertEquals(
            NotificationCandidateDecision.SENSITIVE,
            policy.evaluate(
                input(title = "支付成功", body = "本次扣款 28.00 元"),
                setOf("com.example.chat"),
            ),
        )
    }

    @Test
    fun rejectsOngoingGroupSummaryAndBlankContent() {
        assertEquals(
            NotificationCandidateDecision.SYSTEM_NOISE,
            policy.evaluate(input(isOngoing = true), setOf("com.example.chat")),
        )
        assertEquals(
            NotificationCandidateDecision.SYSTEM_NOISE,
            policy.evaluate(input(isGroupSummary = true), setOf("com.example.chat")),
        )
        assertEquals(
            NotificationCandidateDecision.EMPTY,
            policy.evaluate(input(title = " ", body = "\n"), setOf("com.example.chat")),
        )
    }

    @Test
    fun contentHashIgnoresNotificationKeyAndIncidentalWhitespace() {
        val first = input(
            notificationKey = "first-key",
            title = " 会议调整 ",
            body = "明天下午三点   开会",
        )
        val second = input(
            notificationKey = "second-key",
            title = "会议调整",
            body = "明天下午三点 开会",
        )

        assertEquals(policy.contentHash(first), policy.contentHash(second))
        assertNotEquals(
            policy.contentHash(first),
            policy.contentHash(first.copy(packageName = "com.example.other")),
        )
    }

    @Test
    fun candidatesExpireAfterTwentyFourHours() {
        assertEquals(86_401_000L, policy.expiresAt(postedAtMillis = 1_000L))
    }

    private fun input(
        notificationKey: String = "notification-1",
        packageName: String = "com.example.chat",
        title: String = "待办提醒",
        body: String = "请在明天下午三点前提交材料",
        isOngoing: Boolean = false,
        isGroupSummary: Boolean = false,
    ) = NotificationCandidateInput(
        notificationKey = notificationKey,
        packageName = packageName,
        appLabel = "示例应用",
        title = title,
        body = body,
        postedAtMillis = 1_000L,
        isOngoing = isOngoing,
        isGroupSummary = isGroupSummary,
    )
}
