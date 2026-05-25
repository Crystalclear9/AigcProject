package com.suishouban.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineLabelsTest {
    @Test
    fun prefixesMlkitFallbackEngines() {
        assertEquals("mlkit+lanxin", EngineLabels.withPrefix("lanxin", "mlkit"))
        assertEquals("mlkit+rules", EngineLabels.withPrefix("local-rules", "mlkit"))
    }

    @Test
    fun doesNotDoublePrefixCloudImageEngines() {
        assertEquals("vivo-ocr+rules", EngineLabels.withPrefix("vivo-ocr+rules", "vivo-ocr"))
    }
}
