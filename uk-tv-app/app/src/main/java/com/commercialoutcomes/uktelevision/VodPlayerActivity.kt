package com.commercialoutcomes.uktelevision

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.launch
import android.widget.Toast
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
class VodPlayerActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    lateinit var web: WebView; private set
    private var custom: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var provider: VodProvider
    private var consentControls: WebConsentControls? = null
    private var streamControls: VodStreamControls? = null
    private var sources=emptyList<VodSource>()
    private var sourceIndex=0
    private var servicesDialog:AlertDialog?=null
    private var webFailed=false
    private var qualityControls:VodQualityControls?=null
    private var tubiControls:TubiTvControls?=null
    var nativePlayback:NativeVodPlayback?=null;private set
    private lateinit var defaultAgent: String
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { closeFullscreen();finish() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        provider = runCatching { VodProvider.valueOf(intent.getStringExtra("provider").orEmpty()) }.getOrElse { finish(); return }
        val target = intent.getStringExtra("url").orEmpty()
        val urls=intent.getStringArrayExtra("sourceUrls").orEmpty()
        val providers=intent.getStringArrayExtra("sourceProviders").orEmpty()
        sources=urls.mapIndexedNotNull { i,url -> runCatching { val p=VodProvider.valueOf(providers[i]);if(p.owns(url))VodSource(p,url) else null }.getOrNull() }
        sourceIndex=intent.getIntExtra("sourceIndex",0).coerceIn(0,(sources.size-1).coerceAtLeast(0))
        if(!provider.owns(target)) { finish(); return }
        root = FrameLayout(this)
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            PlayerWebSecurity.configure(this)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            setOnKeyListener { _, code, event ->
                if(code==KeyEvent.KEYCODE_MENU) {
                    if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)showServices()
                    true
                } else false
            }
            // Full desktop layouts expose playback controls on TV-sized screens.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            defaultAgent=settings.userAgentString
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
                    if (request.isForMainFrame) !provider.owns(request.url.toString()) else !WebAddressPolicy.frame(request.url.toString())
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = PlayerWebSecurity.blockedResource(request)
                override fun onPageFinished(view: WebView, url: String) {
                    if(!provider.owns(url))return
                    if(provider==VodProvider.PLEX) consentControls?.check(false)
                    if(provider==VodProvider.MOVY) fitMovyViewport()
                    if(provider==VodProvider.PLUTO) startChosenPlutoTitle()

                    if(provider==VodProvider.DULO) web.evaluateJavascript("""(()=>{const style=document.createElement('style');style.textContent='video{object-fit:contain!important}';document.head.appendChild(style)})()""",null)
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    consentControls?.close();streamControls?.close();qualityControls?.close();tubiControls?.close();nativePlayback?.close()
                    handler.removeCallbacksAndMessages(null);closeFullscreen();root.removeView(view);view.destroy();webFailed=true
                    AlertDialog.Builder(this@VodPlayerActivity).setMessage("The provider player stopped. Please reopen On Demand.").setPositiveButton("Return to library") { _, _ -> finish() }.show()
                    return true
                }
            }
            webChromeClient = object : PlayerChromeClient() {
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
        // Apply the provider's own TV quality preference before its early
        // player starts. Unbounded 4K playback exhausts a 1 GB Firestick.
        if(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val memory=android.app.ActivityManager.MemoryInfo()
            (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memory)
            val quality=if(memory.totalMem<=1536L*1024*1024)"720p" else "1080p"
            WebViewCompat.addDocumentStartJavaScript(web,"""try{const key='dulo_tv_preferences_v1';const p=JSON.parse(localStorage.getItem(key)||'{}');if(p.preferredQuality!=='720p')p.preferredQuality='$quality';localStorage.setItem(key,JSON.stringify(p))}catch(e){}""",setOf("https://dulo.mov"))
        }
        web.loadUrl(target)
        prepareProviderControls()
        web.requestFocus()
        Toast.makeText(this,"Press MENU to change stream or provider",Toast.LENGTH_LONG).show()
    }
    private fun prepareProviderControls() {
        consentControls?.close();consentControls=null;qualityControls?.close();tubiControls?.close();tubiControls=null;nativePlayback?.close();nativePlayback=null
        if(provider==VodProvider.TUBI)tubiControls=TubiTvControls(this,root,web,intent.getStringExtra("title").orEmpty(),::showServices)
        if(provider==VodProvider.PLEX){consentControls=WebConsentControls(this,web).also{it.start()};startWithSound()}
        if(provider==VodProvider.MOVY)qualityControls=VodQualityControls(web).also{it.startAt720p()}
        if(provider in setOf(VodProvider.MOVY,VodProvider.DULO))nativePlayback=NativeVodPlayback(this,root,web).also{it.start()}

    }
    private fun soundOn() {
        nativePlayback?.player?.volume=1f
        web.evaluateJavascript("""(()=>{const b=document.querySelector('button[aria-label="Unmute"]');if(b)b.click();else for(const v of document.querySelectorAll('video')){v.muted=false;v.volume=1}})()""",null)
    }
    private fun startWithSound() {
        var attempts=0
        val poll=object:Runnable { override fun run() {
            if(isFinishing || isDestroyed || attempts++>=90)return
            web.evaluateJavascript("""(()=>{const v=[...document.querySelectorAll('video')].find(v=>!v.paused&&v.readyState>=2);if(!v)return 0;return v.muted||v.volume===0?1:2})()""") { state ->
                if(state=="1")soundOn()
                // One initial unmute per navigation; later user mute choices stay put.
                if(state=="0")handler.postDelayed(this,1000)
            }
        }};handler.post(poll)
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
    private fun changeStream() {
        val native=nativePlayback
        if(native?.player!=null){native.returnToWeb(ready=::showStreamChoices);return}
        showStreamChoices()
    }
    private fun showStreamChoices() {
        if(provider !in setOf(VodProvider.MOVY,VodProvider.DULO)) { chooseProvider();return }
        streamControls?.close()
        val controls=VodStreamControls(web,provider);streamControls=controls
        controls.list { choices ->
            if(isFinishing || isDestroyed)return@list
            if(choices.isEmpty()) {
                AlertDialog.Builder(this).setTitle("Streams not ready")
                    .setMessage("${provider.label} has not exposed its server choices yet. Wait for the player to load, then try again.")
                    .setPositiveButton("Try again") { _,_->changeStream() }
                    .setNeutralButton("Other providers") { _,_->chooseProvider() }.setNegativeButton("Back",null).show()
            } else {
                val labels=choices.map { (if(it.selected)"✓ " else "")+it.label }
                AlertDialog.Builder(this).setTitle("Change stream · ${provider.label}")
                    .setItems(labels.toTypedArray()) { _,i -> controls.choose(choices[i]) { changed ->
                        Toast.makeText(this,if(changed)"Changing stream to ${choices[i].label}" else "Choices changed. Open Change stream again.",Toast.LENGTH_LONG).show()
                        web.requestFocus()
                        if(changed) { if(provider==VodProvider.MOVY)qualityControls?.startAt720p();nativePlayback?.start() }
                    } }
                    .setNeutralButton("Other providers") { _,_->chooseProvider() }.setNegativeButton("Back",null).show()
            }
        }
    }
    private fun changeQuality() {
        AlertDialog.Builder(this).setTitle("Picture quality")
            .setItems(arrayOf("720p · recommended for this Firestick","480p · lower data use","1080p · may stutter on this Firestick")){_,index->
                val height=listOf(720,480,1080)[index]
                val selectQuality = {
                  qualityControls?.choose(height){changed->
                    Toast.makeText(this,if(changed)"${height}p · playback position retained" else "That quality is not available from this source",Toast.LENGTH_LONG).show()
                    if(changed&&height<=720)nativePlayback?.start()
                  }
                  Unit
                }
                val native=nativePlayback
                if(native?.player!=null)native.returnToWeb(ready=selectQuality) else selectQuality()
            }.setNegativeButton("Back",null).show()
    }
    private fun chooseProvider() {
        if(sources.size<2) {
            AlertDialog.Builder(this).setTitle("Other providers").setMessage("No other provider is listed for this title yet. ${if(provider in setOf(VodProvider.MOVY,VodProvider.DULO))"Use Change stream to try this provider’s other servers." else "You can reload this source or return to the library."}")
                .setPositiveButton("Back",null).show();return
        }
        AlertDialog.Builder(this).setTitle("Choose provider · ${intent.getStringExtra("title")}")
            .setSingleChoiceItems(sources.map { it.provider.label }.toTypedArray(),sourceIndex) { dialog,i ->
                dialog.dismiss();if(i!=sourceIndex)switchProvider(i)
            }.setNegativeButton("Back",null).show()
    }
    private fun switchProvider(index: Int) {
        val source=sources[index]
        Toast.makeText(this,"Opening ${source.provider.label}…",Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            runCatching { VodCatalogue.playbackPage(source) }.onSuccess { url ->
                consentControls?.close();streamControls?.close();qualityControls?.close();tubiControls?.close();nativePlayback?.close();handler.removeCallbacksAndMessages(null);closeFullscreen()
                web.stopLoading();provider=source.provider;sourceIndex=index
                web.settings.userAgentString=if(provider==VodProvider.TUBI)defaultAgent.replaceFirst(Regex("\\([^)]*\\)"),"(X11; Linux x86_64)").replace(" Version/4.0","").replace(" Mobile","") else defaultAgent
                web.loadUrl(url)
                prepareProviderControls()
                web.requestFocus()
            }.onFailure { AlertDialog.Builder(this@VodPlayerActivity).setTitle("Source unavailable").setMessage(it.message).setPositiveButton("Back",null).show() }
        }
    }
    private fun duloSeries():String? {
        if(provider!=VodProvider.DULO)return null
        val path=runCatching{java.net.URI(nativePlayback?.providerPage?.takeIf{it.isNotBlank()} ?: web.url.orEmpty()).path}.getOrDefault("")
        val id=Regex("^/(?:tv/)?watch/tv/(\\d+)/?$").matchEntire(path)?.groupValues?.get(1) ?: return null
        return "https://dulo.mov/title/tv/$id/"
    }
    private fun chooseEpisode(selectedSource:VodSource?=null) {
        val source=selectedSource ?: VodSource(VodProvider.DULO,duloSeries()?:return)
        val wait=AlertDialog.Builder(this).setMessage("Loading seasons and episodes…").setNegativeButton("Cancel",null).create()
        val job=lifecycleScope.launch {
            try {
                val rows=VodEpisodes.load(source)
                wait.dismiss()
                if(rows.isEmpty())throw IllegalStateException("The provider did not supply episodes for this season.")
                AlertDialog.Builder(this@VodPlayerActivity).setTitle(if(rows.first().kind=="season")"Choose season" else "Choose episode")
                    .setItems(rows.map{it.title}.toTypedArray()){_,i->
                        val selected=rows[i]
                        if(selected.kind=="season")chooseEpisode(selected.sources.first()) else lifecycleScope.launch {
                            try {
                                val url=VodCatalogue.playbackPage(selected.sources.first())
                                consentControls?.close();streamControls?.close();qualityControls?.close();tubiControls?.close();nativePlayback?.close()
                                handler.removeCallbacksAndMessages(null);closeFullscreen();web.stopLoading()
                                web.loadUrl(url);prepareProviderControls();web.requestFocus()
                            } catch(e:kotlinx.coroutines.CancellationException){throw e}
                            catch(e:Exception){AlertDialog.Builder(this@VodPlayerActivity).setTitle("Episode unavailable").setMessage(e.message).setPositiveButton("Back",null).show()}
                        }
                    }.setNegativeButton("Back",null).show()
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){wait.dismiss();AlertDialog.Builder(this@VodPlayerActivity).setMessage(e.message).setPositiveButton("Back",null).show()}
        }
        wait.setOnCancelListener{job.cancel()}
        wait.setOnShowListener{wait.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener{job.cancel();wait.dismiss()}}
        wait.show()
    }
    private fun showServices() {
        if(webFailed||servicesDialog?.isShowing==true)return
        val labels=buildList {
            if(provider in setOf(VodProvider.MOVY,VodProvider.DULO))add("Change stream · poor copy or buffering")
            if(provider==VodProvider.MOVY)add("Picture quality")
            if(duloSeries()!=null)add("Choose season / episode")
            add("Sound on")
            add("Choose another provider")
            if(provider==VodProvider.PLEX)add("Consent choices")
            add("VPN / country")
            if(provider==VodProvider.TUBI)add("Playback controls")
            add("Reload this source");add("Return to library")
        }
        servicesDialog=AlertDialog.Builder(this).setTitle("UK TV · ${provider.label}")
            .setItems(labels.toTypedArray()) { _, i -> when(labels[i]) {
                "Change stream · poor copy or buffering" -> changeStream()
                "Picture quality" -> changeQuality()
                "Choose season / episode" -> chooseEpisode()
                "Sound on" -> soundOn()
                "Choose another provider" -> chooseProvider()
                "Consent choices" -> consentControls?.check()
                "VPN / country" -> VpnShortcut.open(this, provider.label)
                "Playback controls" -> tubiControls?.show()
                "Reload this source" -> { streamControls?.close();nativePlayback?.close();handler.removeCallbacksAndMessages(null);web.loadUrl(nativePlayback?.providerPage?.takeIf{it.isNotBlank()} ?: web.url.orEmpty());prepareProviderControls() }
                else -> finish()
            } }.show()
    }
    // Intercept TV hardware keys before either WebView or its descendants take focus.
    // AndroidX annotates this inherited framework override as library-restricted.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if(::provider.isInitialized&&provider==VodProvider.TUBI&&!webFailed) {
            if(event.keyCode==KeyEvent.KEYCODE_MENU) {
                if(event.action==KeyEvent.ACTION_DOWN&&event.repeatCount==0)showServices()
                return true
            }
            if(tubiControls?.key(event)==true)return true
            if(event.keyCode==KeyEvent.KEYCODE_BACK) {
                if(event.action==KeyEvent.ACTION_DOWN&&event.repeatCount==0)finish()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if(keyCode==KeyEvent.KEYCODE_MENU) { if(event.repeatCount==0)showServices();return true }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            nativePlayback?.player?.let{if(it.isPlaying)it.pause() else it.play();return true}
            // Ask the provider player to toggle. Never resume a content video
            // that its advertising player deliberately paused.
            sendPlayerKey(if(provider==VodProvider.TUBI)KeyEvent.KEYCODE_K else KeyEvent.KEYCODE_SPACE)
            return true
        }
        return super.onKeyDown(keyCode,event)
    }
    override fun onPause() { consentControls?.close();qualityControls?.close();streamControls?.close();handler.removeCallbacksAndMessages(null);tubiControls?.pause();nativePlayback?.pause();if(::web.isInitialized&&!webFailed)web.onPause(); super.onPause() }
    override fun onResume() { super.onResume();qualityControls?.resume();if(provider==VodProvider.PLEX&&!webFailed)consentControls?.start();tubiControls?.resume();nativePlayback?.resume();if(::web.isInitialized&&!webFailed)web.onResume() }
    override fun onStop() { nativePlayback?.background(); super.onStop() }
    override fun onDestroy() { consentControls?.close();streamControls?.close();qualityControls?.close();tubiControls?.close();if(!webFailed)nativePlayback?.close();handler.removeCallbacksAndMessages(null); closeFullscreen(); if(::web.isInitialized&&!webFailed){ root.removeView(web); web.stopLoading(); web.destroy() }; super.onDestroy() }
}
