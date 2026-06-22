package com.suishouban.app.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.Part
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Header
import retrofit2.http.Streaming

interface SuiShouBanApi {
    @POST("api/workflows/screenshot-text")
    suspend fun startTextWorkflow(@Body request: AnalyzeScreenshotTextRequest): AnalyzeScreenshotTextResponse

    @Multipart
    @POST("api/workflows/screenshot-image")
    suspend fun startImageWorkflow(
        @Part image: MultipartBody.Part,
        @Part("screenshot_time") screenshotTime: RequestBody? = null,
    ): AnalyzeScreenshotTextResponse

    @POST("api/workflows/{run_id}/resume")
    suspend fun resumeWorkflow(
        @Path("run_id") runId: String,
        @Body request: WorkflowResumeRequest,
    ): AnalyzeScreenshotTextResponse

    @GET("api/workflows/{run_id}")
    suspend fun getWorkflow(@Path("run_id") runId: String): AnalyzeScreenshotTextResponse

    @POST("api/workflows/{run_id}/ocr-candidates")
    suspend fun submitOcrCandidate(
        @Path("run_id") runId: String,
        @Body request: OcrCandidateRequest,
    ): AnalyzeScreenshotTextResponse

    @PATCH("api/workflows/{run_id}/draft")
    suspend fun patchDraft(
        @Path("run_id") runId: String,
        @Body request: DraftPatchRequest,
    ): AnalyzeScreenshotTextResponse

    @POST("api/workflows/{run_id}/confirm")
    suspend fun confirmWorkflow(
        @Path("run_id") runId: String,
        @Body request: ConfirmWorkflowRequest,
    ): AnalyzeScreenshotTextResponse

    @POST("api/workflows/{run_id}/react")
    suspend fun reactWorkflow(
        @Path("run_id") runId: String,
        @Body request: WorkflowReactRequest,
    ): AnalyzeScreenshotTextResponse

    @Streaming
    @GET("api/workflows/{run_id}/events")
    suspend fun workflowEvents(
        @Path("run_id") runId: String,
        @Header("Last-Event-ID") lastEventId: String? = null,
    ): ResponseBody

    @GET("health")
    suspend fun health(): HealthResponse

    @POST("api/providers/probe")
    suspend fun providerProbe(): ProviderProbeResponse

    @POST("api/analyze/screenshot-text")
    suspend fun analyzeScreenshotText(@Body request: AnalyzeScreenshotTextRequest): AnalyzeScreenshotTextResponse

    @Multipart
    @POST("api/analyze/screenshot-image")
    suspend fun analyzeScreenshotImage(
        @Part image: MultipartBody.Part,
        @Part("screenshot_time") screenshotTime: RequestBody? = null,
    ): AnalyzeScreenshotTextResponse

    @GET("api/cards")
    suspend fun listCards(
        @Query("card_type") cardType: String? = null,
        @Query("status") status: String? = null,
        @Query("q") keyword: String? = null,
    ): List<ActionCardDto>

    @POST("api/cards")
    suspend fun createCard(@Body card: ActionCardDto): ActionCardDto

    @PATCH("api/cards/{id}")
    suspend fun updateCard(@Path("id") id: String, @Body card: ActionCardDto): ActionCardDto

    @POST("api/cards/{id}/complete")
    suspend fun completeCard(@Path("id") id: String): ActionCardDto
}
