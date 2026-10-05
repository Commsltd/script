package com.commercialoutcomes.uktelevision

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

const val HALF_HOUR = 1800000L
const val WINDOW = HALF_HOUR * 5

enum class PrivacyMode(val label: String) {
    HARDENED("Hardened compatibility"),
    STRICT("Strict privacy")
}

data class GuideRow(val station: Station, val source: StreamSource, val sourceIndex: Int) {
    val key: String get() = station.id + ":" + source.id
    val label: String get() = when {
        source.kind == "youtube" -> "${station.name} · YouTube"
        source.kind == "web" -> "${station.name} · Official web"
        sourceIndex == 0 -> station.name
        else -> "${station.name} · Alt ${sourceIndex}"
    }
}

object GuideRules {
    fun at(shows: List<Programme>, time: Long): Programme? = shows.firstOrNull { it.start <= time && it.stop > time }
    fun canAutoFallback(a: Station, b: Station): Boolean = a.id == b.id
    fun inWindow(shows: List<Programme>, start: Long, end: Long) = shows.filter { it.stop > start && it.start < end }
    fun healthScore(h: StreamHealth?): Long {
        if (h == null) return 0
        val recentFailure = h.lastFailure > h.lastOk
        return (if (h.lastOk > 0) 1000L else 0L) + h.successes.coerceAtMost(30) * 10L -
            h.failures.coerceAtMost(30) * 5L - (if (recentFailure) 700L else 0L)
    }
}

class TvModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TvApplication
    val dao = app.database.dao()
    val prefs = application.getSharedPreferences("preferences", 0)
    private var baseStations = emptyList<Station>()
    private var importedStations = emptyList<Station>()
    private var sourceCache = emptyMap<String, List<StreamSource>>()

    var stations by mutableStateOf(emptyList<Station>()); private set
    var schedule by mutableStateOf(emptyMap<String, List<Programme>>()); private set
    var favourites by mutableStateOf(emptySet<String>()); private set
    var snapshot by mutableStateOf<Snapshot?>(null); private set
    var message by mutableStateOf("Loading your television guide…"); private set
    var refreshing by mutableStateOf(true); private set
    var group by mutableStateOf("01 MAIN UK")
    var selectedKey by mutableStateOf("")
    var railSelected by mutableStateOf(false)
    var windowStart by mutableLongStateOf(System.currentTimeMillis() / HALF_HOUR * HALF_HOUR)
    var cursor by mutableLongStateOf(System.currentTimeMillis())
    var clock by mutableLongStateOf(System.currentTimeMillis())
    var inlineSources by mutableStateOf(prefs.getBoolean("inlineSources", true)); private set
    var autoFallback by mutableStateOf(prefs.getBoolean("autoFallback", true)); private set
    private var connectionProfile by mutableStateOf(prefs.getString("profile", "UK VPN") ?: "UK VPN")
    val profile: String get() = connectionProfile
    var privacyMode by mutableStateOf(
        runCatching { PrivacyMode.valueOf(prefs.getString("privacyMode", PrivacyMode.HARDENED.name)!!) }
            .getOrDefault(PrivacyMode.HARDENED)
    ); private set
    val allowRemoteArtwork: Boolean get() = privacyMode != PrivacyMode.STRICT
    val allowExternalApps: Boolean get() = privacyMode != PrivacyMode.STRICT
    val allowCleartextVideo: Boolean get() = privacyMode != PrivacyMode.STRICT
    var userPlaylists by mutableStateOf(PlaylistImport.load(prefs)); private set

    var isPlayer by mutableStateOf(false)
    var playingRow by mutableStateOf<GuideRow?>(null)
    var previousRow: GuideRow? = null

    init {
        viewModelScope.launch {
            dao.stations().collect {
                baseStations = it
                rebuildStations()
            }
        }
        viewModelScope.launch { dao.programmes().collect { schedule = it.groupBy { p -> p.channelId } } }
        viewModelScope.launch { dao.favourites().collect { favourites = it.map { f -> f.channelId }.toSet(); ensureSelection() } }
        viewModelScope.launch { dao.snapshot().collect { snapshot = it } }
        viewModelScope.launch {
            try { app.repository.seed() } catch (_: Exception) { message = "Downloading your channel list…" }
            val core = app.repository.refresh()
            val extras = refreshUserPlaylistsInternal()
            message = extras ?: core ?: "Guide ready"
            refreshing = false
        }
    }

    private fun rebuildStations() {
        stations = PlaylistImport.merge(baseStations, importedStations)
        sourceCache = stations.associate { it.id to it.sources() }
        ensureSelection()
    }

    private suspend fun refreshUserPlaylistsInternal(): String? {
        if (userPlaylists.isEmpty()) {
            importedStations = emptyList()
            rebuildStations()
            return null
        }
        val result = PlaylistImport.refresh(userPlaylists, privacyMode == PrivacyMode.STRICT)
        importedStations = result.stations
        rebuildStations()
        return result.warnings.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    fun rows(): List<GuideRow> {
        val filtered = stations.filter {
            group == "ALL CHANNELS" ||
                (group == "FAVOURITES" && it.id in favourites) ||
                it.groupName == group
        }
        return filtered.flatMap { station ->
            val all = sourceCache[station.id] ?: station.sources()
            (if (inlineSources) all else all.take(1)).mapIndexed { index, source -> GuideRow(station, source, index) }
        }
    }

    fun groups(): List<String> = listOf("FAVOURITES", "ALL CHANNELS") + stations.map { it.groupName }.distinct()

    fun ensureSelection() {
        val rows = rows()
        if (rows.none { it.key == selectedKey }) {
            val last = prefs.getString("lastChannel", "")
            selectedKey = (rows.firstOrNull { it.station.id == last } ?: rows.firstOrNull())?.key ?: ""
        }
    }

    fun selectGroup(value: String) { group = value; ensureSelection() }
    fun selected(): GuideRow? = rows().firstOrNull { it.key == selectedKey } ?: rows().firstOrNull()
    fun highlighted(): Programme? = selected()?.let { GuideRules.at(schedule[it.station.id].orEmpty(), cursor) }

    fun moveRow(delta: Int) {
        val rows = rows(); if (rows.isEmpty()) return
        val index = rows.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
        selectedKey = rows[(index + delta).coerceIn(0, rows.lastIndex)].key
    }

    fun moveTime(delta: Int) {
        val list = selected()?.let { schedule[it.station.id] }.orEmpty()
        val next = if (delta > 0) list.firstOrNull { it.start > cursor }
        else list.lastOrNull { it.start < (highlighted()?.start ?: cursor) }
        cursor = next?.start ?: (cursor + delta * HALF_HOUR)
        val min = snapshot?.guideStart ?: clock - 86400000L
        val max = (snapshot?.guideEnd ?: clock + 7 * 86400000L) - 1
        cursor = cursor.coerceIn(min.coerceAtMost(max), max)
        if (cursor < windowStart || cursor >= windowStart + WINDOW) windowStart = cursor / HALF_HOUR * HALF_HOUR
    }

    fun jump(hours: Int) {
        cursor += hours * 3600000L
        val min = snapshot?.guideStart ?: clock
        val max = (snapshot?.guideEnd ?: clock + 86400000L) - 1
        cursor = cursor.coerceIn(min.coerceAtMost(max), max)
        windowStart = cursor / HALF_HOUR * HALF_HOUR
    }

    fun now() { clock = System.currentTimeMillis(); cursor = clock; windowStart = clock / HALF_HOUR * HALF_HOUR }

    fun toggleFavourite(station: Station) = viewModelScope.launch {
        if (station.id in favourites) dao.removeFavourite(station.id) else dao.addFavourite(Favourite(station.id))
    }

    fun setInline(enabled: Boolean) { inlineSources = enabled; prefs.edit().putBoolean("inlineSources", enabled).apply(); ensureSelection() }
    fun setFailover(enabled: Boolean) { autoFallback = enabled; prefs.edit().putBoolean("autoFallback", enabled).apply() }
    fun setProfile(value: String) { connectionProfile = value; prefs.edit().putString("profile", value).apply() }

    fun setPrivacyMode(mode: PrivacyMode) {
        privacyMode = mode
        prefs.edit().putString("privacyMode", mode.name).apply()
        viewModelScope.launch { message = refreshUserPlaylistsInternal() ?: "Privacy mode: ${mode.label}" }
    }

    fun addPlaylist(name: String, url: String) {
        val cleanName = name.trim().ifBlank { "Playlist ${userPlaylists.size + 1}" }.take(40)
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("https://") && !cleanUrl.startsWith("http://")) {
            message = "Playlist URL must begin with http:// or https://"; return
        }
        if (privacyMode == PrivacyMode.STRICT && !cleanUrl.startsWith("https://")) {
            message = "Strict Privacy rejected an unencrypted playlist URL."; return
        }
        userPlaylists = (userPlaylists + UserPlaylist(cleanName, cleanUrl)).distinctBy { it.url }
        PlaylistImport.save(prefs, userPlaylists)
        viewModelScope.launch { message = refreshUserPlaylistsInternal() ?: "Playlist added" }
    }

    fun removePlaylist(url: String) {
        userPlaylists = userPlaylists.filterNot { it.url == url }
        PlaylistImport.save(prefs, userPlaylists)
        viewModelScope.launch { message = refreshUserPlaylistsInternal() ?: "Playlist removed" }
    }

    fun refreshUserPlaylists() {
        viewModelScope.launch { message = refreshUserPlaylistsInternal() ?: "Additional playlists refreshed" }
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true; message = "Refreshing programme guide…"
        viewModelScope.launch {
            val core = app.repository.refresh()
            val extras = refreshUserPlaylistsInternal()
            message = extras ?: core ?: "Guide refreshed"
            refreshing = false
        }
    }

    fun markPlaying(row: GuideRow) {
        if (playingRow?.station?.id != row.station.id) previousRow = playingRow
        playingRow = row; isPlayer = true
        prefs.edit().putString("lastChannel", row.station.id).apply()
    }

    fun find(query: String): List<Pair<Station, Programme?>> {
        val q = query.trim(); if (q.length < 2) return emptyList()
        val channels = stations.filter { it.name.contains(q, true) }.take(20).map { it to null }
        val byId = stations.associateBy { it.id }
        val programmes = schedule.values.asSequence().flatten()
            .filter { it.stop > clock && (it.title.contains(q, true) || it.subtitle.contains(q, true) || it.description.contains(q, true)) }
            .sortedBy { it.start }.take(80).mapNotNull { p -> byId[p.channelId]?.let { it to p } }.toList()
        return channels + programmes
    }

    fun focus(station: Station, programme: Programme? = null) {
        group = station.groupName
        val row = rows().firstOrNull { it.station.id == station.id } ?: return
        selectedKey = row.key; railSelected = false
        if (programme == null) now() else { cursor = programme.start; windowStart = cursor / HALF_HOUR * HALF_HOUR }
    }
}
