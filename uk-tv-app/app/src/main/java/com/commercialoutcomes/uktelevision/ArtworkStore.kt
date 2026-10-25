package com.commercialoutcomes.uktelevision

import android.content.Context
import kotlinx.coroutines.CancellationException
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object ArtworkStore {
    const val PREFIX = "artwork://"
    private const val FEED =
        "https://raw.githubusercontent.com/Commsltd/script/uk-tv-app-data/artwork.zip"
    private const val MAX_DOWNLOAD = 80 * 1024 * 1024
    private const val MAX_EXTRACTED = 120L * 1024 * 1024
    private const val MAX_ENTRY = 5L * 1024 * 1024
    private const val MAX_ENTRIES = 4000
    private val safeName = Regex("^[a-f0-9]{32}\\.(?:webp|svg)$")

    fun model(context: Context, reference: String, mode: PrivacyMode): Any? {
        if (reference.startsWith(PREFIX)) {
            val name = reference.removePrefix(PREFIX)
            if (!safeName.matches(name)) return null
            val file = File(File(context.filesDir, "artwork"), name)
            return file.takeIf { it.isFile }
        }
        if (mode == PrivacyMode.STRICT) return null
        return reference.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    suspend fun seed(context: Context) = withContext(Dispatchers.IO) {
        val target = File(context.filesDir, "artwork")
        if (target.listFiles()?.isNotEmpty() == true) return@withContext
        val names = context.assets.list("").orEmpty()
        if ("artwork.zip" !in names) return@withContext
        target.mkdirs()
        context.assets.open("artwork.zip").use { extract(it, target) }
    }

    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        val zip = File(context.cacheDir, "artwork-download.zip")
        val staging = File(context.filesDir, "artwork-new")
        val target = File(context.filesDir, "artwork")
        val backup = File(context.filesDir, "artwork-old")
        val prefs = context.getSharedPreferences("artwork-sync", Context.MODE_PRIVATE)
        try {
            val hash = remoteHash()
            val existing = target.listFiles()?.isNotEmpty() == true
            if (existing && hash != null && hash == prefs.getString("hash", null)) return@withContext true
            val (changed, etag) = download(zip, prefs.getString("etag", null).takeIf { existing })
            if (!changed) return@withContext true
            if (hash != null) {
                val digest = MessageDigest.getInstance("SHA-256")
                zip.inputStream().use { input ->
                    val bytes = ByteArray(32768)
                    while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
                }
                require(digest.digest().joinToString("") { "%02x".format(it) } == hash) { "Artwork changed during download" }
            }
            staging.deleteRecursively()
            staging.mkdirs()
            zip.inputStream().use { extract(it, staging) }
            require(staging.listFiles()?.isNotEmpty() == true) { "Empty artwork bundle" }

            backup.deleteRecursively()
            if (target.exists() && !target.renameTo(backup)) {
                throw IOException("Could not stage existing artwork")
            }
            if (!staging.renameTo(target)) {
                backup.renameTo(target)
                throw IOException("Could not activate artwork bundle")
            }
            backup.deleteRecursively()
            prefs.edit().putString("etag", etag).putString("hash", hash).apply()
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            staging.deleteRecursively()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            false
        } finally {
            zip.delete()
        }
    }

    private fun remoteHash(): String? = try {
        val request = Request.Builder().url(FEED.replace("artwork.zip", "artwork-status.json")).build()
        PublicVodHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.byteStream()?.use {
                JSONObject(it.readBounded(65536).toString(Charsets.UTF_8)).optString("bundleSha256")
                    .takeIf { hash -> Regex("[a-f0-9]{64}").matches(hash) }
            }
        }
    } catch (_: IOException) { null }

    private fun download(target: File, previousEtag: String?): Pair<Boolean, String?> {
        val request = Request.Builder().url(FEED).header("User-Agent", "UKTelevision/${BuildConfig.VERSION_NAME}")
        previousEtag?.let { request.header("If-None-Match", it) }
        return PublicVodHttp.client.newBuilder().callTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build()
            .newCall(request.build()).execute().use { response ->
                if (response.code == 304 && previousEtag != null) return@use false to previousEtag
                if (response.code != 200) throw IOException("Artwork HTTP ${response.code}")
                var total = 0L
                val buffer = ByteArray(32768)
                target.outputStream().buffered().use { output ->
                    val input = response.body?.byteStream() ?: throw IOException("Empty artwork response")
                    input.use {
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            total += n; if (total > MAX_DOWNLOAD) throw IOException("Artwork bundle too large")
                            output.write(buffer, 0, n)
                        }
                    }
                }
                true to response.header("ETag")
            }
    }

    internal fun extract(input: java.io.InputStream, directory: File) {
        var entries = 0
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !safeName.matches(entry.name)) {
                    // closeEntry drains the entire inflated entry, bypassing our size budget.
                    throw IOException("Unexpected artwork archive entry")
                }
                if (++entries > MAX_ENTRIES) throw IOException("Too many artwork files")
                val file = File(directory, entry.name)
                var written = 0L
                file.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        written += n
                        total += n
                        if (written > MAX_ENTRY || total > MAX_EXTRACTED) {
                            throw IOException("Artwork archive exceeds safety limits")
                        }
                        output.write(buffer, 0, n)
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
