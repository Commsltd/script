package com.commercialoutcomes.uktelevision

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.tv.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewMediaIntegrityApiStatusConfig
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object YouTubeIdentity {
    private val idPattern = Regex("^[A-Za-z0-9_-]{11}$")
    fun videoId(raw: String): String? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()
        val id = when {
            host == "youtu.be" -> uri.pathSegments.firstOrNull()
            host in setOf("www.youtube.com", "youtube.com", "m.youtube.com") && uri.path == "/watch" -> uri.getQueryParameter("v")
            host in setOf("www.youtube.com", "youtube.com") && uri.path?.startsWith("/embed/") == true -> uri.lastPathSegment
            else -> null
        }
        return id?.takeIf { idPattern.matches(it) }
    }
    fun livePage(source: StreamSource): String = source.livePage.ifBlank {
        if (source.label.startsWith("Sky News official")) "https://www.youtube.com/@SkyNews/live" else ""
    }
    // Decode only the player response, never a recommended video's identity.
    fun currentLiveId(html: String): String? {
        val marker = Regex("ytInitialPlayerResponse\\s*=\\s*").find(html) ?: return null
        val tail = html.substring(marker.range.last + 1)
        var depth = 0; var quoted = false; var escaped = false; var end = -1
        for ((i, ch) in tail.withIndex()) {
            if (quoted) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i + 1; break } }
            }
        }
        if (end < 0) return null
        val json = runCatching { JSONObject(tail.substring(0, end)) }.getOrNull() ?: return null
        val live = json.optJSONObject("microformat")?.optJSONObject("playerMicroformatRenderer")
            ?.optJSONObject("liveBroadcastDetails")?.optBoolean("isLiveNow") == true
        val details = json.optJSONObject("videoDetails") ?: return null
        return details.optString("videoId").takeIf { live && idPattern.matches(it) }
    }
    suspend fun resolve(page: String): String? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(page)
        require(uri.scheme == "https" && uri.host == "www.youtube.com" && uri.path?.endsWith("/live") == true)
        val connection = URL(page).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000; connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.setRequestProperty("Accept-Language", "en-GB,en;q=0.9")
        try {
            if (connection.responseCode != 200) return@withContext null
            val bytes = connection.inputStream.use { it.readBytesLimited(3 * 1024 * 1024) }
            currentLiveId(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer); if (n < 0) break
        require(out.size() + n <= limit) { "Live page too large" }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

class YouTubeController {
    var status by mutableStateOf(""); private set
    var failed by mutableStateOf(false); private set
    var playing by mutableStateOf(false); private set
    var videoId by mutableStateOf(""); private set
    var retryGeneration by mutableIntStateOf(0); private set
    private var view: WebView? = null
    fun stopTimer() { timeout?.let(handler::removeCallbacks); timeout = null }
    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
    val events = mutableListOf<String>()
    fun attached(value: WebView) = view === value
    fun attach(value: WebView) { view = value }
    fun detach(value: WebView) { if (view === value) { view = null; timeout?.let(handler::removeCallbacks) } }
    fun loading() { events.clear(); timeout?.let(handler::removeCallbacks); videoId = ""; failed = false; playing = false; status = "Finding the current YouTube live broadcast…" }
    var loads = 0; private set
    fun starting(id: String, timeoutMs: Long = 60000) {
        loads++
        videoId = id; failed = false; status = "Connecting to YouTube…"
        timeout?.let(handler::removeCallbacks)
        timeout = Runnable { if (!playing && !failed) error("YouTube did not start. Press OK to retry.") }.also { handler.postDelayed(it, timeoutMs) }
    }
    fun event(name: String, code: Int) {
        if (events.size > 50) events.removeAt(0)
        events.add("$name:$code")
        android.util.Log.i("TVYouTube", "$name:$code")
        when (name) {
            "state" -> when (code) {
                1 -> { timeout?.let(handler::removeCallbacks); timeout = null; playing = true; failed = false; status = "Playing · YouTube" }
                2 -> { timeout?.let(handler::removeCallbacks); timeout = null; playing = false; status = "Paused · YouTube" }
                3 -> {
                    playing = false; status = "Buffering · YouTube"
                    if (timeout == null) armBufferTimeout()
                }
                0 -> error("This YouTube broadcast has ended. Press OK to find the current live broadcast.")
            }
            "error" -> error(when (code) {
                100 -> "This YouTube video is no longer available. Press OK to find the current broadcast."
                101, 150 -> "The broadcaster does not allow this video in embedded players."
                153 -> "YouTube rejected the player identification (153)."
                else -> "YouTube could not play this broadcast (error $code). Press OK to retry."
            })
            "blocked" -> error("YouTube requires a play action. Press OK to play.")
        }
    }
    private fun armBufferTimeout() {
        timeout = Runnable { if (!playing && !failed) error("YouTube stopped responding. Press OK to retry.") }
            .also { handler.postDelayed(it, 30000) }
    }
    fun error(message: String) { timeout?.let(handler::removeCallbacks); failed = true; playing = false; status = message }
    fun retry() { retryGeneration++ }
    fun pauseOrPlay() {
        if (failed) { retry(); return }
        view?.evaluateJavascript("if(window.player){window.player.${if (playing) "pauseVideo" else "playVideo"}();}", null)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbeddedYouTubePlayer(source: StreamSource, strict: Boolean, controller: YouTubeController,
    modifier: Modifier = Modifier, interactive: Boolean = false) {
    val context = LocalContext.current
    val retry = controller.retryGeneration
    val webView = remember(source.id, strict, retry) {
        WebView(context).apply {
            setBackgroundColor(Color.BLACK); keepScreenOn = true
            PlayerWebSecurity.configure(this)
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false); settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEBVIEW_MEDIA_INTEGRITY_API_STATUS)) {
                WebSettingsCompat.setWebViewMediaIntegrityApiStatus(settings,
                    WebViewMediaIntegrityApiStatusConfig.Builder(
                        WebViewMediaIntegrityApiStatusConfig.WEBVIEW_MEDIA_INTEGRITY_API_ENABLED_WITHOUT_APP_IDENTITY).build())
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = object : PlayerChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (BuildConfig.DEBUG) android.util.Log.d("TVYouTube", message.message().take(300))
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    controller.error("The YouTube player stopped. Press OK to retry.")
                    return true
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.isForMainFrame || !WebAddressPolicy.frame(request.url.toString())
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = PlayerWebSecurity.blockedResource(request)
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) controller.error("YouTube could not connect. Press OK to retry.")
                }
            }
            controller.attach(this)
        }
    }
    LaunchedEffect(source.id, strict, retry) {
        controller.loading()
        val page = YouTubeIdentity.livePage(source)
        val resolved = if (page.isNotBlank()) runCatching { YouTubeIdentity.resolve(page) }.getOrNull() else null
        currentCoroutineContext().ensureActive()
        val id = resolved ?: YouTubeIdentity.videoId(source.url)
        if (id == null) {
            controller.error("No current live broadcast could be found. Press OK to retry.")
            return@LaunchedEffect
        }
        controller.starting(id)
        val origin = "https://${context.packageName}"
        val host = if (strict) "https://www.youtube-nocookie.com" else "https://www.youtube.com"
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"/>
            <meta name="referrer" content="strict-origin-when-cross-origin"/>
            <style>html,body,#player{margin:0;width:100%;height:100%;background:#000;overflow:hidden}</style></head><body>
            <div id="player"></div><script src="https://www.youtube.com/iframe_api"></script><script>
            var player,ukTvEvents=[];function report(name,code){ukTvEvents.push([name,code]);if(ukTvEvents.length>12)ukTvEvents.shift();}
            function onYouTubeIframeAPIReady(){player=new YT.Player('player',{
              host:'$host',videoId:'$id',playerVars:{autoplay:1,controls:1,playsinline:1,rel:0,origin:'$origin'},
              events:{onReady:function(e){report('ready',0);e.target.playVideo();},onStateChange:function(e){report('state',e.data);},
              onError:function(e){report('error',e.data);},onAutoplayBlocked:function(){report('blocked',0);}}});}
            </script></body></html>"""
        webView.loadDataWithBaseURL("$origin/", html, "text/html", "UTF-8", null)
    }
    // Poll the app-owned main document. No Java object is exposed to the provider's iframes.
    LaunchedEffect(webView) {
        while (isActive) {
            delay(750)
            val raw = withTimeoutOrNull(2000) {
                suspendCancellableCoroutine<String> { continuation ->
                    webView.evaluateJavascript("JSON.stringify((window.ukTvEvents||[]).splice(0,12))") { value ->
                        if (continuation.isActive) continuation.resume(value)
                    }
                }
            } ?: continue
            if (controller.attached(webView)) WebPlayerEvents.parse(raw).forEach { (name, code) -> controller.event(name, code) }
        }
    }
    DisposableEffect(webView) {
        onDispose { controller.detach(webView); webView.stopLoading(); webView.clearHistory(); webView.destroy() }
    }
    Box(modifier) {
        key(webView) { AndroidView(factory = { webView }, update = { it.isFocusable = interactive; it.isFocusableInTouchMode = interactive }, modifier = Modifier.fillMaxSize()) }
        if (controller.failed || !controller.playing) Text(controller.status,
            color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.9f)).padding(12.dp))
    }
}
