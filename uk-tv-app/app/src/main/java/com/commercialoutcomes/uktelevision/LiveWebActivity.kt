package com.commercialoutcomes.uktelevision

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout

/** Broadcasters that require their own session/ad player still stay inside UK TV. */
class LiveWebActivity : Activity() {
    companion object {
        val pages=mapOf(
            "TTT.tt" to "https://player.tegotv.com/player.php?channel=1&about=0&chat=0&donation=0&epg=0&autoplay=1",
            "CCNTV6.tt" to "https://www.tv6tnt.com/watch_live/"
        )
    }
    lateinit var web: WebView;private set
    private lateinit var root: FrameLayout
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private var fullscreen: View?=null
    val pageErrors=mutableListOf<String>()
    private var callback: WebChromeClient.CustomViewCallback?=null
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val target=pages[intent.getStringExtra("station")] ?: run { finish();return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        root=FrameLayout(this)
        web=WebView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled=true;settings.domStorageEnabled=true;settings.mediaPlaybackRequiresUserGesture=false
            settings.allowFileAccess=false;settings.allowContentAccess=false;settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.useWideViewPort=true;settings.loadWithOverviewMode=true;settings.setSupportMultipleWindows(false)
            val strict=getSharedPreferences("preferences",MODE_PRIVATE).getString("privacyMode","")==PrivacyMode.STRICT.name
            CookieManager.getInstance().setAcceptThirdPartyCookies(this,!strict)
            webViewClient=object:WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.isForMainFrame && request.url.toString()!=target
                override fun onPageFinished(view:WebView,url:String) {
                    if(intent.getStringExtra("station")=="CCNTV6.tt")fitBroadcasterPlayer()
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { finish();return true }
            }
            webChromeClient=object:WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if(message.messageLevel()==ConsoleMessage.MessageLevel.ERROR && pageErrors.size<10)pageErrors.add(message.message().substringBefore("?").take(250))
                    return true
                }
                override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                    if(fullscreen!=null){cb.onCustomViewHidden();return}
                    fullscreen=view;callback=cb;root.addView(view,FrameLayout.LayoutParams(-1,-1))
                }
                override fun onHideCustomView() { closeFullscreen() }
            }
        }
        root.addView(web,FrameLayout.LayoutParams(-1,-1));setContentView(root);web.loadUrl(target)
    }
    private fun fitBroadcasterPlayer() {
        var attempts=0
        val poll=object:Runnable { override fun run() {
            if(isFinishing || isDestroyed || attempts++>=20)return
            web.evaluateJavascript("""(()=>{const f=[...document.querySelectorAll('iframe')].find(f=>{try{return new URL(f.src).hostname.endsWith('.dailymotion.com')}catch(e){return false}});if(!f)return false;f.style.cssText+=';position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;border:0!important;z-index:1000!important';for(let p=f.parentElement;p&&p!==document.body;p=p.parentElement){p.style.transform='none';p.style.overflow='visible'}document.body.style.overflow='hidden';f.focus();return true})()""") { ready -> if(ready!="true")handler.postDelayed(this,1000) }
        } };handler.post(poll)
    }
    private fun closeFullscreen(){fullscreen?.let(root::removeView);fullscreen=null;callback?.onCustomViewHidden();callback=null}
    override fun onKeyDown(code: Int,event:KeyEvent):Boolean {
        if(code==KeyEvent.KEYCODE_MENU){AlertDialog.Builder(this).setTitle(intent.getStringExtra("title"))
            .setItems(arrayOf("Reload channel","Return to TV guide")){_,i->if(i==0)web.reload()else finish()}.show();return true}
        return super.onKeyDown(code,event)
    }
    override fun onPause(){if(::web.isInitialized)web.onPause();super.onPause()}
    override fun onResume(){super.onResume();if(::web.isInitialized)web.onResume()}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);closeFullscreen();if(::web.isInitialized){root.removeView(web);web.stopLoading();web.destroy()};super.onDestroy()}
}
