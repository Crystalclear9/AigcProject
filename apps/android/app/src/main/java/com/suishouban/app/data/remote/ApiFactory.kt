package com.suishouban.app.data.remote

import com.suishouban.app.BuildConfig
import com.suishouban.app.data.repository.PublicOnlyDns
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

object ApiFactory {
    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }
    private val publicClient = clientBuilder().dns(PublicOnlyDns).build()
    private val localDebugClient = clientBuilder().dns(Dns.SYSTEM).build()
    private val cache = ConcurrentHashMap<String, SuiShouBanApi>()

    fun create(baseUrl: String): SuiShouBanApi {
        val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val url = normalizedBaseUrl.toHttpUrl()
        val client = if (workflowDns(url) === Dns.SYSTEM) localDebugClient else publicClient
        return cache.getOrPut(normalizedBaseUrl) {
            Retrofit.Builder()
                .baseUrl(normalizedBaseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(SuiShouBanApi::class.java)
        }
    }

    private fun clientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(logging)

    internal fun workflowDns(
        url: HttpUrl,
        allowLocalDebugGateway: Boolean = BuildConfig.DEBUG &&
            BuildConfig.ALLOW_LOCAL_WORKFLOW_GATEWAY,
    ): Dns {
        // ADB reverse exposes the host gateway only through this exact debug-only loopback URL.
        return if (
            allowLocalDebugGateway &&
            url.scheme == "http" &&
            url.host == "127.0.0.1"
        ) {
            Dns.SYSTEM
        } else {
            PublicOnlyDns
        }
    }
}
