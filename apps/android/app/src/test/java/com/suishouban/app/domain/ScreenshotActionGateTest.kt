package com.suishouban.app.domain

import com.suishouban.app.domain.screenshot.ScreenshotActionGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotActionGateTest {
    private val gate = ScreenshotActionGate()

    @Test
    fun promptsForDeadlineSubmissionNotice() {
        val result = gate.evaluate("请在6月10日22:00前提交实验报告，逾期无法补交")

        assertTrue(result.shouldPrompt)
        assertTrue(result.matchedSignals.any { it.startsWith("行动:") })
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

    @Test
    fun promptsForNoisyCourseScreenshotWithStatusBarAndBottomTabs() {
        val result = gate.evaluate(
            """
            15:14 5G WiFi 62%
            学习通
            ✨ 课程通知 ✨
            请各位同学
            6 月 20 日 22 ： 00 前
            提交《实验报告》
            提交至学习通，文件命名为学号+姓名
            首页 消息 我的
            """.trimIndent()
        )

        assertTrue(result.shouldPrompt)
        assertTrue(result.suggestedTitle?.contains("实验报告") == true)
        assertTrue(result.deadlineHint?.contains("6 月 20 日") == true || result.deadlineHint?.contains("6月20日") == true)
    }

    @Test
    fun promptsForPosterStyleCompetitionNotice() {
        val result = gate.evaluate(
            """
            AIGC 创新赛
            报 名 通 道 已 开 启
            D D L：2026.06.18 23:59
            上传作品说明书、团队信息表
            点击官网链接提交
            """.trimIndent()
        )

        assertTrue(result.shouldPrompt)
        assertTrue(result.matchedSignals.any { it.contains("截止:DDL") || it.contains("截止:ddl") })
    }

    @Test
    fun ignoresShoppingPromoEvenWithDeadlineWords() {
        val result = gate.evaluate("618 优惠券 限时秒杀 明晚20:00截止 抢购满减 购物车 下单")

        assertFalse(result.shouldPrompt)
    }

    @Test
    fun promptsForMeetingPosterWithPreparationTask() {
        val result = gate.evaluate(
            """
            团队周会安排
            周五 14:30 腾讯会议
            请参加会议并准备本周进展汇报 PPT。
            需要提前 10 分钟签到。
            """.trimIndent()
        )

        assertTrue(result.shouldPrompt)
        assertTrue(result.suggestedTitle?.contains("会议") == true || result.suggestedTitle?.contains("汇报") == true)
    }

    @Test
    fun ignoresGeneratedOwnAppSettingsImage() {
        val result = gate.evaluate(
            """
            随手办
            设置中心
            云端增强（可选）
            Workflow API URL，可留空
            提醒策略：高优先级 3 天 / 1 天 / 3 小时 / 30 分钟
            已创建提醒 0
            """.trimIndent()
        )

        assertFalse(result.shouldPrompt)
    }
}
