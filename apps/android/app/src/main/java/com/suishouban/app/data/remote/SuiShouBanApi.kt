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

interface SuiShouBanApi {
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
