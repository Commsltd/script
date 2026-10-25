package com.commercialoutcomes.uktelevision

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Refresh media addresses independently of the much larger programme/artwork download. */
object LiveSourceRules {
    private fun identity(s: StreamSource) = Triple(s.url, s.headers, s.kind)

    // A rotating URL may retain its source ID. A new URL may also acquire a new ID.
    // Neither case may delete the other, independent alternatives for this channel.
    fun merge(old: List<StreamSource>, fresh: List<StreamSource>): List<StreamSource> =
        (fresh + old.filter { previous -> fresh.none { it.id == previous.id } })
            .distinctBy { it.id }.distinctBy { identity(it) }

    fun changedIds(old: List<StreamSource>, fresh: List<StreamSource>): Set<String> =
        fresh.filter { candidate -> old.none { identity(it) == identity(candidate) } }
            .map { it.id }.toSet()

    fun remainingAttempts(tried: Set<String>, old: List<StreamSource>, fresh: List<StreamSource>): Set<String> =
        tried - changedIds(old, fresh)

    data class Parsed(val builtAt: Long, val channels: Map<String, List<StreamSource>>)
    fun parse(text: String, now: Long = System.currentTimeMillis()): Map<String, List<StreamSource>> = inventory(text, now).channels
    fun inventory(text: String, now: Long = System.currentTimeMillis()): Parsed {
        val document = JSONObject(text)
        val root = document.optJSONObject("liveSources") ?: document
        require(root.getInt("schemaVersion") == 1)
        // Never resurrect an arbitrarily old mirror, or accept a future-dated feed.
        require(now - root.getLong("builtAt") in -300000L..7 * 86400000L)
        val channels = root.getJSONArray("channels")
        require(channels.length() in 1..10000)
        val result = linkedMapOf<String, List<StreamSource>>()
        for (i in 0 until channels.length()) {
            val channel = channels.getJSONObject(i)
            val id = channel.getString("id")
            require(id.isNotBlank() && id.length <= 200 && !id.startsWith("user.") && id !in result)
            val entries = channel.getJSONArray("sources")
            require(entries.length() <= 512)
            val sources = parseSources(entries)
            require(sources.map { it.id }.distinct().size == sources.size)
            require(sources.all {
                val url = URI(it.url)
                it.id.isNotBlank() && it.id.length <= 200 && it.url.length <= 8192 &&
                    it.kind == "direct" && !it.unsupportedDrm && url.scheme in setOf("http", "https") &&
                    !url.host.isNullOrBlank() && url.userInfo == null && PublicStreamHttp.allowed(it.url)
            })
            result[id] = sources
        }
        return Parsed(root.getLong("builtAt"), result)
    }

    fun read(input: InputStream): String {
        val buffered = input.buffered()
        buffered.mark(2)
        val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val stream = if (gzip) GZIPInputStream(buffered) else buffered
        return stream.use {
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                if (bytes.size() + n > 6 * 1024 * 1024) throw IOException("Stream inventory too large")
                bytes.write(buffer, 0, n)
            }
            bytes.toString("UTF-8")
        }
    }
}

object LiveSourceRefresh {
    const val FEED = "https://raw.githubusercontent.com/Commsltd/script/uk-tv-app-data/status.json"
    private val lock = Mutex()
    private var lastAttempt = Long.MIN_VALUE
    private var etag: String? = null
    @Volatile var lastSuccess: Long = 0; private set
    @Volatile var lastError: String = ""; private set
    fun diagnostic(): String = if (lastSuccess == 0L) "Stream links: not refreshed this session${if (lastError.isBlank()) "" else " ($lastError)"}"
        else "Stream links last checked ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(java.util.Date(lastSuccess))}${if (lastError.isBlank()) "" else " · last refresh failed ($lastError)"}"
    private data class Inventory(val builtAt: Long, val channels: Map<String, List<StreamSource>>)
    @Volatile private var latest = Inventory(0, emptyMap())
    private val client by lazy {
        PublicVodHttp.client.newBuilder().callTimeout(6, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
    }

    fun cached(channelId: String, notBefore: Long = 0): List<StreamSource> {
        val current = latest
        return if (current.builtAt < notBefore || System.currentTimeMillis() - current.builtAt > 7 * 86400000L)
            emptyList() else current.channels[channelId].orEmpty()
    }

    suspend fun sources(channelId: String, notBefore: Long = 0): List<StreamSource> = withContext(Dispatchers.IO) {
        if (channelId.startsWith("user.")) return@withContext emptyList()
        lock.withLock {
            val tick = System.nanoTime() / 1000000
            // Shared across channels/previews: failures cannot create a download storm.
            if (lastAttempt == Long.MIN_VALUE || tick - lastAttempt >= 300000) {
                lastAttempt = tick
                try {
                    val request = Request.Builder().url(FEED).apply { etag?.let { header("If-None-Match", it) } }.build()
                    client.newCall(request).execute().use { response ->
                        if (response.code != 304 || latest.builtAt == 0L) {
                            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                            val text = response.body?.byteStream()?.let(LiveSourceRules::read)
                                ?: throw IOException("Empty stream inventory")
                            val parsed = LiveSourceRules.inventory(text)
                            if (parsed.builtAt >= latest.builtAt) latest = Inventory(parsed.builtAt, parsed.channels)
                            etag = response.header("ETag")
                        }
                        lastSuccess = System.currentTimeMillis(); lastError = ""
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { lastError = error.javaClass.simpleName /* Keep previous alternatives. */ }
            }
            cached(channelId, notBefore)
        }
    }
}
