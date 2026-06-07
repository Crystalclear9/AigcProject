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
    fun comparisonTextStillGeneratesComparisonCard() {
        val result = extractor.extract("方案 A 价格 399 元；方案 B 价格 459 元，帮我对比一下选哪个。")

        assertEquals(1, result.cards.size)
        assertEquals(CardTypes.COMPARISON, result.cards.first().cardType)
    }
}
