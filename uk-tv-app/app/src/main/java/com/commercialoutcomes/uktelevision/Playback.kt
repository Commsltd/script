package com.commercialoutcomes.uktelevision

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
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
    private var pictureCheck: Job? = null
    private var health = emptyMap<String, StreamHealth>()
    private var success: Job? = null
    private var candidates = emptyList<StreamSource>()
    private val tried = mutableSetOf<String>()
    private var markedSuccess = false
    private var firstFrame = false
    private var profile = ""
    private var recovery: Job? = null
    private val retries = mutableMapOf<String, Int>()
    private var paused = false
    private val checks = SourceChecks(context)
    private var checking: Job? = null
    private var refreshingSources: Job? = null
    private var refreshedAfterFailure = false
    private var route = ""
    var lastProblem by mutableStateOf(""); private set
    val youtube = YouTubeController()

    private fun errorReason(error: PlaybackException): String {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)
                return if (SourceAdvice.geoMessage(cause.responseBody.toString(Charsets.UTF_8))) "GEO_RESTRICTED" else "HTTP_${cause.responseCode}"
            if (cause is java.net.UnknownHostException) return "DNS_UNAVAILABLE"
            cause = cause.cause
        }
        return error.errorCodeName
    }

    fun play(row: GuideRow, forceSource: Boolean = false) {
        generation++; val token = generation
        request?.cancel(); recovery?.cancel(); checking?.cancel(); refreshingSources?.cancel(); stopPlayer()
        refreshedAfterFailure = false
        station = row.station; source = null; failed = false; tried.clear(); retries.clear(); attempt = 0
        paused = false; lastProblem = ""
        status = "Selecting a stream…"; profile = model.profile; route = checks.route(profile)
        request = scope.launch {
            health = model.dao.health(profile).associateBy { it.streamId }
            val pin = model.prefs.getString("pin:$profile:${row.station.id}", "")
            val forced = if (forceSource || row.sourceIndex > 0) row.source.id else null
            candidates = LiveSourceRules.merge(row.station.sources(),
                LiveSourceRefresh.cached(row.station.id, model.snapshot?.builtAt ?: 0))
                .filter { GuideRules.canUseDirectSource(it, model.privacyMode) }
                .sortedWith(
                compareByDescending<StreamSource> { it.id == forced }
                    .thenByDescending { it.id == pin }
                    .thenBy { it.priority }
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
        val next = retry ?: if (tried.isEmpty()) candidates.firstOrNull() else candidates.filter { it.id !in tried }.sortedWith(
            compareByDescending<StreamSource> { GuideRules.healthScore(health[it.id]) }
                .thenByDescending { checks.cached(it, route)?.sampled == true }
                .thenBy { it.priority }
        ).firstOrNull()
        val limit = if (model.autoFallback) candidates.size else 1
        if (next == null || (retry == null && tried.size >= limit)) {
            val channel = station
            if (model.autoFallback && !refreshedAfterFailure && channel != null &&
                !channel.id.startsWith("user.") && connected()) {
                refreshedAfterFailure = true
                status = "Checking for updated stream links…"
                stopPlayer()
                refreshingSources = scope.launch {
                    val published = LiveSourceRefresh.sources(channel.id, model.snapshot?.builtAt ?: 0)
                    if (token != generation) return@launch
                    // The hourly guide refresh may already have a newer source set.
                    val current = model.stations.firstOrNull { it.id == channel.id }?.sources().orEmpty()
                    val fresh = LiveSourceRules.merge(current, published)
                        .filter { GuideRules.canUseDirectSource(it, model.privacyMode) }
                    val changed = LiveSourceRules.changedIds(candidates, fresh)
                    if (changed.isNotEmpty()) {
                        candidates = LiveSourceRules.merge(candidates, fresh)
                        // Retry changed addresses once, even when their source ID is unchanged.
                        // Never replay an identical failed URL merely because it was renamed.
                        tried.addAll(candidates.map { it.id }.filter { it !in changed })
                        tried.removeAll(changed)
                        health = health.filterKeys { it !in changed }
                    }
                    startNext(token)
                }
                return
            }
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
        val label = next.region.takeIf { it.isNotBlank() } ?: next.host
        status = "Connecting · $label · ${tried.size}/${minOf(limit, candidates.size)}"
        val hasBackup = model.autoFallback && candidates.any { it.id !in tried }
        val http: androidx.media3.datasource.HttpDataSource.Factory =
            if (station?.id?.startsWith("user.") == true) DefaultHttpDataSource.Factory()
                .setUserAgent("UKTelevision/${BuildConfig.VERSION_NAME}").setDefaultRequestProperties(next.headers)
                .setConnectTimeoutMs(if (hasBackup) 4000 else 10000).setReadTimeoutMs(if (hasBackup) 5000 else 12000)
                .setAllowCrossProtocolRedirects(model.allowCleartextVideo)
            else androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
                PublicStreamHttp.playbackClient(model.privacyMode == PrivacyMode.STRICT, next))
                .setUserAgent("UKTelevision/${BuildConfig.VERSION_NAME}").setDefaultRequestProperties(next.headers)
        val media = PlaybackMediaSources.create(http,
            MediaItem.Builder().setUri(next.url).setMimeType(next.mime).build(),
            object : DefaultLoadErrorHandlingPolicy() {
                override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                    if (hasBackup) C.TIME_UNSET else super.getRetryDelayMsFor(info)
            })
        // Keep live-TV buffering bounded on the 1 GB Firestick. Start after one
        // second, retain enough runway for brief VPN/network interruptions.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 30000, 1000, 2500)
            .setTargetBufferBytes(24 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false).build()
        val exo = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setLoadControl(loadControl).build()
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
                firstFrame = true; watchdog?.cancel(); status = "Playing · $label"
                success?.cancel()
                success = scope.launch {
                    delay(12000)
                    if (current() && exo.isPlaying && !markedSuccess) {
                        markedSuccess = true
                        refreshedAfterFailure = false
                        retries[next.id] = 0
                        tried.clear(); tried.add(next.id)
                        val old = model.dao.health(profile).firstOrNull { it.streamId == next.id } ?: StreamHealth(next.id, profile)
                        model.dao.putHealth(old.copy(successes = old.successes + 1, lastOk = System.currentTimeMillis()))
                        checkBackups(token)
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (current() && isPlaying && firstFrame) {
                    watchdog?.cancel(); status = "Playing · $label"
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (!current()) return
                if (state == Player.STATE_BUFFERING && firstFrame && !paused) {
                    status = "Buffering · $label"
                    armWatchdog(token, thisAttempt, if (hasBackup) 7000 else 20000)
                } else if (state == Player.STATE_READY && firstFrame) {
                    watchdog?.cancel(); status = "Playing · $label"
                } else if (state == Player.STATE_ENDED) failure(token, thisAttempt, "STREAM_ENDED")
            }
        })
        exo.setMediaSource(media); exo.prepare(); exo.playWhenReady = true
        armWatchdog(token, thisAttempt, if (hasBackup) 8000 else 15000)
        pictureCheck = scope.launch {
            val progress = VideoProgress()
            while (current()) {
                delay(1000)
                if (!current()) break
                val counters = exo.videoDecoderCounters
                counters?.ensureUpdated()
                val reason = progress.sample(SystemClock.elapsedRealtime(),
                    counters?.renderedOutputBufferCount ?: 0, exo.isPlaying && !paused,
                    exo.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
                if (reason != null) { failure(token, thisAttempt, reason); break }
            }
        }
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
        lastProblem = SourceAdvice.explain(SourceCheck(System.currentTimeMillis(), reason,
            country = SourceAdvice.country(failedSource)))
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
            val updated = old.copy(failures = old.failures + 1,
                lastFailure = System.currentTimeMillis(), lastError = reason)
            health = health + (failedSource.id to updated)
            model.dao.putHealth(updated)
            checks.remember(failedSource, route, SourceCheck(System.currentTimeMillis(), reason))
            if (model.autoFallback && candidates.any { it.id !in tried }) {
                status = "$lastProblem Trying another source…"
                startNext(token)
                return@launch
            }
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
    private fun checkBackups(token: Int) {
        if (checking?.isActive == true || station?.id?.startsWith("user.") == true) return
        // Only built-in public channels; account playlists may limit connections.
        checking = scope.launch {
            var cursor = 0
            delay(15000) // Let initial playback settle before spending bandwidth on backups.
            while (generation == token) {
                if (paused) { delay(300000); continue }
                // Metadata only: do not replace the playing video or force a quality change.
                val fresh = station?.let { LiveSourceRefresh.sources(it.id, model.snapshot?.builtAt ?: 0) }.orEmpty()
                if (generation != token) break
                val changed = LiveSourceRules.changedIds(candidates, fresh)
                val remaining = LiveSourceRules.remainingAttempts(tried, candidates, fresh)
                tried.clear(); tried.addAll(remaining)
                health = health.filterKeys { it !in changed }
                candidates = LiveSourceRules.merge(candidates, fresh)
                    .filter { GuideRules.canUseDirectSource(it, model.privacyMode) }
                val activeRoute = checks.route(profile)
                if (activeRoute != route) { route = activeRoute; cursor = 0; tried.clear(); source?.let { tried.add(it.id) } }
                val eligible = candidates.filter { it.id != source?.id }
                var checked = 0
                for (offset in eligible.indices) {
                    val candidate = eligible[cursor % eligible.size]
                    cursor = (cursor + 1) % eligible.size
                    if (checks.cached(candidate, route) != null) continue
                    val result = checks.check(candidate, model.privacyMode == PrivacyMode.STRICT)
                    if (generation != token || checks.route(profile) != activeRoute) break
                    checks.remember(candidate, activeRoute, result)
                    if (++checked >= 3) break
                }
                delay(300000)
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
        watchdog?.cancel(); success?.cancel(); pictureCheck?.cancel()
        val old = player; player = null
        old?.release()
    }
    fun close() { generation++; request?.cancel(); recovery?.cancel(); checking?.cancel(); refreshingSources?.cancel(); stopPlayer(); source = null; status = "" }
}
