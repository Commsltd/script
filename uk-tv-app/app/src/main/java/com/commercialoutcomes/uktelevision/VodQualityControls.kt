package com.commercialoutcomes.uktelevision

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/** Use the provider's real quality selector and restore the current episode position. */
class VodQualityControls(private val web:WebView) {
    private val handler=Handler(Looper.getMainLooper())
    private var closed=false
    fun close(){closed=true;handler.removeCallbacksAndMessages(null)}
    fun choose(height:Int, done:(Boolean)->Unit={}) {
        require(height in listOf(480,720,1080))
        web.evaluateJavascript("""(()=>{const v=document.querySelector('video');const b=[...document.querySelectorAll('button')].find(b=>new RegExp('^${height}p(?:\\s|$)').test(b.innerText.trim()));if(!v||!b)return false;window.ukTvQualityRestore={time:v.currentTime,duration:v.duration,paused:v.paused,src:v.currentSrc};b.click();return true})()""") { raw ->
            if(closed)return@evaluateJavascript
            if(raw!="true"){done(false);return@evaluateJavascript}
            var attempts=0
            val poll=object:Runnable{override fun run(){
                if(closed)return
                web.evaluateJavascript("""(()=>{const s=window.ukTvQualityRestore,v=document.querySelector('video');if(!s||!v||v.readyState<2||Math.abs(v.duration-s.duration)>5||v.videoHeight!==$height)return false;if(s.time>3&&Math.abs(v.currentTime-s.time)>3)v.currentTime=s.time;if(s.paused)v.pause();delete window.ukTvQualityRestore;return true})()"""){ready->
                    if(closed)return@evaluateJavascript
                    if(ready=="true"||attempts++>=30)done(ready=="true") else handler.postDelayed(this,500)
                }
            }};handler.postDelayed(poll,500)
        }
    }
    fun startAt720p() {
        var attempts=0
        val poll=object:Runnable{override fun run(){
            if(closed)return
            web.evaluateJavascript("""(()=>{const v=document.querySelector('video');if(!v||v.paused||v.readyState<2||v.duration<600)return 0;return v.videoHeight>720?1:2})()"""){ready->
                if(closed)return@evaluateJavascript
                if(ready=="1")choose(720)
                else if(ready=="0"&&attempts++<90)handler.postDelayed(this,1000)
            }
        }};handler.post(poll)
    }
}
