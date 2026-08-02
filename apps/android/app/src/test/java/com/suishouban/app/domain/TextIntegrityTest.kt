package com.suishouban.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextIntegrityTest {
    @Test
    fun randomIdentifierAndChromeAreRejected() {
        val report = TextIntegrity.summaryQuality(
            "22:29 未分类 欢迎来到原子笔记 V2-CR22k3zM_OVq7CS",
        )

        assertFalse(report.acceptable)
        assertTrue("random_identifier" in report.reasons)
        assertTrue("ui_noise" in report.reasons)
    }

    @Test
    fun evidenceComposerDoesNotCopyRawOcrChrome() {
        val summary = EvidenceSummaryComposer.compose(
            title = "提交实验报告",
            deadline = "2026-08-12T22:01:00+08:00",
            startTime = null,
            location = "学习通",
            materials = listOf("实验报告"),
            submitMethod = "上传",
        )

        assertTrue(summary.contains("提交实验报告"))
        assertTrue(summary.contains("22:01"))
        assertFalse(summary.contains("欢迎来到"))
    }

    @Test
    fun higherQualityIncomingSummaryReplacesGarbledLocalValue() {
        assertEquals(
            "8月12日22:00前提交实验报告至学习通",
            TextIntegrity.chooseBetterSummary(
                "锟斤拷 22:29 V2-CR22k3zM_OVq7CS",
                "8月12日22:00前提交实验报告至学习通",
            ),
        )
    }

    @Test
    fun ocrCorrectionSuggestionIsConservativeAndPreservesTime() {
        assertEquals(
            "请在8月7日22:01前提交实验报告至学习通",
            TextIntegrity.suggestOcrCorrection("请在8月7日22:01前提交实验根告至学刁通"),
        )
        assertEquals(null, TextIntegrity.suggestOcrCorrection("请在8月7日22:01前提交实验报告"))
        assertEquals(
            "课程群公告 8月8日14:30参加会议并准备PPT ③报名表发到邮箱",
            TextIntegrity.suggestOcrCorrection(
                "谍程群公告 8月8日14:30参加会议井准备PPT ⑨报各表发到邮箱",
            ),
        )
    }
}
