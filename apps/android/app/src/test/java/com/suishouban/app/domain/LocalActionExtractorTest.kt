package com.suishouban.app.domain

import com.suishouban.app.data.model.CardTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalActionExtractorTest {
    @Test
    fun userCorrectedTextCannotLoopBackIntoOcrReview() {
        val result = LocalActionExtractor().extract(
            text = "课程群公告 请在8月7日22:00前提交实验报告到学习通 消息 卡片",
            screenshotTime = "2026-08-01T10:00:00+08:00",
            trustedUserCorrection = true,
        )

        assertEquals("completed", result.workflowStatus)
        assertEquals(1.0, result.ocrQualityReport?.qualityScore ?: 0.0, 0.0)
        assertTrue(result.ocrQualityReport?.reasons?.contains("user_corrected") == true)
        assertTrue(result.cards.any { "实验报告" in it.title })
    }

    @Test
    fun flattenedBottomNavigationDoesNotKeepCorrectedOcrInReview() {
        val result = LocalActionExtractor().extract(
            "口课程通知口 请在8月7日22:01前提交实验报告至学习通 首页 消息",
            "2026-08-01T10:00:00+08:00",
        )

        assertEquals("completed", result.workflowStatus)
        assertTrue(result.ocrText.startsWith("课程通知"))
        assertFalse(result.ocrText.endsWith("首页 消息"))
    }
    private val extractor = LocalActionExtractor()

    @Test
    fun courseReminderKeepsAttachmentAsSupportingEvidence() {
        val result = extractor.extract(
            """
            15:14 5G WiFi 电量62%
            课程通知
            7月5日22:00前提交实验报告
            实验报告提交至学习通，文件命名为学号+姓名。
            老师提醒：逾期无法补交，请提前准备附件。
            首页 消息 日历 我的
            """.trimIndent(),
            "2026-06-07T10:00:00+08:00",
        )

        assertEquals(1, result.cards.count { "实验报告" in it.title })
        assertTrue(result.cards.none { it.title == "准备附件" || it.title == "完成附件" })
        assertTrue(result.overallConfidence > 0.0)
        assertNotNull(result.ocrQualityReport)
    }

    @Test
    fun nonActionTextReturnsNoCards() {
        val result = extractor.extract("图书馆总服务台电话 010-12345678，地址：主校区图书馆一层大厅。")

        assertTrue(result.cards.isEmpty())
        assertTrue(result.previewActions.isEmpty())
    }

    @Test
    fun strongActionTextStillGeneratesTaskCard() {
        val result = extractor.extract("请同学们在本周五晚上 22:00 前提交实验报告，提交至学习通。")

        assertEquals(1, result.cards.size)
        assertEquals(CardTypes.TASK, result.cards.first().cardType)
    }

    @Test
    fun noisyCourseScreenshotTextGeneratesTaskCard() {
        val result = extractor.extract(
            """
            15:14 5G WiFi 电量 62%
            学 习 通
            ✨ 课程通知 ✨
            6 月 20 日 22 ： 00 前
            提交《实 验 报 告》
            提交至学习通，文件命名为学号+姓名。
            首页 消息 我的
            """.trimIndent()
        )

        assertEquals(1, result.cards.size)
        assertEquals(CardTypes.TASK, result.cards.first().cardType)
        assertTrue(result.cards.first().title.contains("实验报告"))
        assertTrue(result.cards.first().deadline?.contains("T22:00") == true)
    }

    @Test
    fun posterStyleCompetitionScreenshotGeneratesTaskCard() {
        val result = extractor.extract(
            """
            AIGC 创新赛
            报 名 通 道 已 开 启
            D D L：2026.06.18 23:59
            上传作品说明书、团队信息表
            点击官网链接提交
            """.trimIndent()
        )

        assertEquals(1, result.cards.size)
        assertEquals(CardTypes.TASK, result.cards.first().cardType)
        assertTrue(result.cards.first().deadline?.startsWith("2026-06-18T23:59") == true)
        assertTrue(result.cards.first().materials.contains("作品说明书"))
    }

    @Test
    fun comparisonTextStillGeneratesComparisonCard() {
        val result = extractor.extract("方案 A 价格 399 元；方案 B 价格 459 元，帮我对比一下选哪个。")

        assertEquals(1, result.cards.size)
        assertEquals(CardTypes.COMPARISON, result.cards.first().cardType)
    }

    @Test
    fun multiTaskNoticeSplitsIntoSeparateConcreteCards() {
        val result = extractor.extract(
            """
            课程群公告
            ① 请在 6月26日 22:00 前提交《实验报告》到学习通，文件名：学号+姓名。
            ② 6月27日 14:30 参加腾讯会议，并准备本周进展汇报 PPT，会议号 886 210 552。
            ③ 报名表 6月28日 前发到指定邮箱，逾期不补。
            广告：18 文具满减与本通知无关。
            """.trimIndent()
        )

        val titles = result.cards.map { it.title }
        assertTrue("titles=$titles", titles.any { it.contains("实验报告") })
        assertTrue("titles=$titles", titles.any { it.contains("会议") || it.contains("汇报") })
        assertTrue("titles=$titles", titles.any { it.contains("报名表") || it.contains("报名") })
        assertTrue("cards=${result.cards}", result.cards.size >= 3)
        assertTrue("titles=$titles", result.cards.none { it.title == "相关日程" || it.title == "处理截图事项" })
    }
}
