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
class PlaybackEngine(
    private val context: Context,
    private val model: TvModel,
    private val scope: CoroutineScope,
    private val onIntegratedFallback: (StreamSource) -> Unit = {}
) {
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
    private var recovery: Job? = null
    private val retries = mutableMapOf<String, Int>()
    private var paused = false
    var lastProblem by mutableStateOf(""); private set
    val youtube = YouTubeController()

    private fun errorReason(error: PlaybackException): String {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) return "HTTP_${cause.responseCode}"
            if (cause is java.net.UnknownHostException) return "DNS_UNAVAILABLE"
            cause = cause.cause
        }
        return error.errorCodeName
    }

    fun play(row: GuideRow, forceSource: Boolean = false) {
        generation++; val token = generation
        request?.cancel(); recovery?.cancel(); stopPlayer()
        station = row.station; failed = false; tried.clear(); retries.clear(); attempt = 0
        paused = false; lastProblem = ""
        status = "Selecting a stream…"; profile = model.profile
        request = scope.launch {
            val health = model.dao.health(profile).associateBy { it.streamId }
            val pin = model.prefs.getString("pin:$profile:${row.station.id}", "")
            val forced = if (forceSource || row.sourceIndex > 0) row.source.id else null
            candidates = row.station.sources()
                .filter { GuideRules.canUseDirectSource(it, model.privacyMode) }
                .sortedWith(
                compareByDescending<StreamSource> { it.id == forced }
                    .thenByDescending { it.id == pin }
                    .thenByDescending { GuideRules.healthScore(health[it.id]) }
            )
            if (token != generation) return@launch
            if (candidates.isEmpty()) {
                val youtube = row.station.sources().firstOrNull { it.kind == "youtube" }
                if (model.autoFallback && youtube != null) {
                    failed = false
                    status = "Switching to official YouTube…"
                    onIntegratedFallback(youtube)
                    return@launch
                }
                failed = true
                val hasHttp = row.station.sources().any { it.kind == "direct" && it.url.startsWith("http://") }
                status = when {
                    model.privacyMode == PrivacyMode.STRICT && hasHttp ->
                        "Strict Privacy blocked this channel's unencrypted HTTP source. Hold OK for other sources."
                    youtube != null ->
                        "An official YouTube source is available. Hold OK for sources."
                    else ->
                        "No supported in-app stream is available for this service."
                }
            } else startNext(token)
        }
    }
    private fun connected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork != null
    }
    private fun startNext(token: Int, retry: StreamSource? = null) {
        if (token != generation) return
        val next = retry ?: candidates.firstOrNull { it.id !in tried }
        val limit = if (model.autoFallback) candidates.size else 1
        if (next == null || (retry == null && tried.size >= limit)) {
            val youtube = station?.sources()?.firstOrNull { it.kind == "youtube" }
            if (model.autoFallback && youtube != null) {
                failed = false
                status = "Direct feeds failed; switching to official YouTube…"
                stopPlayer()
                onIntegratedFallback(youtube)
                return
            }
            failed = true
            status = if (youtube != null)
                "Direct feeds failed. Hold OK and choose the official YouTube source."
            else
                "${lastProblem.ifBlank { "No available source played." }} Press OK to retry or hold OK for sources."
            stopPlayer()
            return
        }
        attempt++; val thisAttempt = attempt
        stopPlayer(); tried.add(next.id); source = next; firstFrame = false; markedSuccess = false
        status = "Connecting · ${next.host} · ${tried.size}/${minOf(limit, candidates.size)}"
        val http = DefaultHttpDataSource.Factory().setUserAgent("UKTelevision/${BuildConfig.VERSION_NAME}")
            .setDefaultRequestProperties(next.headers).setConnectTimeoutMs(10000).setReadTimeoutMs(12000)
            .setAllowCrossProtocolRedirects(model.allowCleartextVideo)
        val media = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
            .createMediaSource(MediaItem.Builder().setUri(next.url).setMimeType(next.mime).build())
        val exo = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true)).build()
        player = exo
        exo.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        exo.setHandleAudioBecomingNoisy(true)
        fun current() = generation == token && attempt == thisAttempt && player === exo
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!current()) return
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW &&
                    (retries[next.id] ?: 0) < 3) {
                    retries[next.id] = (retries[next.id] ?: 0) + 1
                    status = "Returning to live…"
                    armWatchdog(token, thisAttempt, 20000)
                    exo.seekToDefaultPosition(); exo.prepare(); exo.play()
                } else failure(token, thisAttempt, errorReason(error))
            }
            override fun onRenderedFirstFrame() {
                if (!current()) return
                firstFrame = true; watchdog?.cancel(); status = "Playing · ${next.host}"
                success?.cancel()
                success = scope.launch {
                    delay(12000)
                    if (current() && exo.isPlaying && !markedSuccess) {
                        markedSuccess = true
                        retries[next.id] = 0
                        val old = model.dao.health(profile).firstOrNull { it.streamId == next.id } ?: StreamHealth(next.id, profile)
                        model.dao.putHealth(old.copy(successes = old.successes + 1, lastOk = System.currentTimeMillis()))
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (current() && isPlaying && firstFrame) {
                    watchdog?.cancel(); status = "Playing · ${next.host}"
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (!current()) return
                if (state == Player.STATE_BUFFERING && firstFrame && !paused) {
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
        if (token != generation || currentAttempt != attempt || failed || paused) return
        attempt++
        watchdog?.cancel(); success?.cancel(); recovery?.cancel()
        val failedSource = source ?: return
        lastProblem = RecoveryRules.explain(reason)
        stopPlayer()
        recovery = scope.launch {
            if (!connected()) {
                lastProblem = RecoveryRules.explain("NETWORK_UNAVAILABLE")
                status = "Connection lost · waiting to reconnect…"
                repeat(30) {
                    delay(2000)
                    if (token != generation) return@launch
                    if (connected()) {
                        startNext(token, failedSource)
                        return@launch
                    }
                }
                if (token == generation) {
                    failed = true; status = "$lastProblem Press OK to retry."
                }
                return@launch
            }
            if (token != generation) return@launch
            val old = model.dao.health(profile).firstOrNull { it.streamId == failedSource.id }
                ?: StreamHealth(failedSource.id, profile)
            if (token != generation) return@launch
            model.dao.putHealth(old.copy(failures = old.failures + 1,
                lastFailure = System.currentTimeMillis(), lastError = reason))
            val count = (retries[failedSource.id] ?: 0)
            val wait = RecoveryRules.retryDelay(count)
            if (RecoveryRules.retryable(reason) && wait != null) {
                retries[failedSource.id] = count + 1
                status = "Reconnecting · attempt ${count + 1}/3"
                delay(wait)
                if (token == generation && !paused) startNext(token, failedSource)
            } else if (token == generation) {
                status = "$lastProblem Trying another source…"
                startNext(token)
            }
        }
    }
    fun pauseOrPlay() {
        val current = player
        if (failed || current == null) { model.playingRow?.let { play(it) }; return }
        if (current.playWhenReady) { paused = true; current.pause(); watchdog?.cancel(); recovery?.cancel(); status = "Paused" }
        else { paused = false; if (current.isCurrentMediaItemLive) current.seekToDefaultPosition(); status = "Resuming live…"; armWatchdog(generation, attempt, 20000); current.play() }
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
    fun close() { generation++; request?.cancel(); recovery?.cancel(); stopPlayer(); status = "" }
}
