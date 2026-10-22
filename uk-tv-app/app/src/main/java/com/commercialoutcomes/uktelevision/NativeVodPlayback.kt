package com.commercialoutcomes.uktelevision

import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URL
import javax.net.ssl.HttpsURLConnection

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
                        val parsed=URL(url)
                        if(parsed.protocol!="https"||parsed.userInfo!=null||parsed.host in setOf("localhost","127.0.0.1","::1"))return@runCatching false
                        val connection=parsed.openConnection() as HttpsURLConnection
                        connection.connectTimeout=5000;connection.readTimeout=5000
                        connection.setRequestProperty("User-Agent",agent);connection.setRequestProperty("Referer",page)
                        try {
                            if(connection.responseCode!=200){diagnostic="Playlist HTTP ${connection.responseCode}";return@runCatching false}
                            val bytes=connection.inputStream.use{input->
                                val output=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192)
                                while(output.size()<=1024*1024){val n=input.read(chunk);if(n<0)break;output.write(chunk,0,n)}
                                output.toByteArray()
                            }
                            bytes.size<=1024*1024 && VodHls.matches(bytes.toString(Charsets.UTF_8),snapshot.optDouble("duration"))
                        }finally{connection.disconnect()}
                    }.onFailure{diagnostic="Playlist request: ${it.javaClass.simpleName}"}.getOrDefault(false)}
                    if(matches){
                        candidate=url;begin(url,page,agent,(snapshot.optDouble("time")*1000).toLong());return@launch
                    }
                    rejected.add(url)
                }
            }
        }
    }
    private fun begin(url:String,page:String,agent:String,position:Long){
        if(!enabled)return
        val token=++generation
        val http=DefaultHttpDataSource.Factory().setUserAgent(agent).setDefaultRequestProperties(mapOf("Referer" to page))
            .setConnectTimeoutMs(6000).setReadTimeoutMs(8000)
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
            override fun onRenderedFirstFrame(){if(generation==token){diagnostic="Native picture playing";watchdog?.cancel()}}
            override fun onPlayerError(error:PlaybackException){if(generation==token){diagnostic="Native playback: ${error.errorCodeName}";rejected.add(candidate);returnToWeb(true)}}
        })
        exo.setMediaItem(MediaItem.fromUri(url));exo.seekTo(position);exo.prepare();exo.play()
        watchdog=activity.lifecycleScope.launch{delay(20000);if(generation==token&&(exo.videoDecoderCounters?.renderedOutputBufferCount?:0)==0){rejected.add(candidate);returnToWeb(true)}}
    }
    fun returnToWeb(resume:Boolean=true){
        val exo=player
        val position=exo?.currentPosition?.takeIf{it>0}?:lastPosition
        generation++;watchdog?.cancel();job?.cancel()
        view?.player=null;view?.let(root::removeView);view=null;exo?.release();player=null
        web.visibility=View.VISIBLE
        if(exo!=null)web.evaluateJavascript("""(()=>{const v=document.querySelector('video');if(v){v.currentTime=${position/1000.0};${if(resume)"v.play().catch(()=>{})" else "v.pause()"}}})()""",null)
        web.requestFocus()
    }
    fun pause(){job?.cancel();resumeAfterPause=player?.isPlaying==true;player?.pause()}
    fun resume(){if(resumeAfterPause)player?.play();resumeAfterPause=false;if(enabled&&player==null)schedule()}
    fun close(){enabled=false;returnToWeb(false)}
}
