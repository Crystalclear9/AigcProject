package com.suishouban.app.domain.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrQualityScorerTest {
    @Test
    fun `garbled candidate cannot win because of arrival order`() {
        val first = OcrCandidate(
            engine = "mlkit",
            text = "提□交□□报�告 6月?? 22:00 学□通",
            arrivedAtMs = 1,
        )
        val complete = OcrCandidate(
            engine = "vivo-ocr",
            text = "请在6月10日22:00前通过学习通提交实验报告",
            arrivedAtMs = 200,
        )

        val result = requireNotNull(OcrRaceController.arbitrate(listOf(first, complete)))

        assertEquals("vivo-ocr", result.selectedCandidate.engine)
        assertTrue(first.qualityReport.reasons.contains("garbled_characters"))
    }

    @Test
    fun `single low quality candidate is held for review`() {
        val result = requireNotNull(
            OcrRaceController.arbitrate(
                listOf(OcrCandidate(engine = "mlkit", text = "□�□�□"))
            )
        )

        assertTrue(result.requiresReview)
        assertTrue("low_ocr_quality" in result.reviewReasons)
    }
}
