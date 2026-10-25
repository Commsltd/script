package com.commercialoutcomes.uktelevision

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** A broadcaster player shared by the guide preview and full-screen surface. */
@SuppressLint("SetJavaScriptEnabled")
class LiveWebPlayer(context:Context, val station:String):FrameLayout(context) {
    val web=object:WebView(context) {
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);if(w>0&&h>0)post { fitPlayer() }}
    }
    private val handler=Handler(Looper.getMainLooper())
    private val message=TextView(context).apply {
        setTextColor(Color.WHITE);setBackgroundColor(Color.BLACK);textSize=16f;gravity=Gravity.CENTER;setPadding(20,20,20,20)
        text="Loading broadcaster player…"
    }
    private var closed=false
    private var cleanTv6=false
    private var custom:View?=null
    private var callback:WebChromeClient.CustomViewCallback?=null
    val pageErrors=mutableListOf<String>()
    init {
        setBackgroundColor(Color.BLACK)
        web.setBackgroundColor(Color.BLACK)
        PlayerWebSecurity.configure(web)
        web.settings.apply {
            javaScriptEnabled=true;domStorageEnabled=true;mediaPlaybackRequiresUserGesture=false
            allowFileAccess=false;allowContentAccess=false;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort=true;loadWithOverviewMode=true;setSupportMultipleWindows(false)
        }
        if(BuildConfig.DEBUG)WebView.setWebContentsDebuggingEnabled(true)
        val strict=context.getSharedPreferences("preferences",Context.MODE_PRIVATE).getString("privacyMode","")==PrivacyMode.STRICT.name
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,!strict)
        web.isFocusable=false
        web.webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                val url=request.url.toString()
                return if(request.isForMainFrame) setOf("player.tegotv.com","www.tv6tnt.com").none { WebAddressPolicy.ownedBy(url,it) } else !WebAddressPolicy.frame(url)
            }
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest)=PlayerWebSecurity.blockedResource(request)
            override fun onPageFinished(view:WebView,url:String){fitPlayer()}
            override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean {
                message.text="Broadcaster player stopped. MENU → Reload broadcaster.";message.visibility=VISIBLE;web.visibility=GONE;return true
            }
        }
        web.webChromeClient=object:PlayerChromeClient(){
            override fun onConsoleMessage(m:ConsoleMessage):Boolean {if(m.messageLevel()==ConsoleMessage.MessageLevel.ERROR&&pageErrors.size<10)pageErrors.add(m.message().substringBefore('?').take(200));return true}
            override fun onShowCustomView(view:View,cb:CustomViewCallback){if(custom!=null){cb.onCustomViewHidden();return};custom=view;callback=cb;addView(view,LayoutParams(-1,-1))}
            override fun onHideCustomView(){custom?.let(::removeView);custom=null;callback?.onCustomViewHidden();callback=null}
        }
        addView(web,LayoutParams(-1,-1));addView(message,LayoutParams(-1,-1))
        web.loadUrl(LiveWebActivity.pages.getValue(station))
        fitPlayer()
    }
    private fun fitPlayer() {
        handler.removeCallbacksAndMessages(null)
        var attempts=0
        val poll=object:Runnable { override fun run() {
            if(closed)return
            val script=if(station=="CCNTV6.tt") """(()=>{const f=[...document.querySelectorAll('iframe')].find(f=>{try{return new URL(f.src).hostname.endsWith('.dailymotion.com')}catch(e){return false}});if(!f)return false;f.style.cssText+=';position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;border:0!important;margin:0!important;padding:0!important;opacity:1!important;visibility:visible!important;display:block!important;z-index:1000!important';for(let p=f.parentElement;p&&p!==document.body;p=p.parentElement){p.style.setProperty('transform','none','important');p.style.setProperty('position','static','important');p.style.setProperty('overflow','visible','important');p.style.setProperty('contain','none','important');p.style.setProperty('filter','none','important');p.style.setProperty('perspective','none','important')}document.body.style.overflow='hidden';return f.src})()""" else """(()=>{const p=document.querySelector('#rmpPlayer');if(!p)return false;p.style.cssText+=';position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important';for(const v of document.querySelectorAll('video'))v.style.objectFit='contain';return true})()"""
            web.evaluateJavascript(script) { ready->
                if(closed)return@evaluateJavascript
                val frame=if(station=="CCNTV6.tt")runCatching { org.json.JSONTokener(ready).nextValue() as? String }.getOrNull() else null
                if(!cleanTv6 && frame!=null && android.net.Uri.parse(frame).host.orEmpty().endsWith(".dailymotion.com")) {
                    cleanTv6=true
                    val safe=android.text.TextUtils.htmlEncode(frame)
                    // Keep the broadcaster origin and the complete official
                    // ad/consent player, without loading the surrounding news page.
                    web.loadDataWithBaseURL(LiveWebActivity.pages.getValue(station),"""<html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body style="margin:0;background:black;overflow:hidden"><iframe src="$safe" allow="autoplay;fullscreen;picture-in-picture" allowfullscreen style="position:fixed;inset:0;width:100vw;height:100vh;border:0"></iframe></body></html>""","text/html","UTF-8",LiveWebActivity.pages.getValue(station))
                    return@evaluateJavascript
                }
                if(ready=="true" || (cleanTv6 && frame!=null))message.visibility=GONE
                else if(attempts>=10)message.text="Waiting for TV6’s live player…\nLive broadcasts use Trinidad time (AST):\nWeekdays 06:00–08:00 and 18:00–20:30\nWeekends 19:00–19:30 · Sunday also 18:30–19:00\nMENU → Broadcaster controls if consent is needed"
                if(attempts++<120)handler.postDelayed(this,1500)
            }
        }};handler.post(poll)
    }
    fun showControls() {
        // Provider actions are only clicked in response to the user's choice.
        web.evaluateJavascript("""(()=>{const visible=e=>e.getBoundingClientRect().width>0&&e.getBoundingClientRect().height>0;window.ukTvButtons=[...document.querySelectorAll('button,[role="button"]')].filter(b=>visible(b)&&(b.innerText.trim()||b.getAttribute('aria-label')));return window.ukTvButtons.map(b=>(b.innerText.trim()||b.getAttribute('aria-label')).slice(0,120))})()""") { result->
            if(closed)return@evaluateJavascript
            val labels=runCatching{JSONArray(result)}.getOrNull()?:JSONArray()
            if(labels.length()==0){AlertDialog.Builder(context).setTitle("Broadcaster controls").setMessage(if(station=="CCNTV6.tt")"TV6 broadcasts only at its published live times. The player displays its own off-air notice between broadcasts." else "No broadcaster buttons are currently available.").setPositiveButton("Back",null).show();return@evaluateJavascript}
            AlertDialog.Builder(context).setTitle("Broadcaster controls").setItems(Array(labels.length()){labels.getString(it)}){_,i->
                web.evaluateJavascript("""(()=>{const b=window.ukTvButtons?.[$i];if(b&&document.contains(b)&&!b.disabled)b.click()})()""",null)
            }.setNegativeButton("Back",null).show()
        }
    }
    fun reload(){message.visibility=VISIBLE;message.text="Loading broadcaster player…";cleanTv6=false;web.loadUrl(LiveWebActivity.pages.getValue(station));fitPlayer()}
    fun close(){if(closed)return;closed=true;handler.removeCallbacksAndMessages(null);callback?.onCustomViewHidden();removeAllViews();web.stopLoading();web.destroy()}
}
