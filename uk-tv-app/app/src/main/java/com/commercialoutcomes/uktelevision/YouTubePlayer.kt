package com.commercialoutcomes.uktelevision

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewMediaIntegrityApiStatusConfig

private fun youtubeVideoId(raw: String): String? {
    val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
    val host = uri.host?.lowercase().orEmpty()
    return when {
        host == "youtu.be" -> uri.pathSegments.firstOrNull()
        host.endsWith("youtube.com") && uri.path == "/watch" -> uri.getQueryParameter("v")
        host.endsWith("youtube.com") && uri.path?.startsWith("/embed/") == true ->
            uri.pathSegments.getOrNull(1)
        else -> null
    }?.takeIf { it.matches(Regex("^[A-Za-z0-9_-]{6,20}$")) }
}

private fun youtubeHtml(videoId: String, strict: Boolean): String {
    val host = if (strict) "www.youtube-nocookie.com" else "www.youtube.com"
    val src = "https://$host/embed/$videoId?autoplay=1&controls=0&playsinline=1&rel=0&modestbranding=1"
    return """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1"/>
          <style>
            html,body,#frame { margin:0; width:100%; height:100%; background:#000; overflow:hidden; }
            iframe { width:100%; height:100%; border:0; display:block; }
          </style>
        </head>
        <body>
          <iframe id="frame"
            src="$src"
            allow="autoplay; encrypted-media; picture-in-picture"
            referrerpolicy="strict-origin-when-cross-origin"
            allowfullscreen></iframe>
        </body>
        </html>
    """.trimIndent()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbeddedYouTubePlayer(
    source: StreamSource,
    strict: Boolean,
    modifier: Modifier = Modifier,
    interactive: Boolean = false
) {
    val context = LocalContext.current
    val videoId = remember(source.url) { youtubeVideoId(source.url) }

    val webView = remember(videoId, strict, interactive) {
        WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = interactive
            isFocusableInTouchMode = interactive
            keepScreenOn = true

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEBVIEW_MEDIA_INTEGRITY_API_STATUS)) {
                val config = WebViewMediaIntegrityApiStatusConfig.Builder(
                    WebViewMediaIntegrityApiStatusConfig.WEBVIEW_MEDIA_INTEGRITY_API_ENABLED_WITHOUT_APP_IDENTITY
                ).build()
                WebSettingsCompat.setWebViewMediaIntegrityApiStatus(settings, config)
            }

            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest
                ): Boolean = request.isForMainFrame

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail
                ): Boolean {
                    view.destroy()
                    return true
                }
            }

            if (videoId != null) {
                loadDataWithBaseURL(
                    "https://commsltd.github.io/uk-television/",
                    youtubeHtml(videoId, strict),
                    "text/html",
                    "UTF-8",
                    null
                )
            } else {
                loadDataWithBaseURL(
                    "https://commsltd.github.io/uk-television/",
                    "<html><body style='background:#000;color:#fff;font-family:sans-serif'><h2>Official live video is temporarily unavailable.</h2></body></html>",
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            CookieManager.getInstance().removeAllCookies(null)
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier
    )
}
