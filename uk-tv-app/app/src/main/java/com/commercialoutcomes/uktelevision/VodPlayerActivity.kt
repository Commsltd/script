package com.commercialoutcomes.uktelevision

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout

/** Official provider players stay in this activity, including their ads and consent controls. */
class VodPlayerActivity : Activity() {
    private lateinit var root: FrameLayout
    lateinit var web: WebView; private set
    private var custom: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var provider: VodProvider
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        provider = runCatching { VodProvider.valueOf(intent.getStringExtra("provider").orEmpty()) }.getOrElse { finish(); return }
        val target = intent.getStringExtra("url").orEmpty()
        if(!provider.owns(target)) { finish(); return }
        root = FrameLayout(this)
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            // Full desktop layouts expose playback controls on TV-sized screens.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            if(provider==VodProvider.TUBI) {
                // Tubi's mobile site serves a preview and an app-install link.
                // Request its desktop web player, retaining this engine's version.
                settings.userAgentString = settings.userAgentString
                    .replaceFirst(Regex("\\([^)]*\\)"), "(X11; Linux x86_64)")
                    .replace(" Version/4.0", "").replace(" Mobile", "")
            }
            val strict=getSharedPreferences("preferences",MODE_PRIVATE).getString("privacyMode","")==PrivacyMode.STRICT.name
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, !strict)
            if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && !provider.owns(request.url.toString())
                override fun onPageFinished(view: WebView, url: String) {
                    if(provider==VodProvider.MOVY) fitMovyViewport()
                    if(provider==VodProvider.PLUTO) startChosenPlutoTitle()
                    if(provider==VodProvider.TUBI) enterTubiFullscreen()
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    AlertDialog.Builder(this@VodPlayerActivity).setMessage("The provider player stopped. Please reopen On Demand.").setPositiveButton("Return to library") { _, _ -> finish() }.show()
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (custom != null) { callback.onCustomViewHidden(); return }
                    custom = view; customCallback = callback
                    root.addView(view, FrameLayout.LayoutParams(-1,-1))
                }
                override fun onHideCustomView() { closeFullscreen() }
            }
        }
        root.setBackgroundColor(Color.BLACK)
        root.addView(web, FrameLayout.LayoutParams(-1,-1))
        setContentView(root)
        web.loadUrl(target)
    }
    private fun fitMovyViewport() {
        // Movy's TV detection sets a 1280px layout at scale 1, but this
        // Firestick WebView has a 960px CSS viewport. Keep its TV controls
        // while making the layout match the actual visible screen.
        var attempts=0
        val poll=object:Runnable {
            override fun run() {
                if(isFinishing || isDestroyed || attempts++>=6)return
                web.evaluateJavascript("Math.min(visualViewport.width/innerWidth,visualViewport.height/innerHeight)") { value ->
                    value.toFloatOrNull()?.takeIf { it in 0.1f..0.99f }?.let { web.zoomBy(it) }
                    handler.postDelayed(this,1000)
                }
            }
        }
        handler.post(poll)
    }
    private fun sendPlayerKey(code: Int) {
        web.requestFocus()
        web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code))
        web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code))
    }
    private fun enterTubiFullscreen() {
        var attempts=0
        val poll=object:Runnable {
            override fun run() {
                if(isFinishing || isDestroyed || custom!=null || attempts++>=60)return
                web.evaluateJavascript("""(()=>{const v=document.querySelector('video[data-id="videoPlayerComponent"]');return !!document.fullscreenElement?2:(v && v.readyState>=2 && v.duration>120 && !v.paused?1:0)})()""") { result ->
                    if(result=="1")sendPlayerKey(KeyEvent.KEYCODE_F)
                    if(result!="2")handler.postDelayed(this,1500)
                }
            }
        }
        handler.post(poll)
    }
    private fun startChosenPlutoTitle() {
        // The native library selection is the user's play request. Pluto exposes
        // its own Play Now action after hydration; invoke it once, keeping its
        // session, advertising and any consent dialogue intact.
        var attempts=0
        val poll=object:Runnable {
            override fun run() {
                if(isFinishing || isDestroyed || attempts++>=60)return
                web.evaluateJavascript("""(()=>{if(window.ukTvVodStarted)return true;const play=document.querySelector('a[data-testid="main-cta"][data-movie-id],a[data-testid="main-cta"][data-episode-id]');const ready=play&&Object.keys(play).some(k=>k.startsWith('__reactProps')&&typeof play[k]?.onClick==='function');if(ready && /^(Play Now|Play|Resume|Watch Now)$/i.test(play.textContent.trim()) && play.getAttribute('data-content-locked')!=='1'){window.ukTvVodStarted=true;play.click();return true}return false})()""") { result ->
                    if(result!="true")handler.postDelayed(this,1000)
                }
            }
        }
        handler.post(poll)
    }
    private fun closeFullscreen() {
        custom?.let(root::removeView); custom = null
        customCallback?.onCustomViewHidden(); customCallback = null
    }
    private fun showServices() {
        val labels=buildList { if(intent.getIntExtra("alternativeCount",0)>0)add("Choose another source");if(provider==VodProvider.TUBI)add("Full screen");add("Reload this source");add("Return to library") }
        AlertDialog.Builder(this).setTitle("UK TV · On Demand")
            .setItems(labels.toTypedArray()) { _, i -> when(labels[i]) {
                "Choose another source" -> { setResult(RESULT_FIRST_USER);finish() }
                "Full screen" -> if(custom==null)sendPlayerKey(KeyEvent.KEYCODE_F)
                "Reload this source" -> web.reload()
                else -> finish()
            } }.show()
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { showServices(); return true }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            // Ask the provider player to toggle. Never resume a content video
            // that its advertising player deliberately paused.
            sendPlayerKey(if(provider==VodProvider.TUBI)KeyEvent.KEYCODE_K else KeyEvent.KEYCODE_SPACE)
            return true
        }
        return super.onKeyDown(keyCode,event)
    }
    @Deprecated("Legacy Fire OS back dispatch")
    override fun onBackPressed() {
        closeFullscreen();finish()
    }
    override fun onPause() { if(::web.isInitialized)web.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); if(::web.isInitialized)web.onResume() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); closeFullscreen(); if(::web.isInitialized){ root.removeView(web); web.stopLoading(); web.destroy() }; super.onDestroy() }
}
