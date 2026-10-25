package com.commercialoutcomes.uktelevision

import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener
import okhttp3.Request

/** The provider's normal player resolves the title. Hand off only its matching clear HLS
 * feature to Media3; credentials and media addresses are never saved or logged. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeVodPlayback(private val activity:ComponentActivity,private val root:FrameLayout,private val web:WebView) {
    var player:ExoPlayer?=null;private set
    var diagnostic="Waiting for the selected feature";private set
    private var view:PlayerView?=null
    private var job:Job?=null
    private var watchdog:Job?=null
    private var enabled=false
    private var generation=0
    private val rejected=mutableSetOf<String>()
    private var candidate=""
    var providerPage="";private set
    private var webUnloaded=false
    private var restoreJob:Job?=null
    private var lastPosition=0L
    private var resumeAfterPause=false
    fun start(){enabled=true;schedule()}
    private fun schedule(){
        job?.cancel()
        job=activity.lifecycleScope.launch {
            repeat(90){
                delay(2000)
                if(!enabled||player!=null)return@launch
                val snapshot=suspendCancellableCoroutine<JSONObject?> { c ->
                    web.evaluateJavascript("""(()=>{const v=document.querySelector('video');if(!v||v.paused||v.readyState<2||v.duration<600||v.videoHeight>720||window.ukTvQualityRestore)return null;return JSON.stringify({duration:v.duration,time:v.currentTime,urls:performance.getEntriesByType('resource').filter(r=>/[.]m3u8(?:[?]|$)/i.test(r.name)).map(r=>r.name).concat(v.currentSrc&&/^[hH][tT][tT][pP][sS]:/.test(v.currentSrc)?[v.currentSrc]:[]).slice(-12)})})()"""){raw->
                        if(c.isActive)c.resume(runCatching{JSONObject(JSONTokener(raw).nextValue().toString())}.getOrNull(),null)
                    }
                } ?: return@repeat
                val urls=snapshot.optJSONArray("urls")?:return@repeat
                diagnostic="Checking ${urls.length()} feature playlists"
                val page=web.url.orEmpty();val agent=web.settings.userAgentString
                for(i in urls.length()-1 downTo 0){
                    val url=urls.optString(i)
                    if(url in rejected)continue
                    val matches=withContext(Dispatchers.IO){runCatching{
                        if(!WebAddressPolicy.publicHttps(url))return@runCatching false
                        val request=Request.Builder().url(url).header("User-Agent",agent).apply {
                            PublicVodHttp.referer(page)?.let { header("Referer",it) }
                        }.build()
                        PublicVodHttp.client.newCall(request).execute().use { response ->
                            if(response.code!=200){diagnostic="Playlist HTTP ${response.code}";return@runCatching false}
                            val bytes=response.body?.byteStream()?.use { it.readBounded(1024*1024) } ?: return@runCatching false
                            VodHls.matches(bytes.toString(Charsets.UTF_8),snapshot.optDouble("duration"))
                        }
                    }.onFailure{diagnostic="Playlist request: ${it.javaClass.simpleName}"}.getOrDefault(false)}
                    if(matches){
                        candidate=url;providerPage=page;begin(url,page,agent,(snapshot.optDouble("time")*1000).toLong());return@launch
                    }
                    rejected.add(url)
                }
            }
        }
    }
    internal fun begin(url:String,page:String,agent:String,position:Long){
        if(!enabled)return
        candidate=url;providerPage=page
        val token=++generation
        val http=OkHttpDataSource.Factory(PublicVodHttp.client).setUserAgent(agent)
            .setDefaultRequestProperties(PublicVodHttp.referer(page)?.let { mapOf("Referer" to it) } ?: emptyMap())
        val buffer=DefaultLoadControl.Builder().setBufferDurationsMs(15000,45000,1500,4000)
            .setTargetBufferBytes(24*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build()
        val exo=ExoPlayer.Builder(activity,DefaultRenderersFactory(activity).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(http)).setLoadControl(buffer).build()
        player=exo;lastPosition=position;diagnostic="Native player preparing"
        exo.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
        exo.trackSelectionParameters=exo.trackSelectionParameters.buildUpon().setMaxVideoSize(1920,720).build()
        val surface=PlayerView(activity).apply{player=exo;controllerShowTimeoutMs=3000;setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);setKeepContentOnPlayerReset(false)}
        view=surface;root.addView(surface,FrameLayout.LayoutParams(-1,-1));surface.requestFocus()
        // Stop web decoding before starting the native renderer, avoiding two
        // decoders and two audio tracks on a 1 GB Firestick.
        web.evaluateJavascript("document.querySelectorAll('video').forEach(v=>v.pause())",null)
        web.visibility=View.INVISIBLE
        exo.addListener(object:Player.Listener{
            override fun onRenderedFirstFrame(){if(generation==token){
                diagnostic="Native picture playing";watchdog?.cancel()
                // Pausing a video does not release its MSE buffers or provider
                // scripts. Drop the document after native playback succeeds.
                if(!webUnloaded){webUnloaded=true;web.stopLoading();web.loadUrl("about:blank")}
            }}
            override fun onPlayerError(error:PlaybackException){if(generation==token){diagnostic="Native playback: ${error.errorCodeName}";rejected.add(candidate);returnToWeb(true)}}
        })
        exo.setMediaItem(MediaItem.fromUri(url));exo.seekTo(position);exo.prepare();exo.play()
        watchdog=activity.lifecycleScope.launch{delay(20000);if(generation==token&&(exo.videoDecoderCounters?.renderedOutputBufferCount?:0)==0){rejected.add(candidate);returnToWeb(true)}}
    }
    fun returnToWeb(resume:Boolean=true, ready:()->Unit={}) {
        val exo=player
        val position=exo?.currentPosition?.takeIf{it>0}?:lastPosition
        lastPosition=position
        releaseNative()
        web.visibility=View.VISIBLE
        if(webUnloaded && providerPage.isNotBlank()) {
            webUnloaded=false
            web.loadUrl(providerPage)
            restoreJob=activity.lifecycleScope.launch {
                repeat(60) {
                    delay(500)
                    if(!enabled)return@launch
                    val restored=suspendCancellableCoroutine<Boolean> { c ->
                        web.evaluateJavascript("""(()=>{const v=document.querySelector('video');if(!v||v.readyState<1||!Number.isFinite(v.duration))return false;if(${position}>0)v.currentTime=Math.min(${position/1000.0},Math.max(0,v.duration-1));${if(resume)"v.play().catch(()=>{})" else "v.pause()"};return true})()""") { raw ->
                            if(c.isActive)c.resume(raw=="true",null)
                        }
                    }
                    if(restored){web.requestFocus();ready();return@launch}
                }
                web.requestFocus();ready()
            }
        } else {
            if(exo!=null)web.evaluateJavascript("""(()=>{const v=document.querySelector('video');if(v){v.currentTime=${position/1000.0};${if(resume)"v.play().catch(()=>{})" else "v.pause()"}}})()""",null)
            web.requestFocus();ready()
        }
    }
    private fun releaseNative() {
        generation++;watchdog?.cancel();job?.cancel();restoreJob?.cancel()
        view?.player=null;view?.let(root::removeView);view=null
        player?.release();player=null
    }
    fun pause(){job?.cancel();restoreJob?.cancel();resumeAfterPause=player?.isPlaying==true;player?.pause()}
    fun background(){
        if(player!=null){lastPosition=player?.currentPosition?:lastPosition;releaseNative()}
    }
    fun resume(){
        if(webUnloaded && player==null && candidate.isNotBlank()) {
            begin(candidate,providerPage,web.settings.userAgentString,lastPosition)
            if(!resumeAfterPause)player?.pause()
        } else if(resumeAfterPause){
            if(player!=null)player?.play()
            else web.evaluateJavascript("document.querySelectorAll('video').forEach(v=>v.play().catch(()=>{}))",null)
        }
        resumeAfterPause=false
        if(enabled&&player==null&&!webUnloaded)schedule()
    }
    fun close(){enabled=false;releaseNative();web.visibility=View.VISIBLE}
}
