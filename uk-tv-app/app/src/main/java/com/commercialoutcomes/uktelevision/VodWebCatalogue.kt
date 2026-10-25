package com.commercialoutcomes.uktelevision

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Some providers render their public catalogue in JavaScript. Read that page
 * behind the native library; never expose the provider's browsing UI or execute
 * page scripts in the Android process. Only one bounded page is loaded at a time. */
object VodWebCatalogue {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(activity: Activity, provider: VodProvider, url: String=provider.home, minimumTitles:Int=1): Document = withContext(Dispatchers.Main) {
        require(provider.owns(url))
        suspendCancellableCoroutine { continuation ->
            val handler=Handler(Looper.getMainLooper())
            val parent=activity.window.decorView as ViewGroup
            val web=WebView(activity)
            var finished=false
            val deadline=android.os.SystemClock.elapsedRealtime()+45000
            var bestDocument:Document?=null
            var lastCount=0
            var unchanged=0
            fun cleanup() {
                finished=true;handler.removeCallbacksAndMessages(null)
                parent.removeView(web);web.stopLoading();web.destroy()
            }
            fun fail(message: String) { if(!finished) { cleanup();if(continuation.isActive)continuation.resumeWithException(IllegalStateException(message)) } }
            web.apply {
                alpha=0f;isClickable=false;isFocusable=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                PlayerWebSecurity.configure(this)
                webChromeClient=PlayerChromeClient()
                settings.javaScriptEnabled=true;settings.domStorageEnabled=true
                settings.mediaPlaybackRequiresUserGesture=true;settings.allowFileAccess=false;settings.allowContentAccess=false
                settings.useWideViewPort=true;settings.loadWithOverviewMode=true
                webViewClient=object:WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = if(request.isForMainFrame) !provider.owns(request.url.toString()) else !WebAddressPolicy.frame(request.url.toString())
                    override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest)=PlayerWebSecurity.blockedResource(request)
                    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean { fail("${provider.label} catalogue stopped loading");return true }
                }
            }
            parent.addView(web,ViewGroup.LayoutParams(parent.width.coerceAtLeast(1280),parent.height.coerceAtLeast(720)))
            val poll=object:Runnable {
                override fun run() {
                    if(finished)return
                    if(android.os.SystemClock.elapsedRealtime()>=deadline) { val best=bestDocument;if(best!=null){cleanup();if(continuation.isActive)continuation.resume(best)}else fail("${provider.label} catalogue did not load on this connection");return }
                    web.evaluateJavascript("document.documentElement.outerHTML.slice(0,6000000)") { raw ->
                        if(!finished) {
                            val html=runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
                            val doc=Jsoup.parse(html,web.url ?: url)
                            val rows=runCatching { VodCatalogue.parse(provider,doc) }.getOrDefault(emptyList())
                            if(rows.isNotEmpty())bestDocument=doc
                            unchanged=if(rows.size==lastCount)unchanged+1 else 0;lastCount=rows.size
                            if(rows.size>=minimumTitles || (rows.isNotEmpty()&&unchanged>=10)) { cleanup();if(continuation.isActive)continuation.resume(doc) }
                            else {
                                if(provider==VodProvider.DULO && rows.isNotEmpty())web.evaluateJavascript("window.scrollTo(0,document.documentElement.scrollHeight)",null)
                                handler.postDelayed(this,1000)
                            }
                        }
                    }
                }
            }
            continuation.invokeOnCancellation { handler.post { if(!finished)cleanup() } }
            web.loadUrl(url);handler.postDelayed(poll,3000)
            handler.postDelayed({ fail("${provider.label} catalogue did not load on this connection") },46000)
        }
    }
}
