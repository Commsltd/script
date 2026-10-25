package com.commercialoutcomes.uktelevision

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

class CrewBridgeClientTest {
    @Test fun bridgeRedirectIsRejectedWithoutSendingTokenToTarget() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location",server.url("/target")))
            server.enqueue(MockResponse().setBody("{}"))
            val connection=server.url("/redirect").toUrl().openConnection() as HttpURLConnection
            assertThrows(IllegalStateException::class.java) { CrewBridgeClient.request(connection,"a".repeat(64),JSONObject()) }
            assertEquals(1,server.requestCount)
            assertNull(server.takeRequest(2,TimeUnit.SECONDS)?.getHeader("Authorization"))
        }
    }

    @Test fun validBridgeResponseWorksOverRealHttp() {
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    val body="{\"status\":\"idle\"}"
                    return MockResponse().setBody(body).addHeader("X-UKTV-Signature",CrewBridgeAuth.response("a".repeat(64),request.getHeader("X-UKTV-Nonce")!!,200,body.toByteArray()))
                }
            }
            server.start()
            val connection=server.url("/status").toUrl().openConnection() as HttpURLConnection
            assertEquals("idle",CrewBridgeClient.request(connection,"a".repeat(64),JSONObject()).getString("status"))
            val request=server.takeRequest(2,TimeUnit.SECONDS)!!
            assertNull(request.getHeader("Authorization"))
            assertFalse(request.headers.toString().contains("a".repeat(64)))
            assertEquals(CrewBridgeAuth.request("a".repeat(64),"/status",request.getHeader("X-UKTV-Time")!!,request.getHeader("X-UKTV-Nonce")!!,"{}".toByteArray()),request.getHeader("X-UKTV-Signature"))
        }
    }

    @Test fun impersonatedEngineWithoutAValidSignatureIsRejected() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"url\":\"https://attacker.example.com/movie\"}"))
            server.start()
            val connection=server.url("/status").toUrl().openConnection() as HttpURLConnection
            assertThrows(IllegalStateException::class.java) { CrewBridgeClient.request(connection,"a".repeat(64),JSONObject()) }
        }
    }

    @Test fun authenticationMatchesPythonVectorsAndRejectsModifiedReplies() {
        val token="a".repeat(64);val nonce="b".repeat(32);val body="{\"status\":\"idle\"}".toByteArray()
        assertEquals("4644a02180ecac49931a6a713e4008fd1b75ccbb93ec28bcc88e4cff2966a3fe",CrewBridgeAuth.request(token,"/status","1700000000",nonce,"{}".toByteArray()))
        val signature="077b3b9e7943f8d2de59d7012cb335b40110919d0054670db927571678f8f00b"
        assertTrue(CrewBridgeAuth.validResponse(token,nonce,200,body,signature))
        assertFalse(CrewBridgeAuth.validResponse(token,nonce,201,body,signature))
        assertFalse(CrewBridgeAuth.validResponse(token,nonce,200,"{}".toByteArray(),signature))
        assertFalse(CrewBridgeAuth.validResponse(token,"c".repeat(32),200,body,signature))
    }
}
