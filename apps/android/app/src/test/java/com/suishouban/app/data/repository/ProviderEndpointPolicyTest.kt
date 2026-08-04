package com.suishouban.app.data.repository

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderEndpointPolicyTest {
    @Test
    fun `chat requires public https without embedded credentials or query`() {
        assertNotNull(ProviderEndpointPolicy.normalizeChat("https://models.example.com/v1/chat/completions"))
        assertNull(ProviderEndpointPolicy.normalizeChat("http://models.example.com/v1/chat/completions"))
        assertNull(ProviderEndpointPolicy.normalizeChat("https://192.168.1.2/v1/chat/completions"))
        assertNull(ProviderEndpointPolicy.normalizeChat("https://key@models.example.com/v1/chat/completions"))
        assertNull(ProviderEndpointPolicy.normalizeChat("https://models.example.com/v1/chat/completions?key=secret"))
    }

    @Test
    fun `plain http ocr is limited to explicit vivo opt in`() {
        val official = "http://api-ai.vivo.com.cn/ocr/general_recognition"
        assertNull(ProviderEndpointPolicy.normalizeOcr(official, allowInsecureVivo = false))
        assertNotNull(ProviderEndpointPolicy.normalizeOcr(official, allowInsecureVivo = true))
        assertNull(
            ProviderEndpointPolicy.normalizeOcr(
                "http://api-ai.vivo.com.cn/ocr/other",
                allowInsecureVivo = true,
            )
        )
        assertNull(
            ProviderEndpointPolicy.normalizeOcr(
                "http://ocr.example.com/ocr/general_recognition",
                allowInsecureVivo = true,
            )
        )
    }
}
