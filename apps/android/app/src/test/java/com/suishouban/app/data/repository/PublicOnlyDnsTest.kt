package com.suishouban.app.data.repository

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicOnlyDnsTest {
    @Test
    fun `rejects local private cgnat and documentation addresses`() {
        listOf("127.0.0.1", "10.0.0.8", "192.168.1.2", "100.64.0.1", "198.18.0.1", "2001:db8::1")
            .forEach { assertFalse(it, PublicOnlyDns.isPublic(InetAddress.getByName(it))) }
    }

    @Test
    fun `accepts public addresses`() {
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111")
            .forEach { assertTrue(it, PublicOnlyDns.isPublic(InetAddress.getByName(it))) }
    }
}
