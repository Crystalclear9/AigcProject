package com.suishouban.app.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface SuiShouBanApi {
    @POST("api/v1/analyze/screenshot-text")
    suspend fun analyzeScreenshotText(@Body request: AnalyzeScreenshotTextRequest): AnalyzeScreenshotTextResponse

    @GET("api/v1/cards")
    suspend fun listCards(
        @Query("card_type") cardType: String? = null,
        @Query("status") status: String? = null,
        @Query("q") keyword: String? = null,
    ): List<ActionCardDto>

    @POST("api/v1/cards")
    suspend fun createCard(@Body card: ActionCardDto): ActionCardDto

    @PATCH("api/v1/cards/{id}")
    suspend fun updateCard(@Path("id") id: String, @Body card: ActionCardDto): ActionCardDto

    @POST("api/v1/cards/{id}/complete")
    suspend fun completeCard(@Path("id") id: String): ActionCardDto
}
