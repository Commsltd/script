package com.commercialoutcomes.uktelevision

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

data class SourceCheck(val checkedAt: Long, val outcome: String, val host: String = "", val country: String = "") {
    val sampled: Boolean get() = outcome == "MEDIA_REACHABLE"
    fun fresh(now: Long = System.currentTimeMillis()) = now - checkedAt in 0 until 300000L
}

object SourceAdvice {
    fun country(source: StreamSource): String {
        // Only known service routes. A channel's country suffix is not evidence
        // about a particular stream's restriction (FAST schedules vary by region).
        val host = runCatching { URL(source.url).host }.getOrDefault("")
        return if ((host.endsWith(".akamaized.net") || host.endsWith(".bbci.co.uk")) &&
            (host.contains("-uk-live") || host.contains("-uk.live."))) "United Kingdom" else ""
    }
    fun geoMessage(text: String): Boolean = Regex(
        "not available in (your|this) (country|region|location)|geo.?blocked|geographical restriction|outside the (uk|united kingdom)",
        RegexOption.IGNORE_CASE).containsMatchIn(text)

    fun explain(check: SourceCheck): String = when {
        check.sampled -> "Playlist and sample media reachable; sustained playback not yet verified."
        check.outcome == "MANIFEST_REACHABLE" -> "Manifest reachable; media playback has not been verified."
        check.outcome == "GEO_RESTRICTED" -> "The provider reports a location restriction." +
            if (check.country.isNotBlank()) " Expected VPN country: ${check.country}." else " The required country was not supplied."
        check.outcome == "HTTP_403" || check.outcome == "HTTP_401" ->
            "Access refused: this may be a login, expired link, location or VPN restriction." +
                if (check.country.isNotBlank()) " This feed is intended for ${check.country}; use that VPN region, but the cause is unconfirmed." else " The required country is unknown."
        check.outcome == "INVALID_MEDIA" -> "The address returned a webpage or invalid media instead of a video stream."
        check.outcome == "HTTP_DOWNGRADE" -> "Strict privacy blocked a redirect to unencrypted HTTP."
        check.outcome == "TIMEOUT" -> "The source did not respond in time."
        check.outcome == "AUTH_REQUIRED" -> "This source needs supported provider authentication."
        else -> RecoveryRules.explain(check.outcome)
    }
}

/** Bounded probes through this device's current network/VPN, never playback claims. */
class SourceChecks(private val context: Context) {
    private val prefs = context.getSharedPreferences("source-checks", Context.MODE_PRIVATE)
    fun route(profile: String): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        val vpn = active?.let { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
        return "$profile:${active?.networkHandle}:$vpn"
    }
    private fun key(source: StreamSource, route: String) = "$route:${source.id}"
    fun cached(source: StreamSource, route: String): SourceCheck? = runCatching {
        val j = JSONObject(prefs.getString(key(source, route), null) ?: return null)
        SourceCheck(j.getLong("time"), j.getString("outcome"), j.optString("host"), j.optString("country")).takeIf { it.fresh() }
    }.getOrNull()
    fun remember(source: StreamSource, route: String, check: SourceCheck) {
        val edit = prefs.edit()
        // Keep storage bounded; old network handles must not accumulate forever.
        if (prefs.all.size > 128) edit.clear()
        edit.putString(key(source, route), JSONObject().put("time", check.checkedAt)
            .put("outcome", check.outcome).put("host", check.host).put("country", check.country).toString()).apply()
    }

    suspend fun check(source: StreamSource, strict: Boolean): SourceCheck = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val country = SourceAdvice.country(source)
        try {
            if (source.unsupportedDrm) return@withContext SourceCheck(now, "AUTH_REQUIRED", country = country)
            var response = fetch(source.url, source.headers, strict, 262144)
            if (response.code !in 200..299) return@withContext result(response, now, country)
            val text = response.bytes.toString(Charsets.UTF_8).trimStart()
            if (text.startsWith("#EXTM3U")) {
                var playlist = text
                // Resolve one master level, choosing a low-bandwidth rendition to
                // avoid competing with the channel currently being watched.
                if (playlist.contains("#EXT-X-STREAM-INF")) {
                    val lines = playlist.lines().map { it.trim() }
                    val variants = lines.mapIndexedNotNull { i, line ->
                        if (!line.startsWith("#EXT-X-STREAM-INF:")) null else {
                            val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                            val rate = Regex("(?:^|,)BANDWIDTH=(\\d+)").find(line.substringAfter(':'))?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE
                            uri?.let { rate to it }
                        }
                    }
                    val child = variants.minByOrNull { it.first }?.second ?: throw InvalidMedia()
                    response = fetch(URL(URL(response.url), child).toString(), source.headers, strict, 262144)
                    if (response.code !in 200..299) return@withContext result(response, now, country)
                    playlist = response.bytes.toString(Charsets.UTF_8).trimStart()
                    if (!playlist.startsWith("#EXTM3U")) throw InvalidMedia()
                }
                if (playlist.contains("METHOD=SAMPLE-AES") || Regex("KEYFORMAT=\"(?!identity)").containsMatchIn(playlist))
                    return@withContext SourceCheck(now, "AUTH_REQUIRED", URL(response.url).host, country)
                val segments = playlist.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
                val segment = segments.getOrNull((segments.size - 2).coerceAtLeast(0)) ?: throw InvalidMedia()
                response = fetch(URL(URL(response.url), segment).toString(), source.headers, strict, 32768)
                if (response.code !in 200..299) return@withContext result(response, now, country)
                if (response.bytes.isEmpty() || response.bytes.toString(Charsets.UTF_8).trimStart().startsWith("<")) throw InvalidMedia()
                SourceCheck(now, "MEDIA_REACHABLE", URL(response.url).host, country)
            } else if (text.contains("<MPD") || source.mime == "video/mp2t" && response.bytes.firstOrNull() == 0x47.toByte()) {
                SourceCheck(now, "MANIFEST_REACHABLE", URL(response.url).host, country)
            } else if (SourceAdvice.geoMessage(text)) SourceCheck(now, "GEO_RESTRICTED", URL(response.url).host, country)
            else throw InvalidMedia()
        } catch (_: UnknownHostException) { SourceCheck(now, "DNS_UNAVAILABLE", country = country) }
        catch (_: java.net.SocketTimeoutException) { SourceCheck(now, "TIMEOUT", country = country) }
        catch (_: Downgrade) { SourceCheck(now, "HTTP_DOWNGRADE", country = country) }
        catch (_: InvalidMedia) { SourceCheck(now, "INVALID_MEDIA", country = country) }
        catch (_: java.io.IOException) { SourceCheck(now, "CONNECTION_FAILED", country = country) }
        catch (_: IllegalArgumentException) { SourceCheck(now, "INVALID_MEDIA", country = country) }
    }
    private fun result(r: Response, now: Long, country: String) = SourceCheck(now,
        if (SourceAdvice.geoMessage(r.bytes.toString(Charsets.UTF_8))) "GEO_RESTRICTED" else "HTTP_${r.code}", URL(r.url).host, country)
    private data class Response(val url: String, val code: Int, val bytes: ByteArray)
    private class InvalidMedia : java.io.IOException()
    private class Downgrade : java.io.IOException()
    private fun fetch(start: String, headers: Map<String, String>, strict: Boolean, cap: Int): Response {
        var url = URL(start)
        val originalHost = url.host
        repeat(5) {
            require(url.protocol in setOf("http", "https"))
            if (strict && url.protocol != "https") throw Downgrade()
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 3000; c.readTimeout = 3000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "UKTelevision/${BuildConfig.VERSION_NAME}")
            headers.forEach { (key, value) ->
                if (url.host == originalHost || key.lowercase() in setOf("user-agent", "referer", "origin")) c.setRequestProperty(key, value)
            }
            try {
                val code = c.responseCode
                if (code in setOf(301,302,303,307,308)) {
                    url = URL(url, c.getHeaderField("Location") ?: throw InvalidMedia())
                } else {
                    val stream = if (code in 200..299) c.inputStream else c.errorStream
                    val bytes = stream?.use { input ->
                        val buffer = ByteArray(cap); var total = 0
                        while (total < cap) { val n = input.read(buffer, total, cap-total); if (n < 0) break; total += n }
                        buffer.copyOf(total)
                    } ?: byteArrayOf()
                    return Response(url.toString(), code, bytes)
                }
            } finally { c.disconnect() }
        }
        throw InvalidMedia()
    }
}
