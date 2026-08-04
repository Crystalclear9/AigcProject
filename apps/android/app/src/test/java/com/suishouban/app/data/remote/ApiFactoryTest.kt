package com.suishouban.app.data.remote

import com.suishouban.app.data.repository.PublicOnlyDns
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertSame
import org.junit.Test

class ApiFactoryTest {
    @Test
    fun workflowDnsAllowsOnlyTheExplicitDebugLoopbackGateway() {
        assertSame(
            Dns.SYSTEM,
            ApiFactory.workflowDns(
                "http://127.0.0.1:8000/".toHttpUrl(),
                allowLocalDebugGateway = true,
            ),
        )
        assertSame(
            PublicOnlyDns,
            ApiFactory.workflowDns(
                "http://127.0.0.1:8000/".toHttpUrl(),
                allowLocalDebugGateway = false,
            ),
        )
        assertSame(
            PublicOnlyDns,
            ApiFactory.workflowDns(
                "http://192.168.1.10:8000/".toHttpUrl(),
                allowLocalDebugGateway = true,
            ),
        )
    }
}
