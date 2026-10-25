package com.commercialoutcomes.uktelevision

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object CrewBridgeClient {
    fun call(token: String, path: String, body: JSONObject): JSONObject {
        require(path in setOf("/status", "/search", "/catalogue", "/resolve", "/quit"))
        require(token.length in 32..256 && token.all { it in '!'..'~' }) { "The Crew is not configured on this Firestick." }
        return request(URL("http://127.0.0.1:18765$path").openConnection() as HttpURLConnection, token, body)
    }

    internal fun request(connection: HttpURLConnection, token: String, body: JSONObject): JSONObject {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4096) { "Crew request too large" }
        val nonce = CrewBridgeAuth.nonce()
        val timestamp = (System.currentTimeMillis()/1000).toString()
        connection.apply {
            connectTimeout = 1500; readTimeout = 4000; requestMethod = "POST"; doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("X-UKTV-Time", timestamp)
            setRequestProperty("X-UKTV-Nonce", nonce)
            setRequestProperty("X-UKTV-Signature", CrewBridgeAuth.request(token, url.path, timestamp, nonce, bytes))
            setRequestProperty("Content-Type", "application/json")
            setFixedLengthStreamingMode(bytes.size)
        }
        try {
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            check(code !in 300..399) { "The local Crew engine returned an unexpected redirect" }
            val input = (if (code < 400) connection.inputStream else connection.errorStream)
                ?: error("The local Crew engine returned no response")
            val raw = input.use { it.readBounded(2*1024*1024) }
            check(CrewBridgeAuth.validResponse(token,nonce,code,raw,connection.getHeaderField("X-UKTV-Signature"))) {
                "Cannot verify the local Crew engine. This update requires UK TV bridge 0.2.1."
            }
            val result = JSONObject(raw.toString(Charsets.UTF_8))
            check(code in 200..299) { result.optString("error", "The Crew is unavailable").take(160) }
            return result
        } finally { connection.disconnect() }
    }
}
