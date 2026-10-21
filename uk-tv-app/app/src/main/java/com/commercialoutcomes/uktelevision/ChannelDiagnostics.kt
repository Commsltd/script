package com.commercialoutcomes.uktelevision

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

object ChannelDiagnostics {
    fun network(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return "Network: disconnected"
        val caps = cm.getNetworkCapabilities(active)
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        return "Network: connected · VPN ${if (vpn) "detected" else "not detected"}"
    }
    suspend fun check(source: StreamSource): String = withContext(Dispatchers.IO) {
        if (source.unsupportedDrm) return@withContext "Provider authentication required."
        if (source.kind == "youtube") {
            val page = YouTubeIdentity.livePage(source)
            val id = if (page.isBlank()) YouTubeIdentity.videoId(source.url)
                else runCatching { YouTubeIdentity.resolve(page) }.getOrNull()
            return@withContext if (id != null) "Current video found ($id). Playback must still be checked."
                else "No current YouTube live broadcast found."
        }
        try {
            val result = fetch(source.url, source.headers)
            if (!result.second.trimStart().startsWith("#EXTM3U") && source.mime.contains("mpegURL"))
                "The source returned an invalid video playlist."
            else if (source.mime.contains("mpegURL")) {
                val first = result.second.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                if (first == null) "The playlist contains no video entries."
                else {
                    val child = fetch(URL(URL(result.first), first).toString(), source.headers)
                    if (child.second.trimStart().startsWith("#EXTM3U")) {
                        val segment = child.second.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                        if (segment == null) return@withContext "The video playlist is empty."
                        fetch(URL(URL(child.first), segment).toString(), source.headers)
                    }
                    "Playlist and sample media reachable. Sustained playback has not been verified."
                }
            } else "Source reachable. Playback must still be checked."
        } catch (_: UnknownHostException) { RecoveryRules.explain("DNS_UNAVAILABLE") }
        catch (e: SourceResponseException) { "${RecoveryRules.explain("HTTP_${e.code}")} (HTTP ${e.code})" }
        catch (_: java.net.SocketTimeoutException) { "The source did not respond in time." }
        catch (_: Exception) { "The source could not be reached or returned an incomplete response." }
    }
    private class SourceResponseException(val code: Int): java.io.IOException()
    private fun fetch(url: String, headers: Map<String,String>): Pair<String,String> {
        require(url.startsWith("https://") || url.startsWith("http://"))
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "UKTelevision/${BuildConfig.VERSION_NAME}")
        headers.forEach { (k,v) -> c.setRequestProperty(k,v) }
        try {
            val status = c.responseCode
            if (status !in 200..299) throw SourceResponseException(status)
            val bytes = c.inputStream.use { input ->
                val buffer = ByteArray(65536); var total = 0
                while (total < buffer.size) { val n = input.read(buffer,total,buffer.size-total); if(n<0)break;total+=n }
                buffer.copyOf(total)
            }
            return c.url.toString() to bytes.toString(Charsets.UTF_8)
        } finally { c.disconnect() }
    }
}
