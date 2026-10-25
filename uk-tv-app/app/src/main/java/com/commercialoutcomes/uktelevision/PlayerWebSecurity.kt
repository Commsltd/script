package com.commercialoutcomes.uktelevision

import android.webkit.*
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

object PlayerWebSecurity {
    @Suppress("DEPRECATION")
    fun configure(web: WebView) {
        web.settings.apply {
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(web.settings, true)
        }
        // A player page cannot start an APK/file download or invoke a native bridge.
        web.setDownloadListener { _, _, _, _, _ -> }
    }

    fun blockedResource(request: WebResourceRequest): WebResourceResponse? =
        if (WebAddressPolicy.resource(request.url.toString())) null else
            WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
}

/** Keep the existing deny-by-default permission behaviour explicit for every player. */
open class PlayerChromeClient : WebChromeClient() {
    override fun onPermissionRequest(request: PermissionRequest) = request.deny()
    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
    override fun onShowFileChooser(webView: WebView, callback: android.webkit.ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean {
        callback.onReceiveValue(null)
        return true
    }
}
