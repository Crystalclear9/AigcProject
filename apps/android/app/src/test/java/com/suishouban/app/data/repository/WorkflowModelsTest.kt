package com.suishouban.app.data.repository

import com.suishouban.app.data.remote.AnalyzeScreenshotTextResponse
import com.suishouban.app.data.remote.WorkflowEventEnvelope
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
        assertNull(response.cacheStatus)
        assertEquals("not_configured", response.modelEnhancementStatus)
        assertEquals("not_configured", response.ocrEnhancementStatus)
    }

    @Test
    fun apiFactoryReusesRetrofitServiceForSameBaseUrl() {
        val first = com.suishouban.app.data.remote.ApiFactory.create("http://127.0.0.1:8000")
        val second = com.suishouban.app.data.remote.ApiFactory.create("http://127.0.0.1:8000/")

        assertSame(first, second)
    }

    @Test
    fun sseEventSnapshotCanDriveUiWithoutFollowUpGet() {
        val event = Gson().fromJson(
            """
            {
              "snapshot": {
                "ocr_text": "提交报告",
                "cards": [],
                "preview_actions": [],
                "engine": "rules",
                "run_id": "run-1",
                "workflow_status": "awaiting_review",
                "revision": 2
              }
            }
            """.trimIndent(),
            WorkflowEventEnvelope::class.java,
        )

        assertEquals("run-1", event.snapshot?.runId)
        assertEquals("awaiting_review", event.snapshot?.workflowStatus)
        assertEquals(2, event.snapshot?.revision)
        assertNull(event.snapshot?.cacheStatus)
    }

    @Test
    fun sseSnapshotWithNullCacheStatusCanDeserialize() {
        val event = Gson().fromJson(
            """
            {
              "snapshot": {
                "ocr_text": "提交报告",
                "cards": [],
                "preview_actions": [],
                "engine": "rules",
                "workflow_status": "awaiting_review",
                "cache_status": null
              }
            }
            """.trimIndent(),
            WorkflowEventEnvelope::class.java,
        )

        assertNull(event.snapshot?.cacheStatus)
    }

    @Test
    fun providerUsageAndEnhancementStatusCanDeserialize() {
        val event = Gson().fromJson(
            """
            {
              "snapshot": {
                "ocr_text": "提交报告",
                "cards": [],
                "preview_actions": [],
                "engine": "mlkit+supervisor-agents",
                "workflow_status": "awaiting_review",
                "cache_status": "bypass",
                "model_enhancement_status": "succeeded",
                "ocr_enhancement_status": "succeeded",
                "provider_usage": {
                  "fast_model": {
                    "request_count_delta": 1,
                    "success_count_delta": 1,
                    "failure_count_delta": 0,
                    "latency_ms": 321.5
                  },
                  "ocr": {
                    "request_count_delta": 1,
                    "success_count_delta": 1,
                    "failure_count_delta": 0
                  }
                }
              }
            }
            """.trimIndent(),
            WorkflowEventEnvelope::class.java,
        )

        val snapshot = event.snapshot!!
        assertEquals("succeeded", snapshot.modelEnhancementStatus)
        assertEquals(1, snapshot.providerUsage["fast_model"]?.successCountDelta)
        assertEquals(321.5, snapshot.providerUsage["fast_model"]?.latencyMs ?: 0.0, 0.01)
    }

    @Test
    fun reactSuggestionsCanDeserializeFromWorkflowSnapshot() {
        val event = Gson().fromJson(
            """
            {
              "snapshot": {
                "ocr_text": "提交报告",
                "cards": [],
                "preview_actions": [],
                "engine": "mlkit+react+model",
                "workflow_status": "awaiting_review",
                "react_suggestions": [
                  "标题仍然偏泛化，需要改成具体动作",
                  "提交实验报告 需要确认提交方式或平台"
                ]
              }
            }
            """.trimIndent(),
            WorkflowEventEnvelope::class.java,
        )

        val snapshot = event.snapshot!!
        assertEquals(2, snapshot.reactSuggestions.size)
        assertTrue(snapshot.reactSuggestions.first().contains("具体动作"))
    }

    @Test
    fun workflowUrlPolicyAcceptsOnlyPublicGatewayUrls() {
        assertTrue(WorkflowUrlPolicy.isAccepted("https://workflow.example.com/"))

        assertFalse(WorkflowUrlPolicy.isAccepted("http://workflow.example.com/"))
        assertFalse(WorkflowUrlPolicy.isAccepted("https://127.0.0.1:8000/"))
        assertFalse(WorkflowUrlPolicy.isAccepted("https://10.0.2.2:8000/"))
        assertFalse(WorkflowUrlPolicy.isAccepted("https://192.168.1.2/"))
        assertFalse(WorkflowUrlPolicy.isAccepted("https://api-ai.vivo.com.cn/v1/chat/completions"))
        assertFalse(WorkflowUrlPolicy.isAccepted("https://api-ai.vivo.com.cn/api/v1/image_generation"))
        assertFalse(WorkflowUrlPolicy.isAccepted("https://api-ai.vivo.com.cn/ocr/general_recognition"))
    }
}
