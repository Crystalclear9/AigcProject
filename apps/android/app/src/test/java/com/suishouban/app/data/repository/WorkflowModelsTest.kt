package com.suishouban.app.data.repository

import com.suishouban.app.data.remote.AnalyzeScreenshotTextResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WorkflowModelsTest {
    @Test
    fun workflowResponseDefaultsToProvisionalStage() {
        val response = AnalyzeScreenshotTextResponse(
            ocrText = "提交报告",
            cards = emptyList(),
            previewActions = emptyList(),
            engine = "",
        )

        assertEquals("provisional", response.resultStage)
        assertEquals("rules", response.route)
        assertEquals(0, response.revision)
    }

    @Test
    fun apiFactoryReusesRetrofitServiceForSameBaseUrl() {
        val first = com.suishouban.app.data.remote.ApiFactory.create("http://127.0.0.1:8000")
        val second = com.suishouban.app.data.remote.ApiFactory.create("http://127.0.0.1:8000/")

        assertSame(first, second)
    }
}
