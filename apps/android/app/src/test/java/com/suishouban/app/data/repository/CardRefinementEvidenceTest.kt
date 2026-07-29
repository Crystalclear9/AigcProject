package com.suishouban.app.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test

class CardRefinementEvidenceTest {
    @Test
    fun summaryPrefersActionFactsAndStaysCompact() {
        val text = buildString {
            appendLine("# 课程大作业说明")
            repeat(20) { appendLine("背景介绍与课程愿景 $it") }
            appendLine("请于 8 月 20 日 22:00 前提交实验报告。")
            appendLine("提交平台：学习通；材料包括 PDF 报告和源代码。")
            appendLine("8 月 18 日在 A301 教室进行中期评审。")
        }

        val summary = summarizeLocalEvidence(text)

        assertTrue(summary.length <= 360)
        assertTrue(summary.contains("8 月 20 日"))
        assertTrue(summary.contains("学习通"))
        assertTrue(summary.contains("A301"))
    }
}
