package com.suishouban.app.data.repository

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object WorkflowUrlPolicy {
    private val vivoProviderHost = listOf("api-ai", "vivo", "com", "cn").joinToString(".")
    private val chatCompletionPath = listOf("", "v1", "chat", "completions").joinToString("/")
    private val imageGenerationPath = listOf("", "api", "v1", "image_generation").joinToString("/")
    private val ocrRecognitionPath = listOf("", "ocr", "general_recognition").joinToString("/")

    private val blockedHosts = setOf(
        "localhost",
        "127.0.0.1",
        "0.0.0.0",
        "10.0.2.2",
        "::1",
        vivoProviderHost,
    )

    private val blockedProviderPaths = listOf(
        chatCompletionPath,
        imageGenerationPath,
        ocrRecognitionPath,
    )

    fun isAccepted(rawBaseUrl: String): Boolean = normalize(rawBaseUrl) != null

    fun normalize(rawBaseUrl: String): String? {
        val url = rawBaseUrl.trim().takeIf { it.isNotBlank() }?.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https") return null
        val host = url.host.lowercase()
        val path = url.encodedPath.lowercase()
        if (url.username.isNotBlank() || url.password.isNotBlank()) return null
        if (url.query != null || url.fragment != null) return null
        if (host in blockedHosts || host.endsWith(".local") || isPrivateIpHost(host) || isPrivateIpv6Host(host)) return null
        if (host == vivoProviderHost || host.endsWith(".$vivoProviderHost") || blockedProviderPaths.any { path.contains(it) }) return null
        return url.toString()
    }

    private fun isPrivateIpHost(host: String): Boolean {
        val parts = host.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val first = parts[0]
        val second = parts[1]
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 169 && second == 254)
    }

    private fun isPrivateIpv6Host(host: String): Boolean {
        val normalized = host.trim('[', ']').lowercase()
        if (":" !in normalized) return false
        return normalized == "::1" ||
            normalized.startsWith("fe80:") ||
            normalized.startsWith("fc") ||
            normalized.startsWith("fd")
    }
}
