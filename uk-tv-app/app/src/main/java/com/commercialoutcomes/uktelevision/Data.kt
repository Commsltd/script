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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
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
        headers.keys().asSequence().associateWith { headers.getString(it) }, obj.optBoolean("unsupportedDrm"),
        obj.optString("kind", "direct"), obj.optString("livePage"), obj.optString("region"),
        obj.optString("guideChannelId"), obj.optInt("priority"), obj.optBoolean("backgroundCheck"))
}

@Dao
interface TvDao {
    @Query("SELECT * FROM stations ORDER BY sortOrder") fun stations(): Flow<List<Station>>
    @Query("SELECT * FROM programmes ORDER BY start") fun programmes(): Flow<List<Programme>>
    @Query("SELECT * FROM favourites") fun favourites(): Flow<List<Favourite>>
    @Query("SELECT * FROM snapshot WHERE id=1") fun snapshot(): Flow<Snapshot?>
    @Query("SELECT COUNT(*) FROM stations") suspend fun stationCount(): Int
    @Query("SELECT * FROM snapshot WHERE id=1") suspend fun currentSnapshot(): Snapshot?
    @Query("DELETE FROM stations") suspend fun clearStations()
    @Query("DELETE FROM programmes") suspend fun clearProgrammes()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putStations(stations: List<Station>)
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
    fun parse(input: InputStream): Catalogue {
        val buffered = input.buffered()
        buffered.mark(2)
        val first = buffered.read(); val second = buffered.read(); buffered.reset()
        val stream = if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered) else buffered
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(32768)
        stream.use { source ->
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                if (bytes.size() + n > 48 * 1024 * 1024) throw IOException("Guide exceeds the safety size limit")
                bytes.write(buffer, 0, n)
            }
        }
        val root = JSONObject(bytes.toString("UTF-8"))
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
        val ps = root.getJSONArray("programmes")
        val regional = root.optJSONArray("regionalProgrammes") ?: JSONArray()
        for (n in 0 until regional.length()) ps.put(regional.getJSONObject(n))
        require(ps.length() in 1..300000) { "Empty or oversized guide" }
        val programmes = (0 until ps.length()).map { n ->
            val p = ps.getJSONObject(n)
            Programme(p.getString("key"), p.getString("channelId"), p.getLong("start"),
                p.getLong("stop"), p.getString("title"), p.optString("subtitle"),
                p.optString("description"), p.optString("details"),
                p.optString("privacyArtwork").ifBlank { p.optString("artwork") })
        }
        require(programmes.all { it.channelId in guideIds && it.stop > it.start && it.title.isNotBlank() }) { "Invalid programme data" }
        val now = System.currentTimeMillis()
        return Catalogue(stations, programmes, Snapshot(revision = root.optString("sourceRevision"),
            builtAt = root.getLong("builtAt"), fetchedAt = now, guideStart = programmes.minOf { it.start },
            guideEnd = programmes.maxOf { it.stop }, descriptions = programmes.count { it.description.isNotBlank() },
            programmeCount = programmes.size, warnings = root.optJSONArray("warnings")?.join("\n") ?: ""), aliases)
    }
}

class TvRepository(private val context: Context, private val db: TvDatabase) {
    private val lock = Mutex()
    companion object {
        const val FEED = "https://raw.githubusercontent.com/Commsltd/script/uk-tv-app-data/catalogue.json.gz"
    }
    private suspend fun replace(data: Catalogue) {
        db.withTransaction {
            db.dao().clearProgrammes()
            db.dao().clearStations()
            db.dao().putStations(data.stations)
            data.programmes.chunked(500).forEach { db.dao().putProgrammes(it) }
            db.dao().putSnapshot(data.snapshot)
            data.aliases.forEach { (old, replacement) -> db.dao().migrateFavourite(old, replacement) }
        }
        val prefs = context.getSharedPreferences("preferences", 0)
        data.aliases[prefs.getString("lastChannel", "")]?.let { prefs.edit().putString("lastChannel", it).apply() }
    }
    suspend fun seed() = withContext(Dispatchers.IO) {
        lock.withLock {
            ArtworkStore.seed(context)
            if (db.dao().stationCount() == 0) {
                replace(BundledCatalogue.read(context))
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
            try {
                val connection = URL(FEED).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "UKTelevision/${BuildConfig.VERSION_NAME}")
                if (previous != null) sync.getString("etag", null)?.let { connection.setRequestProperty("If-None-Match", it) }
                try {
                    val code = connection.responseCode
                    if (connection.url.protocol.lowercase() != "https") {
                        throw IOException("Guide download downgraded from HTTPS")
                    }
                    if (code == 304 && previous != null) {
                        refreshArtwork(previous.builtAt)
                        db.dao().putSnapshot(previous.copy(fetchedAt = System.currentTimeMillis()))
                        return@withLock null
                    }
                    if (code != 200) throw IOException("Guide server returned HTTP $code")
                    val data = connection.inputStream.use { CatalogueParser.parse(it) }
                    require(previous == null || data.snapshot.guideEnd >= previous.guideEnd - 86400000L) { "Older guide rejected; keeping the cached guide" }
                    require(previous == null || data.stations.size >= db.dao().stationCount() / 2) { "Truncated channel list rejected" }
                    refreshArtwork(data.snapshot.builtAt)
                    if (previous?.builtAt == data.snapshot.builtAt && previous.revision == data.snapshot.revision)
                        db.dao().putSnapshot(previous.copy(fetchedAt = System.currentTimeMillis()))
                    else replace(data)
                    sync.edit().putString("etag", connection.getHeaderField("ETag")).apply()
                } finally { connection.disconnect() }
                null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { "Refresh failed (${e.javaClass.simpleName}); cached channels and guide retained." }
        }
    }
}
