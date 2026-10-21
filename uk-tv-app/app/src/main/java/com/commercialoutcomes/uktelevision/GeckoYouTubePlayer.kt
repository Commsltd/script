package com.commercialoutcomes.uktelevision

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.mozilla.geckoview.*

/** The renderer lives inside this application; no browser or YouTube app is launched. */
@androidx.annotation.OptIn(ExperimentalGeckoViewApi::class)
private object EmbeddedRuntime {
    private var runtime: GeckoRuntime? = null
    fun get(context: Context): GeckoRuntime = runtime ?: GeckoRuntime.create(context.applicationContext,
        GeckoRuntimeSettings.Builder().configFilePath("")
            // This is a single embedded player on 1 GB Fire TV hardware. Keep
            // content sandboxing, but avoid a separate process for every site.
            .fissionEnabled(false).extensionsProcessEnabled(false)
            .consoleOutput(BuildConfig.DEBUG).build()).also {
            runtime = it
            it.notifyTelemetryPrefChanged(false)
            GeckoPreferenceController.setGeckoPref("dom.ipc.processCount", 1, GeckoPreferenceController.PREF_BRANCH_USER)
            listOf("datareporting.healthreport.uploadEnabled", "datareporting.policy.dataSubmissionEnabled",
                "toolkit.telemetry.enabled", "toolkit.telemetry.archive.enabled", "datareporting.usage.uploadEnabled")
                .forEach { name -> GeckoPreferenceController.setGeckoPref(name, false, GeckoPreferenceController.PREF_BRANCH_USER) }
        }
}

@Composable
fun GeckoYouTubePlayer(source: StreamSource, strict: Boolean, controller: YouTubeController,
    modifier: Modifier = Modifier, interactive: Boolean = false) {
    val context = LocalContext.current
    val retry = controller.retryGeneration
    val session = remember(source.id, strict, retry) {
        GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true)
            .suspendMediaWhenInactive(true).build()).apply {
            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) { controller.event("page-start", 0) }
                override fun onPageStop(session: GeckoSession, success: Boolean) { controller.event("page-stop", if (success) 1 else 0) }
            }
            contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onCrash(session: GeckoSession) { controller.error("The video player stopped. Press OK to retry.") }
            }
            permissionDelegate = object : GeckoSession.PermissionDelegate {
                override fun onContentPermissionRequest(session: GeckoSession,
                    perm: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int> {
                    val host = Uri.parse(perm.uri).host
                    val autoplay = perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                        perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                    return GeckoResult.fromValue(if (autoplay && host in setOf("www.youtube.com", "www.youtube-nocookie.com"))
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                }
            }
            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                    val uri = Uri.parse(request.uri)
                    val allowed = uri.scheme == "https" && uri.host in setOf("www.youtube.com", "www.youtube-nocookie.com") && uri.path?.startsWith("/embed/") == true
                    if (BuildConfig.DEBUG) android.util.Log.d("TVYouTube", "navigation ${uri.host}${uri.path} allowed=$allowed")
                    return GeckoResult.fromValue(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                }
                override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                    controller.error("YouTube could not connect. Press OK to retry.")
                    return null
                }
            }
            mediaSessionDelegate = object : MediaSession.Delegate {
                override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
                    controller.nativeToggle = { if (controller.playing) mediaSession.pause() else mediaSession.play() }
                }
                override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) { controller.nativeToggle = null }
                override fun onPlay(session: GeckoSession, mediaSession: MediaSession) { controller.event("state",1) }
                override fun onPause(session: GeckoSession, mediaSession: MediaSession) { controller.event("state",2) }
                override fun onStop(session: GeckoSession, mediaSession: MediaSession) { controller.event("state",0) }
            }
            open(EmbeddedRuntime.get(context))
        }
    }
    val view = remember(session) { GeckoView(context).apply {
        setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
        setBackgroundColor(android.graphics.Color.BLACK)
        setSession(session); session.setActive(true); coverUntilFirstPaint(android.graphics.Color.BLACK); keepScreenOn = true
    } }
    LaunchedEffect(session) {
        controller.loading()
        val page = YouTubeIdentity.livePage(source)
        val resolved = if (page.isBlank()) null else runCatching { YouTubeIdentity.resolve(page) }.getOrNull()
        currentCoroutineContext().ensureActive()
        val id = resolved ?: YouTubeIdentity.videoId(source.url)
        if (id == null) { controller.error("No current live broadcast could be found. Press OK to retry."); return@LaunchedEffect }
        controller.starting(id, 120000)
        val host = if (strict) "www.youtube-nocookie.com" else "www.youtube.com"
        session.load(GeckoSession.Loader().uri("https://$host/embed/$id?autoplay=1&controls=1&playsinline=1&rel=0")
            .referrer("https://${context.packageName}/"))
    }
    DisposableEffect(session) {
        onDispose { controller.nativeToggle = null; controller.stopTimer(); view.releaseSession(); session.close() }
    }
    Box(modifier) {
        key(session) { AndroidView(factory = { view }, update = {
            it.isFocusable = interactive; it.isFocusableInTouchMode = interactive
        }, modifier = Modifier.fillMaxSize()) }
        if (controller.failed || !controller.playing) Text(controller.status, color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha=0.9f)).padding(12.dp))
    }
}
