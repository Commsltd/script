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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewMediaIntegrityApiStatusConfig

private fun youtubeEmbedUrl(raw: String, strict: Boolean): String? {
    val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
    val host = uri.host?.lowercase().orEmpty()
    val base = when {
        host == "youtu.be" -> {
            val id = uri.pathSegments.firstOrNull() ?: return null
            "https://www.youtube.com/embed/$id?autoplay=1&controls=1&playsinline=1&rel=0"
        }
        host.endsWith("youtube.com") && uri.path == "/watch" -> {
            val id = uri.getQueryParameter("v") ?: return null
            "https://www.youtube.com/embed/$id?autoplay=1&controls=1&playsinline=1&rel=0"
        }
        host.endsWith("youtube.com") && uri.path?.startsWith("/embed/") == true -> raw
        else -> null
    } ?: return null
    return if (strict) base.replace("://www.youtube.com/", "://www.youtube-nocookie.com/") else base
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbeddedYouTubePlayer(source: StreamSource, strict: Boolean) {
    val context = LocalContext.current
    val url = remember(source.url, strict) { youtubeEmbedUrl(source.url, strict) }

    val webView = remember(url) {
        WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
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
                ): Boolean {
                    if (!request.isForMainFrame) return false
                    val target = request.url
                    val h = target.host?.lowercase().orEmpty()
                    return !(h.endsWith("youtube.com") || h.endsWith("youtube-nocookie.com"))
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail
                ): Boolean {
                    view.destroy()
                    return true
                }
            }

            if (url != null) {
                loadUrl(
                    url,
                    mapOf("Referer" to "https://commsltd.github.io/uk-television/")
                )
            } else {
                loadData(
                    "<html><body style='background:#000;color:#fff;font-family:sans-serif'><h2>This YouTube URL cannot be embedded safely.</h2></body></html>",
                    "text/html",
                    "UTF-8"
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
        modifier = Modifier.fillMaxSize()
    )
}
