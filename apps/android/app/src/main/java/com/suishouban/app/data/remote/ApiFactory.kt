package com.suishouban.app.data.remote

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import com.suishouban.app.data.repository.PublicOnlyDns

object ApiFactory {
    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(PublicOnlyDns)
        .addInterceptor(logging)
        .build()
    private val cache = ConcurrentHashMap<String, SuiShouBanApi>()

    fun create(baseUrl: String): SuiShouBanApi {
        val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return cache.getOrPut(normalizedBaseUrl) {
            Retrofit.Builder()
                .baseUrl(normalizedBaseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(SuiShouBanApi::class.java)
        }
    }
}
