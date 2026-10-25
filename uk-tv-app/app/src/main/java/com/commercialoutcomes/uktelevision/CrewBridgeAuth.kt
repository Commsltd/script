package com.commercialoutcomes.uktelevision

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Pairing secret stays in private storage; each reply is bound to a fresh request. */
object CrewBridgeAuth {
    fun nonce(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private fun sign(token: String, prefix: String, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(prefix.toByteArray(Charsets.UTF_8))
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }
    fun request(token: String, path: String, timestamp: String, nonce: String, body: ByteArray): String =
        sign(token, "uktv-request-v1\nPOST\n$path\n$timestamp\n$nonce\n", body)
    fun response(token: String, nonce: String, status: Int, body: ByteArray): String =
        sign(token, "uktv-response-v1\n$nonce\n$status\n", body)
    fun validResponse(token: String, nonce: String, status: Int, body: ByteArray, signature: String?): Boolean =
        signature != null && Regex("^[a-f0-9]{64}$").matches(signature) && MessageDigest.isEqual(
            response(token, nonce, status, body).toByteArray(Charsets.US_ASCII), signature.toByteArray(Charsets.US_ASCII))
}
