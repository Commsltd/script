package com.commercialoutcomes.uktelevision

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

/** Intercept remote keys through the public View API, not AndroidX's restricted activity shim. */
private class RemoteKeyLayout(context: Context) : FrameLayout(context) {
    var onRemoteKey: ((KeyEvent) -> Boolean)? = null
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        onRemoteKey?.invoke(event) == true || super.dispatchKeyEvent(event)
}

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private lateinit var model: TvModel
    private lateinit var engine: PlaybackEngine
    private val handler = Handler(Looper.getMainLooper())
    private var held = false
    private var dialogOpen = false
    private var hudJob: Job? = null
    private var hud by mutableStateOf(true)
    private var resizeMode by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    private val holdAction = Runnable { held = true; showSources() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        model = ViewModelProvider(this)[TvModel::class.java]
        engine = PlaybackEngine(this, model, lifecycleScope)
        onBackPressedDispatcher.addCallback(this) { back() }
        val compose = ComposeView(this).apply {
            setContent {
                Television(model, engine, hud, resizeMode,
                    onWatch = { watch(it) }, onOptions = { showOptions() }, onBack = { back() })
            }
        }
        setContentView(RemoteKeyLayout(this).apply {
            onRemoteKey = ::handleRemoteKey
            addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
    }
    override fun onStart() {
        super.onStart()
        if (::engine.isInitialized && model.isPlayer && engine.player == null) model.playingRow?.let { watch(it) }
    }
    override fun onStop() {
        handler.removeCallbacks(holdAction)
        if (::engine.isInitialized) engine.close()
        super.onStop()
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun present(builder: AlertDialog.Builder) {
        dialogOpen = true
        val dialog = builder.create()
        dialog.setOnDismissListener { dialogOpen = false }
        dialog.show()
    }
    private fun showHud() {
        hud = true; hudJob?.cancel()
        hudJob = lifecycleScope.launch { delay(7000); hud = false }
    }
    private fun watch(row: GuideRow, force: Boolean = false) {
        if (row.source.kind != "direct") {
            openExternalSource(row.source)
            return
        }
        showHud()
        engine.play(row, force)
    }
    private fun activeRow() = if (model.isPlayer) model.playingRow else model.selected()
    private fun back() {
        if (model.isPlayer) { engine.close(); model.isPlayer = false; model.playingRow?.let { model.focus(it.station) }; return }
        if (model.railSelected) { model.railSelected = false; return }
        present(AlertDialog.Builder(this).setTitle("Leave UK Television?")
            .setPositiveButton("Exit") { _, _ -> finish() }.setNegativeButton("Keep watching", null))
    }
    private fun handleRemoteKey(event: KeyEvent): Boolean {
        if (!::model.isInitialized || dialogOpen) return false
        val key = event.keyCode
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                held = false; handler.postDelayed(holdAction, 650)
            } else if (event.action == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(holdAction)
                if (!held) {
                    if (model.isPlayer) { engine.pauseOrPlay(); showHud() }
                    else if (model.railSelected) model.railSelected = false
                    else {
                        val programme = model.highlighted(); val now = System.currentTimeMillis()
                        if (programme != null && (programme.start > now || programme.stop <= now)) showDetails()
                        else model.selected()?.let { watch(it) }
                    }
                }
            }
            return true
        }
        val handled = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_INFO)
        if (key !in handled) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (key) {
            KeyEvent.KEYCODE_MENU -> showOptions()
            KeyEvent.KEYCODE_INFO -> if (model.isPlayer) showHud() else showDetails()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (model.isPlayer) { engine.pauseOrPlay(); showHud() } else model.selected()?.let { watch(it) }
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> model.previousRow?.let { watch(it) }
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (model.isPlayer) model.previousRow?.let { watch(it) } else model.jump(-6)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (model.isPlayer) engine.player?.seekToDefaultPosition() else model.jump(6)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                val delta = if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_CHANNEL_UP) -1 else 1
                if (model.isPlayer) {
                    val rows = model.rows(); val index = rows.indexOfFirst { it.key == model.playingRow?.key }.coerceAtLeast(0)
                    if (rows.isNotEmpty()) watch(rows[(index + delta).coerceIn(0, rows.lastIndex)])
                } else if (model.railSelected) {
                    val groups = model.groups(); val i = groups.indexOf(model.group).coerceAtLeast(0)
                    model.selectGroup(groups[(i + delta).coerceIn(0, groups.lastIndex)])
                } else model.moveRow(delta)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (model.isPlayer) showHud()
                else if (!model.railSelected) {
                    if (model.cursor <= model.windowStart || (model.highlighted()?.start ?: model.cursor) <= model.windowStart) model.railSelected = true
                    else model.moveTime(-1)
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (model.isPlayer) showHud()
                else if (model.railSelected) model.railSelected = false else model.moveTime(1)
            }
        }
        return true
    }
    private fun showOptions() {
        val row = activeRow()
        val labels = mutableListOf("Programme details", "Stream sources", "Search", "Categories", "Back to now", "Refresh guide", "Settings")
        if (row != null) labels.add(0, if (row.station.id in model.favourites) "Remove favourite" else "Add favourite")
        if (model.isPlayer) labels.addAll(listOf("Audio tracks", "Subtitles", "Aspect ratio", "Prefer this source", "Previous channel"))
        if (!row?.station?.officialUrl.isNullOrBlank()) labels.add("Open official YouTube stream")
        present(AlertDialog.Builder(this).setTitle("Television options").setItems(labels.toTypedArray()) { _, which ->
            when (labels[which]) {
                "Add favourite", "Remove favourite" -> row?.let { model.toggleFavourite(it.station) }
                "Programme details" -> handler.post { showDetails() }
                "Stream sources" -> handler.post { showSources() }
                "Search" -> handler.post { showSearch() }
                "Categories" -> { engine.close(); model.isPlayer = false; model.railSelected = true }
                "Back to now" -> { model.now(); engine.player?.seekToDefaultPosition() }
                "Refresh guide" -> { model.refresh(); toast("Refreshing without resetting favourites") }
                "Settings" -> handler.post { showSettings() }
                "Audio tracks" -> handler.post { showTracks(C.TRACK_TYPE_AUDIO) }
                "Subtitles" -> handler.post { showTracks(C.TRACK_TYPE_TEXT) }
                "Aspect ratio" -> handler.post { chooseAspect() }
                "Prefer this source" -> { engine.pinCurrent(); toast("Preferred for ${model.profile}") }
                "Previous channel" -> model.previousRow?.let { watch(it) }
                "Open official YouTube stream" -> row?.station?.officialUrl?.let { openOfficial(it) }
            }
        })
    }
    private fun showSources() {
        val row = activeRow() ?: return
        lifecycleScope.launch {
            val health = model.dao.health(model.profile).associateBy { it.streamId }
            val streams = row.station.sources()
            val labels = streams.map { s ->
                val h = health[s.id]
                val state = when {
                    s.kind != "direct" -> "Official external source · opens another app/site"
                    s.unsupportedDrm -> "Provider authentication required"
                    model.privacyMode == PrivacyMode.STRICT && s.url.startsWith("http://") ->
                        "Blocked by Strict Privacy: unencrypted HTTP"
                    h == null -> "Not tested on this profile"
                    h.lastOk > h.lastFailure -> "Last playback succeeded"
                    else -> "Last attempt failed: ${h.lastError}"
                }
                "${s.label.ifBlank { s.kind }}\n${s.host} · $state"
            }
            present(AlertDialog.Builder(this@MainActivity).setTitle("${row.station.name} — sources")
                .setItems(labels.toTypedArray()) { _, which -> watch(GuideRow(row.station, streams[which], which), true) }
                .setNegativeButton("Close", null))
        }
    }
    private fun showDetails() {
        val row = activeRow() ?: return
        val p = if (model.isPlayer) GuideRules.at(model.schedule[row.station.id].orEmpty(), System.currentTimeMillis()) else model.highlighted()
        val text = if (p == null) "No programme listings were supplied for this channel. Playback and listings are separate."
        else listOf(SimpleDateFormat("EEE d MMM",Locale.UK).format(Date(p.start))+"  "+formatTime(p.start) + "–" + formatTime(p.stop), p.subtitle, p.description.ifBlank { "No synopsis supplied for this programme." }, p.details,
            if (p.start > System.currentTimeMillis() || p.stop <= System.currentTimeMillis()) "This is a listing only. Watch live opens the current broadcast, not this past/future programme." else "")
            .filter { it.isNotBlank() }.joinToString("\n\n")
        present(AlertDialog.Builder(this).setTitle(p?.title ?: row.station.name).setMessage(text)
            .setPositiveButton("Watch live") { _, _ -> watch(row) }.setNegativeButton("Close", null))
    }
    private fun showSearch() {
        val edit = EditText(this).apply { hint = "Channel, programme or subject"; setSingleLine(true); setPadding(24,16,24,16) }
        present(AlertDialog.Builder(this).setTitle("Search your guide").setView(edit)
            .setPositiveButton("Search") { _, _ ->
                val results = model.find(edit.text.toString())
                handler.post {
                    if (results.isEmpty()) toast("No matches in the cached guide") else {
                        val labels = results.map { (station, p) -> if (p == null) station.name else "${p.title}\n${station.name} · ${SimpleDateFormat("EEE HH:mm",Locale.UK).format(Date(p.start))}" }
                        present(AlertDialog.Builder(this).setTitle("Search results").setItems(labels.toTypedArray()) { _, n ->
                            engine.close(); model.isPlayer = false; model.focus(results[n].first, results[n].second)
                        }.setNegativeButton("Close",null))
                    }
                }
            }.setNegativeButton("Cancel", null))
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
        present(AlertDialog.Builder(this).setTitle("Settings").setItems(entries) { _, n ->
            when (n) {
                0 -> handler.post { showPrivacy() }
                1 -> handler.post { showPlaylists() }
                2 -> model.setInline(!model.inlineSources)
                3 -> model.setFailover(!model.autoFallback)
                4 -> handler.post {
                    present(AlertDialog.Builder(this)
                        .setTitle("Playback history profile — not a VPN switch")
                        .setItems(arrayOf("UK VPN", "Spain / no VPN", "Other route")) { _, i ->
                            engine.close(); model.isPlayer = false
                            model.setProfile(arrayOf("UK VPN", "Spain / no VPN", "Other route")[i])
                        })
                }
                5 -> handler.post {
                    val s = model.snapshot
                    present(AlertDialog.Builder(this).setTitle("UK Television ${BuildConfig.VERSION_NAME}")
                        .setMessage("${model.stations.size} services\n${s?.programmeCount ?: 0} programme entries\n${s?.descriptions ?: 0} descriptions\n${model.userPlaylists.size} additional playlists\n\nPrivacy mode: ${model.privacyMode.label}\nGuide through: ${s?.guideEnd?.let { Date(it).toString() } ?: "not loaded"}\nSource revision: ${s?.revision?.take(12) ?: "unknown"}\n\nThe app contains no analytics, advertising or account SDK. Favourites and stream-health data remain local. Fire OS itself can still observe that this app runs; no app can make itself invisible to the operating system.\n\n${s?.warnings.orEmpty()}\n\nPreview build: external stream availability is not guaranteed.")
                        .setPositiveButton("Close", null))
                }
            }
        })
    }

    private fun showPrivacy() {
        val labels = arrayOf(
            "Hardened compatibility — preserve HTTP-only TV streams when required",
            "Strict privacy — HTTPS direct streams only; remote artwork and external-app handoff blocked"
        )
        present(AlertDialog.Builder(this).setTitle("Privacy mode").setSingleChoiceItems(
            labels,
            if (model.privacyMode == PrivacyMode.STRICT) 1 else 0
        ) { dialog, which ->
            engine.close()
            model.isPlayer = false
            model.setPrivacyMode(if (which == 1) PrivacyMode.STRICT else PrivacyMode.HARDENED)
            dialog.dismiss()
        }.setNegativeButton("Cancel", null))
    }

    private fun showPlaylists() {
        val labels = mutableListOf("+ Add playlist", "Refresh all")
        labels += model.userPlaylists.map { "${it.name}\n${it.url}" }
        present(AlertDialog.Builder(this).setTitle("Additional playlists").setItems(labels.toTypedArray()) { _, which ->
            when (which) {
                0 -> handler.post { addPlaylistDialog() }
                1 -> model.refreshUserPlaylists()
                else -> {
                    val item = model.userPlaylists[which - 2]
                    handler.post {
                        present(AlertDialog.Builder(this).setTitle(item.name)
                            .setMessage(item.url)
                            .setPositiveButton("Remove") { _, _ -> model.removePlaylist(item.url) }
                            .setNegativeButton("Keep", null))
                    }
                }
            }
        }.setNegativeButton("Close", null))
    }

    private fun addPlaylistDialog() {
        val name = EditText(this).apply {
            hint = "Playlist name"
            setSingleLine(true)
            setPadding(24, 16, 24, 16)
        }
        present(AlertDialog.Builder(this).setTitle("Add playlist").setView(name)
            .setPositiveButton("Next") { _, _ ->
                val chosen = name.text.toString()
                handler.post {
                    val url = EditText(this).apply {
                        hint = "https://example.com/playlist.m3u"
                        setSingleLine(true)
                        setPadding(24, 16, 24, 16)
                    }
                    present(AlertDialog.Builder(this).setTitle(chosen.ifBlank { "Playlist URL" }).setView(url)
                        .setPositiveButton("Add") { _, _ -> model.addPlaylist(chosen, url.text.toString()) }
                        .setNegativeButton("Cancel", null))
                }
            }.setNegativeButton("Cancel", null))
    }

    private fun openExternalSource(source: StreamSource) {
        if (!model.allowExternalApps) {
            present(AlertDialog.Builder(this).setTitle("Blocked by Strict Privacy")
                .setMessage("This source would hand playback to ${source.host}, which lets that service observe the connection. Switch to Hardened compatibility if you want to open it.")
                .setPositiveButton("Close", null))
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)))
        } catch (_: ActivityNotFoundException) {
            toast("No compatible app or browser is installed for this official source")
        }
    }
    private fun showTracks(type: Int) {
        val player = engine.player ?: return
        val tracks = player.currentTracks.groups.filter { it.type == type }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
        }
        val labels = tracks.map { (g,i) -> val f = g.getTrackFormat(i); listOfNotNull(f.label, f.language, f.codecs).joinToString(" · ").ifBlank { "Track ${i+1}" } }.toMutableList()
        if (type == C.TRACK_TYPE_TEXT) labels.add(0,"Off")
        if (labels.isEmpty()) { toast("No selectable tracks supplied"); return }
        present(AlertDialog.Builder(this).setTitle(if(type==C.TRACK_TYPE_TEXT) "Subtitles" else "Audio tracks")
            .setItems(labels.toTypedArray()) { _, i ->
                val parameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
                if(type==C.TRACK_TYPE_TEXT && i==0) parameters.setTrackTypeDisabled(type,true)
                else {
                    val (group, index) = tracks[i - if(type==C.TRACK_TYPE_TEXT) 1 else 0]
                    parameters.setTrackTypeDisabled(type,false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,index))
                }
                player.trackSelectionParameters = parameters.build()
            })
    }
    private fun chooseAspect() = present(AlertDialog.Builder(this).setTitle("Picture size")
        .setItems(arrayOf("Fit — preserve picture", "Zoom — crop edges", "Fill — stretch")) { _, index ->
            resizeMode = arrayOf(AspectRatioFrameLayout.RESIZE_MODE_FIT,AspectRatioFrameLayout.RESIZE_MODE_ZOOM,AspectRatioFrameLayout.RESIZE_MODE_FILL)[index]
        })
    private fun openOfficial(url: String) {
        openExternalSource(StreamSource(
            "official", url, "Official source", Uri.parse(url).host.orEmpty(),
            "application/x-external", emptyMap(), false,
            if (url.contains("youtube.com")) "youtube" else "web"
        ))
    }
}

fun formatTime(time: Long): String = SimpleDateFormat("HH:mm", Locale.UK).format(Date(time))
