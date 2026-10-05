package com.commercialoutcomes.uktelevision

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TvSurface { GUIDE, LIVE }
enum class LiveOverlay { NONE, INFO, QUICK_GUIDE }

/**
 * One focus owner for the remote. Dialogs temporarily take focus and explicitly
 * return it here on dismiss, avoiding the "closed a dialog and the remote died"
 * failure mode.
 */
private class RemoteKeyLayout(context: Context) : FrameLayout(context) {
    var onRemoteKey: ((KeyEvent) -> Boolean)? = null
    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        onRemoteKey?.invoke(event) == true || super.dispatchKeyEvent(event)
}

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private lateinit var model: TvModel
    private lateinit var engine: PlaybackEngine
    private lateinit var root: RemoteKeyLayout
    private val handler = Handler(Looper.getMainLooper())

    private var dialogOpen = false
    private var selectDownAt = 0L
    private var longPressTriggered = false
    private var overlayJob: Job? = null
    private var surface by mutableStateOf(TvSurface.GUIDE)
    private var liveOverlay by mutableStateOf(LiveOverlay.NONE)
    private var quickGuideKey by mutableStateOf("")
    private var resizeMode by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    private var youtubeSource by mutableStateOf<StreamSource?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        model = ViewModelProvider(this)[TvModel::class.java]
        engine = PlaybackEngine(this, model, lifecycleScope) { source ->
            enterYouTubeFallback(source)
        }
        onBackPressedDispatcher.addCallback(this) { back() }

        val compose = ComposeView(this).apply {
            setContent {
                Television(
                    model = model,
                    engine = engine,
                    youtubeSource = youtubeSource,
                    surface = surface,
                    liveOverlay = liveOverlay,
                    quickGuideKey = quickGuideKey,
                    resizeMode = resizeMode,
                    onOptions = { showOptions() },
                    onBack = { back() }
                )
            }
        }

        root = RemoteKeyLayout(this).apply {
            onRemoteKey = ::handleRemoteKey
            addView(
                compose,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        setContentView(root)
        root.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        val row = model.playingRow
        if (row != null && engine.player == null && youtubeSource == null) {
            startPlayback(row, fullscreen = surface == TvSurface.LIVE)
        }
    }

    override fun onStop() {
        overlayJob?.cancel()
        engine.close()
        youtubeSource = null
        super.onStop()
    }

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun present(builder: AlertDialog.Builder) {
        dialogOpen = true
        selectDownAt = 0L
        longPressTriggered = false
        val dialog = builder.create()
        dialog.setOnDismissListener {
            dialogOpen = false
            root.postDelayed({ root.requestFocus() }, 80)
        }
        dialog.show()
    }

    private fun scheduleOverlayHide(delayMs: Long = 6500) {
        overlayJob?.cancel()
        overlayJob = lifecycleScope.launch {
            delay(delayMs)
            if (surface == TvSurface.LIVE) liveOverlay = LiveOverlay.NONE
        }
    }

    private fun showInfo() {
        if (surface != TvSurface.LIVE) return
        liveOverlay = LiveOverlay.INFO
        scheduleOverlayHide()
    }

    private fun startPlayback(row: GuideRow, fullscreen: Boolean, force: Boolean = false) {
        model.markPlaying(row)
        quickGuideKey = row.station.id
        when (row.source.kind) {
            "youtube" -> {
                engine.close()
                youtubeSource = row.source
            }
            "direct" -> {
                youtubeSource = null
                engine.play(row, force)
            }
            else -> {
                toast("This source is not integrated into the TV player.")
                return
            }
        }
        surface = if (fullscreen) TvSurface.LIVE else TvSurface.GUIDE
        liveOverlay = if (fullscreen) LiveOverlay.INFO else LiveOverlay.NONE
        if (fullscreen) scheduleOverlayHide()
    }

    private fun enterYouTubeFallback(source: StreamSource) {
        val current = model.playingRow ?: return
        val sources = current.station.sources()
        val index = sources.indexOfFirst { it.id == source.id }.coerceAtLeast(0)
        val row = GuideRow(current.station, source, index)
        model.markPlaying(row)
        youtubeSource = source
        if (surface == TvSurface.LIVE) showInfo()
    }

    private fun activeRow(): GuideRow? =
        if (surface == TvSurface.LIVE) model.playingRow else model.selected()

    private fun liveRows(): List<GuideRow> = model.channelRows()

    private fun openQuickGuide(delta: Int = 0) {
        val rows = liveRows()
        if (rows.isEmpty()) return
        if (quickGuideKey.isBlank() || rows.none { it.station.id == quickGuideKey }) {
            quickGuideKey = model.playingRow?.station?.id ?: rows.first().station.id
        }
        if (delta != 0) {
            val index = rows.indexOfFirst { it.station.id == quickGuideKey }.coerceAtLeast(0)
            quickGuideKey = rows[(index + delta).coerceIn(0, rows.lastIndex)].station.id
        }
        liveOverlay = LiveOverlay.QUICK_GUIDE
        scheduleOverlayHide(10000)
    }

    private fun confirmQuickGuide() {
        val row = liveRows().firstOrNull { it.station.id == quickGuideKey } ?: return
        startPlayback(row, fullscreen = true)
        liveOverlay = LiveOverlay.INFO
        scheduleOverlayHide()
    }

    private fun tuneAdjacent(delta: Int) {
        val rows = liveRows()
        if (rows.isEmpty()) return
        val current = model.playingRow?.station?.id
        val index = rows.indexOfFirst { it.station.id == current }.coerceAtLeast(0)
        startPlayback(rows[(index + delta).coerceIn(0, rows.lastIndex)], fullscreen = true)
    }

    private fun previewSelected() {
        val row = model.selected() ?: return
        val now = System.currentTimeMillis()
        val programme = model.highlighted()
        if (programme != null && (programme.start > now || programme.stop <= now)) {
            showDetails()
            return
        }
        val alreadyPlaying = model.playingRow?.station?.id == row.station.id
        if (alreadyPlaying) {
            surface = TvSurface.LIVE
            showInfo()
        } else {
            startPlayback(row, fullscreen = false)
        }
    }

    private fun shortSelect() {
        when (surface) {
            TvSurface.LIVE -> when (liveOverlay) {
                LiveOverlay.QUICK_GUIDE -> confirmQuickGuide()
                LiveOverlay.INFO -> liveOverlay = LiveOverlay.NONE
                LiveOverlay.NONE -> showInfo()
            }
            TvSurface.GUIDE -> when (model.guideZone) {
                GuideZone.CATEGORIES -> model.chooseGuideZone(GuideZone.CHANNELS)
                GuideZone.CHANNELS -> previewSelected()
                GuideZone.PROGRAMMES -> previewSelected()
            }
        }
    }

    private fun back() {
        if (dialogOpen) return
        if (surface == TvSurface.LIVE) {
            if (liveOverlay != LiveOverlay.NONE) {
                liveOverlay = LiveOverlay.NONE
                return
            }
            surface = TvSurface.GUIDE
            model.alignGuideToPlaying()
            return
        }

        if (model.guideBack()) return

        if (model.playingRow != null) {
            surface = TvSurface.LIVE
            showInfo()
            return
        }

        present(
            AlertDialog.Builder(this)
                .setTitle("Leave UK Television?")
                .setPositiveButton("Exit") { _, _ -> finish() }
                .setNegativeButton("Keep watching", null)
        )
    }

    private fun handleRemoteKey(event: KeyEvent): Boolean {
        if (!::model.isInitialized || dialogOpen) return false
        val key = event.keyCode

        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    selectDownAt = SystemClock.elapsedRealtime()
                    longPressTriggered = false
                } else if (!longPressTriggered) {
                    longPressTriggered = true
                    showSources()
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) {
                val heldFor = if (selectDownAt > 0L)
                    SystemClock.elapsedRealtime() - selectDownAt else 0L
                val wasLong = longPressTriggered || heldFor >= 550L
                selectDownAt = 0L
                longPressTriggered = false
                if (wasLong) {
                    if (!dialogOpen) showSources()
                } else {
                    shortSelect()
                }
                return true
            }
            return true
        }

        val handled = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN
        )
        if (key !in handled) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true

        when (key) {
            KeyEvent.KEYCODE_MENU -> showOptions()
            KeyEvent.KEYCODE_INFO -> {
                if (surface == TvSurface.LIVE) showInfo() else showDetails()
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (surface == TvSurface.LIVE && youtubeSource == null) {
                    engine.pauseOrPlay()
                    showInfo()
                } else if (surface == TvSurface.GUIDE) {
                    model.selected()?.let { startPlayback(it, fullscreen = true) }
                }
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                model.previousRow?.let { startPlayback(it, fullscreen = surface == TvSurface.LIVE) }
            }
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (surface == TvSurface.LIVE) tuneAdjacent(-1) else model.moveRow(-1)
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (surface == TvSurface.LIVE) tuneAdjacent(1) else model.moveRow(1)
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (surface == TvSurface.GUIDE) model.jump(-6)
                else model.previousRow?.let { startPlayback(it, fullscreen = true) }
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (surface == TvSurface.GUIDE) model.jump(6) else showInfo()
            }
            KeyEvent.KEYCODE_PAGE_UP -> if (surface == TvSurface.GUIDE) repeat(6) { model.moveRow(-1) }
            KeyEvent.KEYCODE_PAGE_DOWN -> if (surface == TvSurface.GUIDE) repeat(6) { model.moveRow(1) }

            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val delta = if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                if (surface == TvSurface.LIVE) {
                    if (liveOverlay == LiveOverlay.QUICK_GUIDE) openQuickGuide(delta)
                    else openQuickGuide(delta)
                } else {
                    when (model.guideZone) {
                        GuideZone.CATEGORIES -> model.moveGroup(delta)
                        GuideZone.CHANNELS, GuideZone.PROGRAMMES -> model.moveRow(delta)
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (surface == TvSurface.LIVE) {
                    if (liveOverlay == LiveOverlay.QUICK_GUIDE) liveOverlay = LiveOverlay.NONE
                    else showInfo()
                } else model.guideLeft()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (surface == TvSurface.LIVE) {
                    if (liveOverlay == LiveOverlay.QUICK_GUIDE) confirmQuickGuide()
                    else showInfo()
                } else model.guideRight()
            }
        }
        return true
    }

    private fun showOptions() {
        val row = activeRow()
        val favouriteLabel = row?.let {
            if (it.station.id in model.favourites) "★ Remove favourite" else "☆ Add favourite"
        }

        val labels = if (surface == TvSurface.GUIDE) {
            buildList {
                if (favouriteLabel != null) add(favouriteLabel)
                add("Preview channel")
                add("Watch fullscreen")
                add("Stream sources")
                add("Programme details")
                add("Search")
                add("Categories")
                add("Back to now")
                add("Refresh guide")
                add("Settings")
            }
        } else {
            buildList {
                add("Open full guide")
                add("Quick guide")
                add("Programme info")
                if (favouriteLabel != null) add(favouriteLabel)
                add("Stream sources")
                if (model.previousRow != null) add("Previous channel")
                if (youtubeSource == null) {
                    add("Audio tracks")
                    add("Subtitles")
                    add("Aspect ratio")
                    add("Prefer this source")
                }
                add("Settings")
            }
        }

        present(
            AlertDialog.Builder(this)
                .setTitle(row?.station?.name ?: "Television options")
                .setItems(labels.toTypedArray()) { _, which ->
                    when (val choice = labels[which]) {
                        "☆ Add favourite", "★ Remove favourite" -> row?.let {
                            model.toggleFavourite(it.station)
                            toast(if (choice.startsWith("☆")) "Added to favourites" else "Removed from favourites")
                        }
                        "Preview channel" -> row?.let { startPlayback(it, fullscreen = false) }
                        "Watch fullscreen" -> row?.let { startPlayback(it, fullscreen = true) }
                        "Stream sources" -> handler.post { showSources() }
                        "Programme details", "Programme info" -> handler.post { showDetails() }
                        "Search" -> handler.post { showSearch() }
                        "Categories" -> model.chooseGuideZone(GuideZone.CATEGORIES)
                        "Back to now" -> model.now()
                        "Refresh guide" -> {
                            model.refresh()
                            toast("Refreshing without resetting favourites")
                        }
                        "Open full guide" -> {
                            surface = TvSurface.GUIDE
                            liveOverlay = LiveOverlay.NONE
                            model.alignGuideToPlaying()
                        }
                        "Quick guide" -> openQuickGuide()
                        "Previous channel" -> model.previousRow?.let { startPlayback(it, fullscreen = true) }
                        "Audio tracks" -> handler.post { showTracks(C.TRACK_TYPE_AUDIO) }
                        "Subtitles" -> handler.post { showTracks(C.TRACK_TYPE_TEXT) }
                        "Aspect ratio" -> handler.post { chooseAspect() }
                        "Prefer this source" -> {
                            engine.pinCurrent()
                            toast("Preferred for ${model.profile}")
                        }
                        "Settings" -> handler.post { showSettings() }
                    }
                }
        )
    }

    private fun showSources() {
        val row = activeRow() ?: return
        lifecycleScope.launch {
            val health = model.dao.health(model.profile).associateBy { it.streamId }
            val streams = row.station.sources()
            val labels = streams.map { s ->
                val h = health[s.id]
                val state = when {
                    s.kind == "youtube" -> "Official YouTube · in-app fallback"
                    s.kind != "direct" -> "Not integrated"
                    s.unsupportedDrm -> "Provider authentication required"
                    model.privacyMode == PrivacyMode.STRICT && s.url.startsWith("http://") ->
                        "Blocked in Strict Privacy"
                    h == null -> "Not tested on this route"
                    h.lastOk > h.lastFailure -> "Last playback succeeded"
                    else -> "Last attempt failed · ${h.lastError}"
                }
                "${s.label.ifBlank { s.kind }}\n${s.host} · $state"
            }
            present(
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("${row.station.name} — sources")
                    .setItems(labels.toTypedArray()) { _, which ->
                        val selected = GuideRow(row.station, streams[which], which)
                        startPlayback(selected, fullscreen = surface == TvSurface.LIVE, force = true)
                    }
                    .setNegativeButton("Close", null)
            )
        }
    }

    private fun showDetails() {
        val row = activeRow() ?: return
        val p = if (surface == TvSurface.LIVE)
            GuideRules.at(model.schedule[row.station.id].orEmpty(), System.currentTimeMillis())
        else model.highlighted()

        val text = if (p == null) {
            "No programme listings were supplied for this channel. Playback and listings are separate."
        } else {
            listOf(
                SimpleDateFormat("EEE d MMM", Locale.UK).format(Date(p.start)) +
                    "  " + formatTime(p.start) + "–" + formatTime(p.stop),
                p.subtitle,
                p.description.ifBlank { "No synopsis supplied for this programme." },
                p.details,
                if (p.start > System.currentTimeMillis() || p.stop <= System.currentTimeMillis())
                    "This is a listing. Watch live opens the current broadcast." else ""
            ).filter { it.isNotBlank() }.joinToString("\n\n")
        }

        present(
            AlertDialog.Builder(this)
                .setTitle(p?.title ?: row.station.name)
                .setMessage(text)
                .setPositiveButton("Watch live") { _, _ ->
                    startPlayback(row, fullscreen = true)
                }
                .setNeutralButton(
                    if (row.station.id in model.favourites) "★ Favourite" else "☆ Favourite"
                ) { _, _ -> model.toggleFavourite(row.station) }
                .setNegativeButton("Close", null)
        )
    }

    private fun showSearch() {
        val edit = EditText(this).apply {
            hint = "Channel, programme or subject"
            setSingleLine(true)
            setPadding(24, 16, 24, 16)
        }
        present(
            AlertDialog.Builder(this)
                .setTitle("Search your guide")
                .setView(edit)
                .setPositiveButton("Search") { _, _ ->
                    val results = model.find(edit.text.toString())
                    handler.post {
                        if (results.isEmpty()) {
                            toast("No matches in the cached guide")
                        } else {
                            val labels = results.map { (station, p) ->
                                if (p == null) station.name
                                else "${p.title}\n${station.name} · " +
                                    SimpleDateFormat("EEE HH:mm", Locale.UK).format(Date(p.start))
                            }
                            present(
                                AlertDialog.Builder(this)
                                    .setTitle("Search results")
                                    .setItems(labels.toTypedArray()) { _, n ->
                                        surface = TvSurface.GUIDE
                                        model.focus(results[n].first, results[n].second)
                                    }
                                    .setNegativeButton("Close", null)
                            )
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
        )
    }

    private fun showSettings() {
        val entries = arrayOf(
            "Privacy: ${model.privacyMode.label}",
            "Additional playlists: ${model.userPlaylists.size}",
            "Inline source variants: ${if (model.inlineSources) "ON" else "OFF"}",
            "Automatic same-channel fallback: ${if (model.autoFallback) "ALL SOURCES" else "OFF"}",
            "Connection profile: ${model.profile}",
            "About and diagnostics"
        )
        present(
            AlertDialog.Builder(this).setTitle("Settings").setItems(entries) { _, n ->
                when (n) {
                    0 -> handler.post { showPrivacy() }
                    1 -> handler.post { showPlaylists() }
                    2 -> model.setInline(!model.inlineSources)
                    3 -> model.setFailover(!model.autoFallback)
                    4 -> handler.post {
                        present(
                            AlertDialog.Builder(this)
                                .setTitle("Playback history profile — not a VPN switch")
                                .setItems(arrayOf("UK VPN", "Spain / no VPN", "Other route")) { _, i ->
                                    model.setProfile(arrayOf("UK VPN", "Spain / no VPN", "Other route")[i])
                                }
                        )
                    }
                    5 -> handler.post {
                        val s = model.snapshot
                        present(
                            AlertDialog.Builder(this)
                                .setTitle("UK Television ${BuildConfig.VERSION_NAME}")
                                .setMessage(
                                    "${model.stations.size} services\n" +
                                        "${s?.programmeCount ?: 0} programme entries\n" +
                                        "${s?.descriptions ?: 0} descriptions\n" +
                                        "${model.userPlaylists.size} additional playlists\n\n" +
                                        "Privacy: ${model.privacyMode.label}\n" +
                                        "Guide through: ${s?.guideEnd?.let { Date(it).toString() } ?: "not loaded"}\n" +
                                        "Source revision: ${s?.revision?.take(12) ?: "unknown"}\n\n" +
                                        "No analytics, advertising or account SDK. Favourites and stream-health data stay local."
                                )
                                .setPositiveButton("Close", null)
                        )
                    }
                }
            }
        )
    }

    private fun showPrivacy() {
        val labels = arrayOf(
            "Hardened compatibility — HTTPS preferred, HTTP fallback preserved",
            "Strict privacy — HTTPS-only direct streams; mirrored artwork stays local; YouTube remains in-app"
        )
        present(
            AlertDialog.Builder(this)
                .setTitle("Privacy mode")
                .setSingleChoiceItems(
                    labels,
                    if (model.privacyMode == PrivacyMode.STRICT) 1 else 0
                ) { dialog, which ->
                    model.choosePrivacyMode(
                        if (which == 1) PrivacyMode.STRICT else PrivacyMode.HARDENED
                    )
                    val row = model.playingRow
                    if (row != null && row.source.kind == "direct") {
                        engine.close()
                        startPlayback(row, fullscreen = surface == TvSurface.LIVE)
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
        )
    }

    private fun safePlaylistUrl(value: String): String {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return "Saved playlist"
        val host = uri.host ?: return "Saved playlist"
        val path = uri.path.orEmpty().take(80)
        return "${uri.scheme ?: "https"}://$host$path" +
            if (!uri.query.isNullOrBlank()) "?…" else ""
    }

    private fun showPlaylists() {
        val labels = mutableListOf("+ Add playlist", "Refresh all")
        labels += model.userPlaylists.map { "${it.name}\n${safePlaylistUrl(it.url)}" }
        present(
            AlertDialog.Builder(this)
                .setTitle("Additional playlists")
                .setItems(labels.toTypedArray()) { _, which ->
                    when (which) {
                        0 -> handler.post { addPlaylistDialog() }
                        1 -> model.refreshUserPlaylists()
                        else -> {
                            val item = model.userPlaylists[which - 2]
                            handler.post {
                                present(
                                    AlertDialog.Builder(this)
                                        .setTitle(item.name)
                                        .setMessage(safePlaylistUrl(item.url))
                                        .setPositiveButton("Remove") { _, _ ->
                                            model.removePlaylist(item.url)
                                        }
                                        .setNegativeButton("Keep", null)
                                )
                            }
                        }
                    }
                }
                .setNegativeButton("Close", null)
        )
    }

    private fun addPlaylistDialog() {
        val name = EditText(this).apply {
            hint = "Playlist name"
            setSingleLine(true)
            setPadding(24, 16, 24, 16)
        }
        present(
            AlertDialog.Builder(this)
                .setTitle("Add playlist")
                .setView(name)
                .setPositiveButton("Next") { _, _ ->
                    val chosen = name.text.toString()
                    handler.post {
                        val url = EditText(this).apply {
                            hint = "https://example.com/playlist.m3u"
                            setSingleLine(true)
                            setPadding(24, 16, 24, 16)
                        }
                        present(
                            AlertDialog.Builder(this)
                                .setTitle(chosen.ifBlank { "Playlist URL" })
                                .setView(url)
                                .setPositiveButton("Add") { _, _ ->
                                    model.addPlaylist(chosen, url.text.toString())
                                }
                                .setNegativeButton("Cancel", null)
                        )
                    }
                }
                .setNegativeButton("Cancel", null)
        )
    }

    private fun showTracks(type: Int) {
        val player = engine.player ?: return
        val tracks = player.currentTracks.groups
            .filter { it.type == type }
            .flatMap { group ->
                (0 until group.length)
                    .filter { group.isTrackSupported(it) }
                    .map { group to it }
            }
        val labels = tracks.map { (g, i) ->
            val f = g.getTrackFormat(i)
            listOfNotNull(f.label, f.language, f.codecs)
                .joinToString(" · ")
                .ifBlank { "Track ${i + 1}" }
        }.toMutableList()

        if (type == C.TRACK_TYPE_TEXT) labels.add(0, "Off")
        if (labels.isEmpty()) {
            toast("No selectable tracks supplied")
            return
        }

        present(
            AlertDialog.Builder(this)
                .setTitle(if (type == C.TRACK_TYPE_TEXT) "Subtitles" else "Audio tracks")
                .setItems(labels.toTypedArray()) { _, i ->
                    val parameters = player.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(type)
                    if (type == C.TRACK_TYPE_TEXT && i == 0) {
                        parameters.setTrackTypeDisabled(type, true)
                    } else {
                        val (group, index) = tracks[i - if (type == C.TRACK_TYPE_TEXT) 1 else 0]
                        parameters.setTrackTypeDisabled(type, false)
                            .setOverrideForType(
                                TrackSelectionOverride(group.mediaTrackGroup, index)
                            )
                    }
                    player.trackSelectionParameters = parameters.build()
                }
        )
    }

    private fun chooseAspect() = present(
        AlertDialog.Builder(this)
            .setTitle("Picture size")
            .setItems(arrayOf("Fit — preserve picture", "Zoom — crop edges", "Fill — stretch")) { _, index ->
                resizeMode = arrayOf(
                    AspectRatioFrameLayout.RESIZE_MODE_FIT,
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                    AspectRatioFrameLayout.RESIZE_MODE_FILL
                )[index]
            }
    )
}

fun formatTime(time: Long): String =
    SimpleDateFormat("HH:mm", Locale.UK).format(Date(time))
