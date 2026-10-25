package com.commercialoutcomes.uktelevision

import android.app.Application
import android.content.Context
import androidx.room.*
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.FilterInputStream
import java.io.InputStreamReader
import java.io.IOException
import java.io.InputStream
import java.io.File
import okhttp3.Request
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

@Entity(tableName = "stations")
data class Station(@PrimaryKey val id: String, val name: String, val family: String,
    val groupName: String, val sortOrder: Int, val logo: String, val variant: String,
    val sourcesJson: String, val officialUrl: String)

@Entity(tableName = "programmes", indices = [Index(value = ["channelId", "start"]), Index(value = ["stop"])])
data class Programme(@PrimaryKey val key: String, val channelId: String, val start: Long,
    val stop: Long, val title: String, val subtitle: String, val description: String,
    val details: String, val artwork: String)

@Entity(tableName = "favourites")
data class Favourite(@PrimaryKey val channelId: String)

@Entity(tableName = "stream_health", primaryKeys = ["streamId", "profile"])
data class StreamHealth(val streamId: String, val profile: String, val successes: Int = 0,
    val failures: Int = 0, val lastOk: Long = 0, val lastFailure: Long = 0, val lastError: String = "")

@Entity(tableName = "snapshot")
data class Snapshot(@PrimaryKey val id: Int = 1, val revision: String, val builtAt: Long,
    val fetchedAt: Long, val guideStart: Long, val guideEnd: Long, val descriptions: Int,
    val programmeCount: Int, val warnings: String)

data class StreamSource(val id: String, val url: String, val label: String, val host: String,
    val mime: String, val headers: Map<String, String>, val unsupportedDrm: Boolean,
    val kind: String = "direct", val livePage: String = "", val region: String = "",
    val guideChannelId: String = "", val priority: Int = 0, val backgroundCheck: Boolean = false)

fun Station.sources(): List<StreamSource> = parseSources(JSONArray(sourcesJson))
fun parseSources(array: JSONArray): List<StreamSource> = (0 until array.length()).map { index ->
    val obj = array.getJSONObject(index)
    val headers = obj.optJSONObject("headers") ?: JSONObject()
    StreamSource(obj.getString("id"), obj.getString("url"), obj.optString("label"),
        obj.optString("host"), obj.optString("mime", "application/x-mpegURL"),
        MediaRequestHeaders.sanitise(headers.keys().asSequence().take(32).associateWith { headers.getString(it) }), obj.optBoolean("unsupportedDrm"),
        obj.optString("kind", "direct"), obj.optString("livePage"), obj.optString("region"),
        obj.optString("guideChannelId"), obj.optInt("priority"), obj.optBoolean("backgroundCheck"))
}

@Dao
interface TvDao {
    @Query("SELECT * FROM stations ORDER BY sortOrder") fun stations(): Flow<List<Station>>
    @Query("SELECT * FROM programmes ORDER BY start") fun programmes(): Flow<List<Programme>>
    @Query("""SELECT * FROM programmes WHERE
        (stop > :windowFrom AND start < :windowTo) OR
        (stop > :liveFrom AND start < :liveTo) ORDER BY start""")
    fun windowProgrammes(windowFrom: Long, windowTo: Long, liveFrom: Long, liveTo: Long): Flow<List<Programme>>
    @Query("""SELECT p.* FROM programmes p INNER JOIN stations s ON s.id = p.channelId
        WHERE p.stop > :now AND (instr(lower(p.title), lower(:query)) > 0
        OR instr(lower(p.subtitle), lower(:query)) > 0
        OR instr(lower(p.description), lower(:query)) > 0)
        ORDER BY p.start LIMIT 80""")
    suspend fun searchProgrammes(query: String, now: Long): List<Programme>
    @Query("SELECT * FROM favourites") fun favourites(): Flow<List<Favourite>>
    @Query("SELECT * FROM snapshot WHERE id=1") fun snapshot(): Flow<Snapshot?>
    @Query("SELECT COUNT(*) FROM stations") suspend fun stationCount(): Int
    @Query("SELECT * FROM snapshot WHERE id=1") suspend fun currentSnapshot(): Snapshot?
    @Query("DELETE FROM stations") suspend fun clearStations()
    @Query("DELETE FROM programmes") suspend fun clearProgrammes()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putStations(stations: List<Station>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putProgrammeBatch(programmes: List<Programme>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProgrammes(programmes: List<Programme>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSnapshot(snapshot: Snapshot)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addFavourite(favourite: Favourite)
    @Query("DELETE FROM favourites WHERE channelId=:id") suspend fun removeFavourite(id: String)
    @Query("UPDATE OR REPLACE favourites SET channelId=:replacement WHERE channelId=:old")
    suspend fun migrateFavourite(old: String, replacement: String)
    @Query("SELECT * FROM stream_health WHERE profile=:profile") suspend fun health(profile: String): List<StreamHealth>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putHealth(health: StreamHealth)
}

@Database(entities = [Station::class, Programme::class, Favourite::class, StreamHealth::class, Snapshot::class], version = 1, exportSchema = false)
abstract class TvDatabase : RoomDatabase() { abstract fun dao(): TvDao }

class TvApplication : Application() {
    val database: TvDatabase by lazy { Room.databaseBuilder(this, TvDatabase::class.java, "uk-television.db").build() }
    val repository: TvRepository by lazy { TvRepository(this, database) }
    override fun onCreate() {
        super.onCreate()
        val job = PeriodicWorkRequestBuilder<GuideWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("tv-guide-refresh", ExistingPeriodicWorkPolicy.UPDATE, job)
    }
}

class GuideWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as TvApplication).repository
        return if (repo.refresh() == null) Result.success() else Result.retry()
    }
}

data class Catalogue(val stations: List<Station>, val programmes: List<Programme>, val snapshot: Snapshot,
    val aliases: Map<String, String> = emptyMap())

object CatalogueParser {
    private fun readValue(reader: JsonReader, depth: Int = 0): Any {
        require(depth <= 32) { "Catalogue nesting too deep" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().also { obj ->
                reader.beginObject()
                while (reader.hasNext()) {
                    require(obj.length() < 20000) { "Oversized JSON object" }
                    obj.put(reader.nextName(), readValue(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JSONArray().also { array ->
                reader.beginArray()
                while (reader.hasNext()) {
                    require(array.length() < 512) { "Oversized nested JSON array" }
                    array.put(readValue(reader, depth + 1))
                }
                reader.endArray()
            }
            JsonToken.STRING, JsonToken.NUMBER -> reader.nextString().also { require(it.length <= 65536) { "Oversized catalogue field" } }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
            else -> throw IOException("Invalid catalogue JSON")
        }
    }

    fun parse(input: InputStream, batch: ((List<Programme>) -> Unit)? = null): Catalogue {
        val buffered = input.buffered()
        buffered.mark(2)
        val first = buffered.read(); val second = buffered.read(); buffered.reset()
        val stream = if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered) else buffered
        // Read one record at a time: a whole UTF-16 guide plus its JSON tree
        // exhausted the 128 MB heap on Fire TV before programme info appeared.
        val root = JSONObject()
        val programmes = ArrayList<Programme>()
        val usedGuideIds = HashSet<String>()
        var count = 0
        var firstStart = Long.MAX_VALUE
        var lastStop = Long.MIN_VALUE
        var descriptions = 0
        val bounded = object : FilterInputStream(stream) {
            var total = 0L
            fun count(n: Int): Int {
                if (n > 0) total += n
                if (total > 48L * 1024 * 1024) throw IOException("Guide exceeds the safety size limit")
                return n
            }
            override fun read(): Int = super.read().also { if (it >= 0) count(1) }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                count(`in`.read(bytes, offset, length))
        }
        JsonReader(InputStreamReader(bounded, Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                when (name) {
                    "programmes", "regionalProgrammes" -> {
                        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); continue }
                        reader.beginArray()
                        while (reader.hasNext()) {
                            require(count < 300000) { "Oversized guide" }
                            val p = readValue(reader) as JSONObject
                            val programme = Programme(p.getString("key"), p.getString("channelId"),
                                p.getLong("start"), p.getLong("stop"), p.getString("title"),
                                p.optString("subtitle"), p.optString("description"), p.optString("details"),
                                p.optString("privacyArtwork").ifBlank { p.optString("artwork") })
                            require(programme.stop > programme.start && programme.title.isNotBlank()) { "Invalid programme data" }
                            usedGuideIds.add(programme.channelId)
                            require(usedGuideIds.size <= 20000) { "Too many guide identities" }
                            count++; firstStart = minOf(firstStart, programme.start); lastStop = maxOf(lastStop, programme.stop)
                            if (programme.description.isNotBlank()) descriptions++
                            programmes.add(programme)
                            if (batch != null && programmes.size == 250) { batch(programmes); programmes.clear() }
                        }
                        reader.endArray()
                    }
                    "channels" -> {
                        val channels = JSONArray()
                        reader.beginArray()
                        while (reader.hasNext()) {
                            require(channels.length() < 10000) { "Oversized channel list" }
                            channels.put(readValue(reader))
                        }
                        reader.endArray(); root.put(name, channels)
                    }
                    "schemaVersion", "channelAliases", "sourceRevision", "builtAt", "warnings" ->
                        root.put(name, readValue(reader))
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing catalogue data" }
        }
        require(root.getInt("schemaVersion") == 1) { "Unsupported catalogue version" }
        val cs = root.getJSONArray("channels")
        val stations = (0 until cs.length()).map { n ->
            val c = cs.getJSONObject(n)
            val sources = c.getJSONArray("sources")
            val livePage = c.optString("youtubeLivePage")
            if (livePage.isNotBlank() && parseSources(sources).none { it.kind == "youtube" }) {
                sources.put(JSONObject().put("id",c.getString("youtubeSourceId"))
                    .put("url",livePage).put("livePage",livePage).put("kind","youtube")
                    .put("label",c.optString("youtubeLabel")).put("host","www.youtube.com"))
            }
            require(sources.length() > 0) { "Channel has no sources" }
            parseSources(sources).forEach { require(it.url.startsWith("https://") || it.url.startsWith("http://")) }
            Station(c.getString("id"), c.getString("name"), c.getString("family"),
                c.getString("group"), c.getInt("order"),
                c.optString("privacyLogo").ifBlank { c.optString("logo") },
                c.optString("variant"), sources.toString(), c.optString("officialUrl"))
        }
        require(stations.isNotEmpty() && stations.size <= 10000) { "Empty or oversized channel list" }
        require(stations.map { it.id }.distinct().size == stations.size) { "Duplicate channel identities" }
        val ids = stations.map { it.id }.toSet()
        val aliasJson = root.optJSONObject("channelAliases") ?: JSONObject()
        val aliases = aliasJson.keys().asSequence().associateWith { aliasJson.getString(it) }.filterValues { it in ids }
        val guideIds = ids + aliases.keys + stations.flatMap { it.sources() }.map { it.guideChannelId }.filter { it.isNotBlank() }
        require(count > 0) { "Empty guide" }
        require(usedGuideIds.all { it in guideIds }) { "Invalid programme data" }
        if (batch != null && programmes.isNotEmpty()) { batch(programmes); programmes.clear() }
        val now = System.currentTimeMillis()
        return Catalogue(stations, programmes, Snapshot(revision = root.optString("sourceRevision"),
            builtAt = root.getLong("builtAt"), fetchedAt = now, guideStart = firstStart,
            guideEnd = lastStop, descriptions = descriptions,
            programmeCount = count, warnings = root.optJSONArray("warnings")?.join("\n") ?: ""), aliases)
    }
}

class TvRepository(private val context: Context, private val db: TvDatabase) {
    private val lock = Mutex()
    companion object {
        const val FEED = "https://raw.githubusercontent.com/Commsltd/script/uk-tv-app-data/catalogue.json.gz"
    }
    // Parse local bytes inside a WAL transaction. Readers retain the previous
    // snapshot until all validation and insertion succeed; rollback preserves it.
    suspend fun importCatalogue(input: InputStream, previous: Snapshot? = null): Catalogue {
        val oldCount = db.dao().stationCount()
        val data = db.withTransaction {
            db.dao().clearProgrammes()
            val parsed = CatalogueParser.parse(input) { db.dao().putProgrammeBatch(it) }
            require(previous == null || parsed.snapshot.guideEnd >= previous.guideEnd - 86400000L) { "Older guide rejected" }
            require(previous == null || parsed.stations.size >= oldCount / 2) { "Truncated channel list rejected" }
            db.dao().clearStations()
            db.dao().putStations(parsed.stations)
            db.dao().putSnapshot(parsed.snapshot)
            parsed.aliases.forEach { (old, replacement) -> db.dao().migrateFavourite(old, replacement) }
            parsed
        }
        val prefs = context.getSharedPreferences("preferences", 0)
        data.aliases[prefs.getString("lastChannel", "")]?.let { prefs.edit().putString("lastChannel", it).apply() }
        return data
    }
    suspend fun seed() = withContext(Dispatchers.IO) {
        lock.withLock {
            ArtworkStore.seed(context)
            if (db.dao().stationCount() == 0) {
                BundledCatalogue.open(context).use { importCatalogue(it) }
            }
        }
    }
    private suspend fun refreshArtwork(builtAt: Long) {
        val sync = context.getSharedPreferences("catalogue-sync", Context.MODE_PRIVATE)
        // Retry a failed bundle on the next refresh, including an HTTP 304.
        if (sync.getLong("artworkBuiltAt", -1L) != builtAt && ArtworkStore.refresh(context))
            sync.edit().putLong("artworkBuiltAt", builtAt).apply()
    }

    suspend fun refresh(skipRecent: Boolean = false): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            val previous = db.dao().currentSnapshot()
            if (skipRecent && previous != null &&
                System.currentTimeMillis() - previous.fetchedAt in 0 until 15 * 60 * 1000L) return@withLock null
            val sync = context.getSharedPreferences("catalogue-sync", Context.MODE_PRIVATE)
            val download = File.createTempFile("guide-", ".gz", context.cacheDir)
            try {
                val request = Request.Builder().url(FEED).header("User-Agent", "UKTelevision/${BuildConfig.VERSION_NAME}")
                if (previous != null) sync.getString("etag", null)?.let { request.header("If-None-Match", it) }
                val result = PublicVodHttp.client.newBuilder().callTimeout(90, TimeUnit.SECONDS).build()
                    .newCall(request.build()).execute().use { response ->
                        if (response.code == 304 && previous != null) {
                            db.dao().putSnapshot(previous.copy(fetchedAt = System.currentTimeMillis()))
                            previous
                        } else {
                            require(response.code == 200) { "Guide server returned HTTP ${response.code}" }
                            response.body?.byteStream()?.use { input ->
                                download.outputStream().buffered().use { output ->
                                    val buffer = ByteArray(32768); var total = 0L
                                    while (true) {
                                        val n = input.read(buffer); if (n < 0) break
                                        total += n; require(total <= 20L * 1024 * 1024) { "Compressed guide too large" }
                                        output.write(buffer, 0, n)
                                    }
                                }
                            } ?: throw IOException("Empty guide response")
                            val imported = download.inputStream().use { importCatalogue(it, previous) }
                            sync.edit().putString("etag", response.header("ETag")).apply()
                            imported.snapshot
                        }
                    }
                // The guide is committed and its programme batches released before artwork I/O.
                refreshArtwork(result.builtAt)
                null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { "Refresh failed (${e.javaClass.simpleName}); cached channels and guide retained." }
            finally { download.delete() }
        }
    }
}
