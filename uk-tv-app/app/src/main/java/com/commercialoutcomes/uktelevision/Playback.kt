package com.commercialoutcomes.uktelevision

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.runtime.*
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.*

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackEngine(private val context: Context, private val model: TvModel, private val scope: CoroutineScope) {
    var player by mutableStateOf<ExoPlayer?>(null); private set
    var status by mutableStateOf(""); private set
    var source by mutableStateOf<StreamSource?>(null); private set
    var failed by mutableStateOf(false); private set
    private var station: Station? = null
    private var generation = 0
    private var attempt = 0
    private var request: Job? = null
    private var watchdog: Job? = null
    private var success: Job? = null
    private var candidates = emptyList<StreamSource>()
    private val tried = mutableSetOf<String>()
    private var markedSuccess = false
    private var firstFrame = false
    private var profile = ""

    fun play(row: GuideRow, forceSource: Boolean = false) {
        model.markPlaying(row)
        generation++; val token = generation
        request?.cancel(); stopPlayer()
        station = row.station; failed = false; tried.clear(); attempt = 0
        status = "Selecting a stream…"; profile = model.profile
        request = scope.launch {
            val health = model.dao.health(profile).associateBy { it.streamId }
            val pin = model.prefs.getString("pin:$profile:${row.station.id}", "")
            val forced = if (forceSource || row.sourceIndex > 0) row.source.id else null
            candidates = row.station.sources()
                .filter { it.kind == "direct" && !it.unsupportedDrm }
                .filter { model.allowCleartextVideo || it.url.startsWith("https://") }
                .sortedWith(
                compareByDescending<StreamSource> { it.id == forced }
                    .thenByDescending { it.id == pin }
                    .thenByDescending { GuideRules.healthScore(health[it.id]) }
            )
            if (token != generation) return@launch
            if (candidates.isEmpty()) {
                failed = true
                val hasExternal = row.station.sources().any { it.kind != "direct" }
                val hasHttp = row.station.sources().any { it.kind == "direct" && it.url.startsWith("http://") }
                status = when {
                    model.privacyMode == PrivacyMode.STRICT && hasHttp ->
                        "Strict Privacy blocked this channel's unencrypted HTTP source. Hold OK for other sources."
                    hasExternal ->
                        "No direct stream is available. Hold OK for an official external source."
                    else ->
                        "No supported direct stream is available for this service."
                }
            } else startNext(token)
        }
    }
    private fun connected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork != null
    }
    private fun startNext(token: Int) {
        if (token != generation) return
        val next = candidates.firstOrNull { it.id !in tried }
        val limit = if (model.autoFallback) candidates.size else 1
        if (next == null || tried.size >= limit) {
            failed = true
            status = "No available source played. Hold OK for sources or Back for the guide."
            stopPlayer(); return
        }
        attempt++; val thisAttempt = attempt
        stopPlayer(); tried.add(next.id); source = next; firstFrame = false; markedSuccess = false
        status = "Connecting · ${next.host} · ${tried.size}/${minOf(limit, candidates.size)}"
        val http = DefaultHttpDataSource.Factory().setUserAgent("UKTelevision/0.1")
            .setDefaultRequestProperties(next.headers).setConnectTimeoutMs(10000).setReadTimeoutMs(12000)
            .setAllowCrossProtocolRedirects(true)
        val media = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
            .createMediaSource(MediaItem.Builder().setUri(next.url).setMimeType(next.mime).build())
        val exo = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true)).build()
        player = exo
        exo.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        exo.setHandleAudioBecomingNoisy(true)
        fun current() = generation == token && attempt == thisAttempt && player === exo
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (current()) failure(token, thisAttempt, error.errorCodeName)
            }
            override fun onRenderedFirstFrame() {
                if (!current()) return
                firstFrame = true; watchdog?.cancel(); status = "Playing · ${next.host}"
                success?.cancel()
                success = scope.launch {
                    delay(12000)
                    if (current() && exo.isPlaying && !markedSuccess) {
                        markedSuccess = true
                        val old = model.dao.health(profile).firstOrNull { it.streamId == next.id } ?: StreamHealth(next.id, profile)
                        model.dao.putHealth(old.copy(successes = old.successes + 1, lastOk = System.currentTimeMillis()))
                    }
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (!current()) return
                if (state == Player.STATE_BUFFERING && firstFrame) {
                    status = "Buffering · ${next.host}"
                    armWatchdog(token, thisAttempt, 20000)
                } else if (state == Player.STATE_READY && firstFrame) {
                    watchdog?.cancel(); status = "Playing · ${next.host}"
                } else if (state == Player.STATE_ENDED) failure(token, thisAttempt, "STREAM_ENDED")
            }
        })
        exo.setMediaSource(media); exo.prepare(); exo.playWhenReady = true
        armWatchdog(token, thisAttempt, 15000)
    }
    private fun armWatchdog(token: Int, currentAttempt: Int, delayMs: Long) {
        watchdog?.cancel()
        watchdog = scope.launch { delay(delayMs); failure(token, currentAttempt, "START_OR_BUFFER_TIMEOUT") }
    }
    private fun failure(token: Int, currentAttempt: Int, reason: String) {
        if (token != generation || currentAttempt != attempt || failed) return
        attempt++ // Invalidate any duplicate callbacks immediately.
        watchdog?.cancel(); success?.cancel()
        val failedSource = source ?: return
        if (!connected()) {
            failed = true; status = "Network unavailable. Reconnect Wi-Fi/VPN, then press OK to retry."
            stopPlayer(); return
        }
        status = "Source failed; trying the next source…"
        scope.launch {
            val old = model.dao.health(profile).firstOrNull { it.streamId == failedSource.id } ?: StreamHealth(failedSource.id, profile)
            model.dao.putHealth(old.copy(failures = old.failures + 1, lastFailure = System.currentTimeMillis(), lastError = reason))
            if (token == generation) startNext(token)
        }
    }
    fun pauseOrPlay() {
        val current = player
        if (failed || current == null) { model.playingRow?.let { play(it) }; return }
        if (current.playWhenReady) { current.pause(); watchdog?.cancel(); status = "Paused" }
        else { current.play(); status = "Playing · ${source?.host.orEmpty()}" }
    }
    fun pinCurrent() {
        val current = source ?: return
        val id = station?.id ?: return
        model.prefs.edit().putString("pin:${model.profile}:$id", current.id).apply()
    }
    fun stopPlayer() {
        watchdog?.cancel(); success?.cancel()
        val old = player; player = null
        old?.release()
    }
    fun close() { generation++; request?.cancel(); stopPlayer(); status = "" }
}
