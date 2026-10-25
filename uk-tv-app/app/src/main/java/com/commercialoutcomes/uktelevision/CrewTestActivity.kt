package com.commercialoutcomes.uktelevision

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import org.json.JSONObject

/** Explicit experiment. Kodi supplies sources on this Firestick; Media3 plays here. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CrewTestActivity:ComponentActivity() {
    private lateinit var root:FrameLayout
    private lateinit var status:TextView
    private var job:Job?=null
    private var keepalive:Job?=null
    private var player:ExoPlayer?=null
    private var playerView:PlayerView?=null
    private var result=JSONObject()
    private val token get()=getSharedPreferences("crew-test",MODE_PRIVATE).getString("token","").orEmpty()
    private val type get()=intent.getStringExtra("type")?:"movie"
    private val tmdb get()=intent.getStringExtra("tmdb").orEmpty()
    private val title get()=intent.getStringExtra("title").orEmpty()
    private var season:Int?=null
    private var episode:Int?=null
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        root=FrameLayout(this);setContentView(root)
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(50,30,50,25);setBackgroundColor(0xff101820.toInt())}
        status=TextView(this).apply{setTextColor(-1);textSize=20f;text="The Crew\n$title\n\nChoose a source to watch inside UK TV. Starting The Crew may briefly show Kodi."}
        panel.addView(status,LinearLayout.LayoutParams(-1,0,1f))
        fun button(label:String,action:()->Unit){panel.addView(Button(this).apply{isAllCaps=false;text=label;setOnClickListener{action()}},LinearLayout.LayoutParams(-1,(64*resources.displayMetrics.density).toInt()))}
        button(if(type=="tv")"Choose episode and check sources" else "Check sources"){if(type=="tv")chooseEpisode() else checkSources()}
        button("Return to On Demand"){stopEngine();finish()}
        root.addView(panel,FrameLayout.LayoutParams(-1,-1))
    }
    private suspend fun call(path:String,body:JSONObject=JSONObject()):JSONObject=withContext(Dispatchers.IO) {
        CrewBridgeClient.call(token,path,body)
    }

    private fun task(block:suspend()->Unit){job?.cancel();job=lifecycleScope.launch{try{block()}catch(e:CancellationException){throw e}catch(e:Exception){stopEngine();val reason=if(e is java.io.IOException)"The local source engine has stopped or is unavailable. This Firestick may not have enough free memory to keep both apps running." else e.message.orEmpty();status.text="The Crew could not complete the source search.\n$reason\n\nYou can continue with the existing VOD providers."}}}
    private suspend fun engine(){
        if(runCatching{call("/status")}.isSuccess)return
        status.text="Initialising the source engine on this Firestick…"
        CrewEngine.start(this, token)
    }

    private fun chooseEpisode(source:VodSource=VodSource(VodProvider.DULO,"https://dulo.mov/title/tv/$tmdb/")):Unit=task {
        status.text="Loading seasons and episodes…"
        val rows=VodEpisodes.load(source);check(rows.isNotEmpty()){"No episode list returned"}
        AlertDialog.Builder(this@CrewTestActivity).setTitle("Choose ${if(rows.first().kind=="season")"season" else "episode"}")
            .setItems(rows.map{it.title}.toTypedArray()){_,i->val row=rows[i];if(row.kind=="season")chooseEpisode(row.sources.first()) else {
                val u=android.net.Uri.parse(row.sources.first().url);season=u.getQueryParameter("s")?.toInt();episode=u.getQueryParameter("e")?.toInt();checkSources()
            }}.setNegativeButton("Back",null).show()
    }
    private fun checkSources()=task {
        player?.release();player=null;playerView?.let(root::removeView);playerView=null
        engine();status.text="Checking The Crew sources for $title${season?.let{" · S$it E$episode"}.orEmpty()}…"
        val data=withContext(Dispatchers.IO){VodDiscovery.json("/$type/$tmdb?append_to_response=external_ids&language=en-GB")}
        val imdb=data.optString("imdb_id").takeIf{it.startsWith("tt")}?:data.optJSONObject("external_ids")?.optString("imdb_id").orEmpty()
        val year=data.optString(if(type=="tv")"first_air_date" else "release_date").take(4).toIntOrNull()?:throw IllegalStateException("Missing release year")
        val body=JSONObject().put("title",title).put("year",year).put("tmdb",tmdb).put("imdb",imdb)
        if(type=="tv"){check(season!=null&&episode!=null){"Choose an episode first"};body.put("show",title).put("season",season).put("episode",episode)}
        call("/search",body)
        result=poll("searching")
        val items=result.optJSONArray("items")
        if(items==null||items.length()==0){status.text="The Crew returned no free sources for $title.\n\nNo playable link was supplied. Your existing VOD providers remain available.";stopEngine();return@task}
        status.text="${items.length()} sources returned. Choose a source to watch."
        AlertDialog.Builder(this@CrewTestActivity).setTitle("The Crew · sources")
            .setItems(Array(items.length()){i->val r=items.getJSONObject(i);"${r.optString("quality")} · ${r.optString("provider")} · ${r.optString("host")}"}){_,i->resolve(i)}.setNegativeButton("Back",null).show()
    }
    private suspend fun poll(busy:String):JSONObject {
        repeat(70){delay(1000);val data=call("/status");if(data.optString("status")!=busy){check(data.optString("status")!="error"){data.optString("error")};return data}}
        stopEngine();throw IllegalStateException("Source lookup timed out; the engine has been stopped.")
    }
    private fun resolve(index:Int)=task {
        status.text="Resolving the selected source…";call("/resolve",JSONObject().put("index",index));val data=poll("resolving")
        val value=data.getString("url");val parts=value.split('|',limit=2);val uri=android.net.Uri.parse(parts[0])
        require(uri.scheme in listOf("http","https")&&uri.host!=null&&uri.userInfo==null){"Unsupported media address"}
        val headers=if(parts.size==2)MediaRequestHeaders.crew(parts[1]) else emptyMap()
        val http=DefaultHttpDataSource.Factory().setConnectTimeoutMs(6000).setReadTimeoutMs(8000).setDefaultRequestProperties(headers)
        val exo=ExoPlayer.Builder(this@CrewTestActivity).setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(DefaultLoadControl.Builder().setTargetBufferBytes(24*1024*1024).build()).build()
        player=exo;exo.setAudioAttributes(AudioAttributes.DEFAULT,true);exo.trackSelectionParameters=exo.trackSelectionParameters.buildUpon().setMaxVideoSize(1280,720).build()
        playerView=PlayerView(this@CrewTestActivity).also{it.player=exo;root.addView(it,FrameLayout.LayoutParams(-1,-1));it.requestFocus()}
        exo.addListener(object:Player.Listener{
            override fun onRenderedFirstFrame(){status.text="The Crew source is playing in UK TV";if(uri.host !in setOf("127.0.0.1","localhost","::1"))stopEngine() else {keepalive?.cancel();keepalive=lifecycleScope.launch {while(isActive){delay(30000);runCatching{call("/status")}}}}}
            override fun onPlayerError(error:PlaybackException){playerView?.let(root::removeView);playerView=null;player?.release();player=null;status.text="This source failed: ${error.errorCodeName}. Check sources to try again.";stopEngine()}
        })
        exo.setMediaItem(MediaItem.fromUri(parts[0]));exo.prepare();exo.play()
    }
    private fun stopEngine(){keepalive?.cancel();keepalive=null;CoroutineScope(Dispatchers.IO).launch{runCatching{call("/quit")}}}
    override fun onKeyDown(keyCode:Int,event:KeyEvent?):Boolean {
        if(keyCode==KeyEvent.KEYCODE_MENU){if(event?.repeatCount==0)AlertDialog.Builder(this).setItems(arrayOf("Check another source","Return to On Demand")){_,i->if(i==0)checkSources() else {stopEngine();finish()}}.show();return true}
        return super.onKeyDown(keyCode,event)
    }
    override fun onPause(){player?.pause();super.onPause()}
    override fun onResume(){super.onResume();player?.play()}
    override fun onDestroy(){stopEngine();playerView?.player=null;player?.release();super.onDestroy()}
}
