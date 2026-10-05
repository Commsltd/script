package com.commercialoutcomes.uktelevision

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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
        try {
            download(zip)
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
            true
        } catch (_: Exception) {
            staging.deleteRecursively()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            false
        } finally {
            zip.delete()
        }
    }

    private fun download(target: File) {
        val connection = URL(FEED).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "UKTelevision/0.2")
        try {
            if (connection.responseCode != 200) {
                throw IOException("Artwork server returned HTTP ${connection.responseCode}")
            }
            if (connection.url.protocol.lowercase() != "https") {
                throw IOException("Artwork download downgraded from HTTPS")
            }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(32768)
            connection.inputStream.use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > MAX_DOWNLOAD) throw IOException("Artwork bundle too large")
                    out.write(buffer, 0, n)
                }
            }
            target.writeBytes(out.toByteArray())
        } finally {
            connection.disconnect()
        }
    }

    private fun extract(input: java.io.InputStream, directory: File) {
        var entries = 0
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !safeName.matches(entry.name)) {
                    zip.closeEntry()
                    continue
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
