package com.suishouban.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotActionGateTest {
    private val gate = ScreenshotActionGate()

    @Test
    fun promptsForDeadlineSubmissionNotice() {
        val result = gate.evaluate("请在6月10日22:00前提交实验报告，逾期无法补交")

        assertTrue(result.shouldPrompt)
        assertTrue(result.matchedSignals.any { it.startsWith("行动词:") })
        assertTrue(result.deadlineHint?.contains("6月10日") == true)
    }

    @Test
    fun promptsForMeetingWithTime() {
        val result = gate.evaluate("通知：明天下午3点开组会，请大家准备项目材料")

        assertTrue(result.shouldPrompt)
    }

    @Test
    fun promptsForCommitmentWithActionAndTime() {
        val result = gate.evaluate("我明天晚上把报名材料发给你，记得提醒我")

        assertTrue(result.shouldPrompt)
    }

    @Test
    fun ignoresStatusBarOnlyScreenshot() {
        val result = gate.evaluate("15:14 5G WiFi 电量 62 今日 导入 卡片 日历 设置")

        assertFalse(result.shouldPrompt)
    }

    @Test
    fun ignoresOwnAppUiToAvoidSelfTriggeredLoops() {
        val result = gate.evaluate("随手办 卡片中心 提交实验报告 6月19日22:00 已创建提醒 完成")

        assertFalse(result.shouldPrompt)
    }

    @Test
    fun ignoresOwnSettingsReminderPolicyUi() {
        val result = gate.evaluate("自动化偏好 截图入口提示 优先使用云端模型 提醒策略 高优先级 3天 1天 会议事件 1天 30分钟")

        assertFalse(result.shouldPrompt)
    }

    @Test
    fun ignoresCasualChatWithoutActionTime() {
        val result = gate.evaluate("哈哈这个海报挺好看的，晚上再聊")

        assertFalse(result.shouldPrompt)
    }
}
