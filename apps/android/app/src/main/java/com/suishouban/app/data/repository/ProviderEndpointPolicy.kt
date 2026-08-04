package com.suishouban.app.data.repository

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ProviderEndpointPolicy {
    private const val VIVO_HOST = "api-ai.vivo.com.cn"
    private const val VIVO_OCR_PATH = "/ocr/general_recognition"

    fun normalizeChat(raw: String): String? = normalizeHttps(raw)

    fun normalizeOcr(raw: String, allowInsecureVivo: Boolean): String? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        if (!baseChecks(url)) return null
        if (url.scheme == "https") return url.toString()
        return url.toString().takeIf {
            allowInsecureVivo &&
                url.scheme == "http" &&
                url.host.equals(VIVO_HOST, ignoreCase = true) &&
                url.encodedPath == VIVO_OCR_PATH &&
                url.port == 80
        }
    }

    private fun normalizeHttps(raw: String): String? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        return url.toString().takeIf { url.scheme == "https" && baseChecks(url) }
    }

    private fun baseChecks(url: HttpUrl): Boolean {
        val host = url.host.lowercase()
        if (url.username.isNotBlank() || url.password.isNotBlank()) return false
        if (url.query != null || url.fragment != null) return false
        if (host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" || host == "10.0.2.2") return false
        if (host.endsWith(".local") || isPrivateIpv4(host) || isPrivateIpv6(host)) return false
        return true
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.').mapNotNull(String::toIntOrNull)
        if (parts.size != 4) return false
        return parts[0] == 10 ||
            parts[0] == 127 ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 169 && parts[1] == 254)
    }

    private fun isPrivateIpv6(host: String): Boolean {
        val value = host.trim('[', ']').lowercase()
        return ':' in value && (
            value == "::1" || value.startsWith("fe80:") ||
                value.startsWith("fc") || value.startsWith("fd")
            )
    }
}
