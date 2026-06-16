package com.suishouban.app.domain

import com.suishouban.app.data.model.CardTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalActionExtractorTest {
    private val extractor = LocalActionExtractor()

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
}
