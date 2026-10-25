package com.commercialoutcomes.uktelevision

import org.junit.Assert.*
import org.junit.Test
import okhttp3.Request
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse

class PublicStreamHttpTest {
    @Test fun publicFeedsCannotTargetLocalServicesEvenOverHttp() {
        listOf("http://127.0.0.1/a", "https://192.168.1.1/a", "http://169.254.169.254/a",
            "http://localhost/a", "http://router.local/a", "http://[::1]/a", "http://user:pass@example.org/a")
            .forEach { assertFalse(it, PublicStreamHttp.allowed(it)) }
        assertTrue(PublicStreamHttp.allowed("http://cdn.example.org:8080/live.m3u8"))
        assertTrue(PublicStreamHttp.allowed("https://cdn.example.org/live.m3u8"))
    }
    @Test fun credentialsFollowTheirOriginNotTheHlsChildHost() {
        val headers = mapOf("Authorization" to "secret", "Cookie" to "session", "User-Agent" to "TV")
        assertEquals(headers, PublicStreamHttp.headers(headers,"https://a.example/live","https://a.example/segment"))
        assertEquals(mapOf("User-Agent" to "TV"), PublicStreamHttp.headers(headers,"https://a.example/live","https://b.example/segment"))
        assertEquals(mapOf("User-Agent" to "TV"), PublicStreamHttp.headers(headers,"https://a.example/live","http://a.example/segment"))
    }
    @Test fun publicTransportRefusesLoopbackButExplicitUserLanTransportWorks() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            val request = Request.Builder().url(server.url("/live")).build()
            assertThrows(java.io.IOException::class.java) { PublicStreamHttp.client(false).newCall(request).execute().close() }
            assertEquals(0, server.requestCount)
            PublicStreamHttp.client(false, publicOnly=false).newCall(request).execute().use { assertEquals("ok", it.body!!.string()) }
        }
    }
}
