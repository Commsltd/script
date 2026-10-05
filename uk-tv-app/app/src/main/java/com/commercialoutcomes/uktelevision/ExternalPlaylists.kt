package com.commercialoutcomes.uktelevision

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class UserPlaylist(val name: String, val url: String)
data class PlaylistRefresh(val stations: List<Station>, val warnings: List<String>)

private object LocalSecretBox {
    private const val ALIAS = "uk_television_local_secrets_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun seal(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "v1:$iv:$encrypted"
    }

    fun open(value: String): String {
        val parts = value.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == "v1")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP))
        )
        return String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), Charsets.UTF_8)
    }
}

object PlaylistImport {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")
    private const val KEY = "userPlaylistsEncrypted"
    private const val LEGACY_KEY = "userPlaylists"
    private const val MAX_PLAYLIST_BYTES = 8 * 1024 * 1024

    fun load(prefs: SharedPreferences): List<UserPlaylist> = runCatching {
        val encrypted = prefs.getString(KEY, null)
        val raw = if (encrypted != null) LocalSecretBox.open(encrypted)
            else prefs.getString(LEGACY_KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) null
            else UserPlaylist(o.optString("name").ifBlank { "Playlist ${i + 1}" }.take(40), url)
        }
    }.getOrDefault(emptyList())

    fun save(prefs: SharedPreferences, playlists: List<UserPlaylist>) {
        val array = JSONArray()
        playlists.forEach { p -> array.put(JSONObject().put("name", p.name).put("url", p.url)) }
        prefs.edit()
            .putString(KEY, LocalSecretBox.seal(array.toString()))
            .remove(LEGACY_KEY)
            .apply()
    }

    suspend fun refresh(playlists: List<UserPlaylist>, strict: Boolean): PlaylistRefresh =
        withContext(Dispatchers.IO) {
            val stations = mutableListOf<Station>()
            val warnings = mutableListOf<String>()
            playlists.forEach { config ->
                try {
                    if (strict && !config.url.startsWith("https://")) {
                        warnings += "${config.name}: HTTP playlist blocked by Strict Privacy"
                    } else {
                        stations += parse(config, download(config.url), strict)
                    }
                } catch (e: Exception) {
                    warnings += "${config.name}: ${e.javaClass.simpleName}"
                }
            }
            PlaylistRefresh(stations, warnings)
        }

    private fun download(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 20000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "UKTelevision/0.2")
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(32768)
            connection.inputStream.use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > MAX_PLAYLIST_BYTES) throw IOException("Playlist exceeds 8 MB safety limit")
                    out.write(buffer, 0, n)
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    fun parse(config: UserPlaylist, text: String, strict: Boolean): List<Station> {
        val result = mutableListOf<Station>()
        var extinf: String? = null
        val headers = mutableMapOf<String, String>()
        var order = 0

        fun delimiter(line: String): Int {
            var quoted = false
            line.forEachIndexed { index, char ->
                if (char == '"') quoted = !quoted
                else if (char == ',' && !quoted) return index
            }
            return line.length
        }

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:", true) -> {
                    extinf = line
                    headers.clear()
                }
                extinf != null && line.startsWith("#EXTVLCOPT:", true) -> {
                    val body = line.substringAfter(':')
                    val key = body.substringBefore('=').lowercase()
                    val value = body.substringAfter('=', "")
                    val mapped = when (key) {
                        "http-user-agent" -> "User-Agent"
                        "http-referrer", "http-referer" -> "Referer"
                        "http-origin" -> "Origin"
                        else -> null
                    }
                    if (mapped != null && '\r' !in value && '\n' !in value) headers[mapped] = value
                }
                extinf != null && line.isNotBlank() && !line.startsWith("#") -> {
                    val meta = extinf!!
                    val at = delimiter(meta)
                    val attrs = attr.findAll(meta.substring(0, at)).associate { it.groupValues[1] to it.groupValues[2] }
                    val name = meta.substring((at + 1).coerceAtMost(meta.length)).trim().ifBlank { "Unnamed channel" }
                    val rawUrl = line.substringBefore('|').trim()
                    val suffix = line.substringAfter('|', "")
                    suffix.split('&').forEach { pair ->
                        val key = pair.substringBefore('=').lowercase()
                        val value = runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
                        val mapped = when (key) {
                            "user-agent" -> "User-Agent"
                            "referer", "referrer" -> "Referer"
                            "origin" -> "Origin"
                            else -> null
                        }
                        if (mapped != null && '\r' !in value && '\n' !in value) headers[mapped] = value
                    }

                    if (rawUrl.startsWith("https://") || (rawUrl.startsWith("http://") && !strict)) {
                        val host = runCatching { URL(rawUrl).host }.getOrDefault("")
                        val kind = if (host.endsWith("youtube.com") || host == "youtu.be") "youtube" else "direct"
                        val path = runCatching { URL(rawUrl).path.lowercase() }.getOrDefault("")
                        val mime = when {
                            kind != "direct" -> "application/x-external"
                            path.endsWith(".mpd") -> "application/dash+xml"
                            path.endsWith(".ts") -> "video/mp2t"
                            path.endsWith(".mp4") -> "video/mp4"
                            else -> "application/x-mpegURL"
                        }
                        val originalId = attrs["tvg-id"]?.trim().orEmpty()
                        val id = if (originalId.isNotBlank()) originalId
                            else "user.${shortHash(config.url)}.${shortHash(name + rawUrl)}"
                        val headerJson = JSONObject()
                        headers.forEach { (k, v) -> headerJson.put(k, v) }
                        val source = JSONObject()
                            .put("id", shortHash(rawUrl + headerJson.toString()))
                            .put("url", rawUrl)
                            .put("label", if (kind == "youtube") "YouTube" else name)
                            .put("host", host)
                            .put("mime", mime)
                            .put("headers", headerJson)
                            .put("unsupportedDrm", false)
                            .put("kind", kind)

                        result += Station(
                            id = id,
                            name = name,
                            family = id.substringBefore('@'),
                            groupName = "PLAYLIST · ${config.name}",
                            sortOrder = 1_000_000 + order++,
                            logo = attrs["tvg-logo"].orEmpty(),
                            variant = id.substringAfter('@', ""),
                            sourcesJson = JSONArray().put(source).toString(),
                            officialUrl = ""
                        )
                    }
                    extinf = null
                    headers.clear()
                }
            }
        }
        return merge(emptyList(), result)
    }

    fun merge(base: List<Station>, imported: List<Station>): List<Station> {
        val map = linkedMapOf<String, Station>()
        base.forEach { map[it.id] = it }
        imported.forEach { incoming ->
            val existing = map[incoming.id]
            if (existing == null) {
                map[incoming.id] = incoming
            } else {
                val combined = JSONArray()
                val seen = mutableSetOf<String>()
                listOf(existing.sourcesJson, incoming.sourcesJson).forEach { json ->
                    val array = JSONArray(json)
                    for (i in 0 until array.length()) {
                        val source = array.getJSONObject(i)
                        if (seen.add(source.getString("id"))) combined.put(source)
                    }
                }
                map[incoming.id] = existing.copy(
                    logo = existing.logo.ifBlank { incoming.logo },
                    sourcesJson = combined.toString()
                )
            }
        }
        return map.values.toList()
    }

    private fun shortHash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }
}
