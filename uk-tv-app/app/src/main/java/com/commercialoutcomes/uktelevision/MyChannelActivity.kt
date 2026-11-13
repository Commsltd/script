package com.commercialoutcomes.uktelevision

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.ScrollView
import android.graphics.Typeface
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.util.concurrent.Executors

/** One decoder; no WebView or external computer required. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MyChannelActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val sourceWorker = Executors.newSingleThreadExecutor()
    @Volatile private var sourceCall:okhttp3.Call?=null
    private var sourceJob:java.util.concurrent.Future<*>?=null
    private var sourceGeneration=0
    private lateinit var pictureStatus:TextView
    private var videoMetadata=emptyMap<String,JSONObject>()
    private var pictureProblem=""
    private fun cameraWeather()=videoMetadata[routes.getOrNull(route)?.first]?.optString("weather")?.ifBlank{null} ?: "yellowstone"
    private fun panelGroups()=panels.groups(keys[selected],cameraWeather())
    private val names = listOf("Business & Markets", "World Window", "Spain & Local", "Space & Earth", "British Classics", "Practical Living", "Technology & AI")
    private val keys = listOf("business", "world", "spain", "space", "classics", "practical", "technology")
    private lateinit var defaults: JSONObject
    private lateinit var video: PlayerView
    private lateinit var title: TextView
    private val prefs by lazy { getSharedPreferences("my-channel",MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var cards: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var strip: TextView
    private lateinit var clock: TextView
    private lateinit var ticker: PersonalTicker
    private lateinit var panels: PersonalPanelRepository
    private lateinit var broadcast:PersonalBroadcastScreen
    private val panelData=mutableMapOf<String,PersonalPanel>()
    private var page=0
    private val clockTick=object:Runnable {override fun run(){if(active){updateClock();handler.postDelayed(this,10000)}}}
    private val pageTick=object:Runnable {override fun run(){if(active){if(!paused && dialog==null && !broadcast.hasInformationFocus() && !ticker.hasFocus()){page++;renderPanels()};handler.postDelayed(this,25000)}}}
    private val panelRefresh=object:Runnable {override fun run(){if(active){refreshData();handler.postDelayed(this,60000)}}}
    private val localTick=object:Runnable {override fun run(){if(active){updateLocalPanels();handler.postDelayed(this,60000)}}}

    private var dialog: AlertDialog?=null
    private var pool=listOf(0,1,2,3)
    private var interval=15
    private var randomOrder=true
    private val rotation=PersonalRotation()
    private var paused=false
    private var selectDown=0L
    private var revealedControls=false
    private var deadline=0L
    private var remaining=900000L
    private val cooldown=mutableMapOf<Int,Long>()
    private val hideControls=Runnable { if(dialog==null && !broadcast.hasInformationFocus() && !ticker.hasFocus()){controls.visibility=View.GONE;video.requestFocus()} }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private var player: ExoPlayer? = null
    private var selected = 0
    private var automatic = true
    private var active = false
    private var generation = 0
    private var routes = emptyList<Pair<String,String>>()
    private var route = 0
    private var failureRounds=0
    private val rotate = Runnable { if(active && automatic && !paused && dialog==null) advance() }
    private val retry = Runnable { if(active){route=routes.indexOfFirst{it.first==routes.getOrNull(route)?.first}.coerceAtLeast(0);playRoute()} }
    private val timeout = Runnable { if (active) nextRoute() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("my-channel", MODE_PRIVATE)
        selected = prefs.getInt("preset",0).coerceIn(0,6)
        automatic = prefs.getBoolean("auto",true)
        defaults = JSONObject(assets.open("my-channel/default-feeds.json").bufferedReader().use { it.readText() })
        pool=prefs.getString("pool","0,1,2,3").orEmpty().split(',').mapNotNull{it.toIntOrNull()}.filter{it in 0..6}.ifEmpty{listOf(0,1,2,3)}
        interval=prefs.getInt("interval",15).coerceIn(1,120)
        randomOrder=prefs.getBoolean("shuffle",true)
        panels=PersonalPanelRepository(this)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        video=PlayerView(this).apply{useController=false;isFocusable=true;setBackgroundColor(Color.BLACK)}
        pictureStatus=PersonalStyle.label(this,18f).apply{gravity=android.view.Gravity.CENTER;setPadding(dp(24),dp(20),dp(24),dp(70));setBackgroundColor(Color.rgb(8,18,28))}
        controls=LinearLayout(this).apply{setPadding(dp(6),dp(6),dp(6),dp(6));setBackgroundColor(Color.rgb(8,18,28))}
        fun action(label:String,block:()->Unit){controls.addView(PersonalStyle.action(this,label,block),LinearLayout.LayoutParams(0,dp(48),1f).apply{setMargins(dp(3),0,dp(3),0)})}
        action("Domains"){domains()};action("Sources"){sources()};action("Next"){advance()};action("Stay"){automatic=false;saveSettings();scheduleRotation();updateChrome()};action("My Lists"){lists()};action("Menu"){menu()}
        broadcast=PersonalBroadcastScreen(this,video,pictureStatus,controls){showTextDetails(it)}
        root=broadcast;body=broadcast.body;cards=broadcast.middle;title=broadcast.title;clock=broadcast.clock;strip=broadcast.strip;ticker=broadcast.ticker
        ticker.setOnClickListener{showTickerDetails()}
        setContentView(broadcast)
    }

    override fun onResume() { super.onResume();active=true;select(selected) }
    override fun onPause() {
        active=false;panels.cancel();ticker.stop();dialog?.dismiss();dialog=null;generation++;cancelSource();handler.removeCallbacksAndMessages(null);video.player=null;player?.release();player=null
        super.onPause()
    }
    override fun onDestroy() { panels.close();worker.shutdownNow();sourceWorker.shutdownNow();super.onDestroy() }
    private fun select(index:Int) {
        selected=index;page=0;panels.cancel();panelData.clear();paused=false;failureRounds=0;generation++;cancelSource();handler.removeCallbacksAndMessages(null)
        getSharedPreferences("my-channel",MODE_PRIVATE).edit().putInt("preset",selected).putBoolean("auto",automatic).apply()
        title.text="MY CHANNEL  •  ${names[selected]}"
        updateChrome()
        val sources=defaults.getJSONObject(keys[selected]).getJSONArray("video")
        videoMetadata=(0 until sources.length()).associate{sources.getJSONObject(it).let{s->s.getString("name") to s}}
        routes=videoMetadata.flatMap{(name,source)->val urls=source.getJSONArray("urls");val healthy=prefs.getString("healthy.${keys[selected]}.$name","");(0 until urls.length()).map{urls.getString(it)}.sortedBy{if(it==healthy)0 else 1}.map{name to it}}
        val source=prefs.getString("source.${keys[selected]}","")
        route=routes.indexOfFirst{it.first==source}.coerceAtLeast(0)
        remaining=interval*60000L
        playRoute();refreshData();updateLocalPanels();scheduleRotation();updateChrome();showControls();handler.post(clockTick);handler.postDelayed(pageTick,25000);handler.postDelayed(panelRefresh,60000);handler.postDelayed(localTick,60000)
    }
    private fun cancelSource(){sourceGeneration++;sourceJob?.cancel(true);sourceCall?.cancel();sourceCall=null}
    private fun playRoute() {
        cancelSource();pictureProblem=""
        handler.removeCallbacks(retry);handler.removeCallbacks(timeout);video.player=null;player?.release();player=null
        val entry=routes.getOrNull(route) ?: return pictureUnavailable()
        pictureStatus.text="Connecting to ${entry.first}…";pictureStatus.visibility=View.VISIBLE;updateChrome()
        updateLocalPanels()
        val meta=videoMetadata[entry.first];val resolver=meta?.optString("resolver").orEmpty()
        if(resolver.isNotBlank()){
            refreshData();updateLocalPanels()
            val token=sourceGeneration
            sourceJob=sourceWorker.submit {
                val url=runCatching {
                    val call=PublicVodHttp.client.newBuilder().callTimeout(15,java.util.concurrent.TimeUnit.SECONDS).build().newCall(okhttp3.Request.Builder().url(entry.second).build());sourceCall=call
                    if(token!=sourceGeneration){call.cancel();return@submit}
                    val text=call.execute().use{r->check(r.isSuccessful);r.body!!.byteStream().use{it.readBounded(524288)}.toString(Charsets.UTF_8)}
                    PersonalMediaResolver.parse(resolver,text)
                }.getOrNull()
                handler.post{if(active && sourceGeneration==token){if(url==null)nextRoute()else startVideo(url)}}
            }
        }else startVideo(entry.second)
    }
    private fun startVideo(url:String){
        if(!active)return
        val p=ExoPlayer.Builder(this).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(PublicVodHttp.client))).setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5000,15000,1000,2000).setTargetBufferBytes(8 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()).build()
        // Prefer 720p where offered. Some live cameras publish a single 1080p rendition.
        p.trackSelectionParameters=p.trackSelectionParameters.buildUpon().setMaxVideoSize(1280,720).setMaxVideoBitrate(3000000).build()
        player=p;video.player=p
        var renderedFrame=false
        p.addListener(object:Player.Listener {
            override fun onPlayerError(error:PlaybackException) { if(player===p) nextRoute() }
            override fun onRenderedFirstFrame() { if(player===p) {renderedFrame=true;pictureStatus.visibility=View.GONE;pictureProblem="";handler.removeCallbacks(timeout);cooldown.remove(selected);routes.getOrNull(route)?.let{prefs.edit().putString("healthy.${keys[selected]}.${it.first}",it.second).apply()}} }
            override fun onPlaybackStateChanged(state:Int) {
                if(player!==p)return
                if(state==Player.STATE_BUFFERING){handler.removeCallbacks(timeout);handler.postDelayed(timeout,20000)}
                else if(state==Player.STATE_READY && renderedFrame)handler.removeCallbacks(timeout)
                else if(state==Player.STATE_ENDED)nextRoute()
            }
        })
        p.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8).build());p.prepare();p.playWhenReady=!paused;handler.postDelayed(timeout,20000)
    }
    private fun pictureUnavailable(){
        cancelSource();handler.removeCallbacks(timeout);video.player=null;player?.release();player=null
        pictureProblem="Picture unavailable · Sources to retry or choose another"
        pictureStatus.text="${routes.getOrNull(route)?.first ?: names[selected]} is unavailable.\n\nYour information panels are still running.\nOK → Sources to retry or choose another picture."
        pictureStatus.visibility=View.VISIBLE;cooldown[selected]=System.currentTimeMillis()+300000
        if(++failureRounds<2)handler.postDelayed(retry,60000)
        updateChrome()
    }
    private fun nextRoute() {
        // Recovery stays on the same programme/camera. Another title is an explicit source choice.
        if(routes.getOrNull(route+1)?.first==routes.getOrNull(route)?.first){route++;playRoute()}else pictureUnavailable()
    }
    private fun refreshData(force:Boolean=false) {
        val token=generation
        panels.refresh(keys[selected],force,cameraWeather()){id,result->handler.post{if(active && generation==token){panelData[id]=result;renderPanels()}}}
        renderPanels()
    }
    private fun renderPanels() {
        val groups=panelGroups()
        broadcast.bind(keys[selected],panelData,page)
        val news=groups.take(3).flatten().distinct().mapNotNull{panelData[it]}.filter{panels.specs[it.id]?.kind=="rss"}
        val lead=PersonalEditorial.brief(panelData,news.map{it.id},page).items.firstOrNull()?.headline
        val items=PersonalEditorial.distinct(news.flatMap{p->p.items.map{it.copy(observed=p.source)}})
            .filter{it.headline!=lead}.map{it.observed+" • "+it.headline}
            .ifEmpty{listOf("Reference information is dated at source · MENU opens your mix and programme queue")}
        ticker.setHeadlines(if(ticker.reducedMotion)items[page.mod(items.size)] else items.joinToString("      ◆      "))
    }
    private fun updateLocalPanels() {
        val token=generation;val domain=keys[selected]
        if(domain=="world"){
            val meta=videoMetadata[routes.getOrNull(route)?.first]
            if(meta!=null)panelData["picture"]=PersonalPanel("picture","Your window",meta.optString("publisher"),listOf(PersonalItem(meta.getString("name"),meta.optString("description"),"Live camera · "+meta.optString("timezone"),meta.optString("page").ifBlank{routes[route].second})))
        }
        val routeToken=sourceGeneration
        val channelName=routes.getOrNull(route)?.first.orEmpty().substringBefore(" — ")
        worker.submit {
            val programmes=kotlinx.coroutines.runBlocking {(application as TvApplication).database.dao().personalNowNext(channelName,System.currentTimeMillis())}
            val now=System.currentTimeMillis()
            val info=PersonalPanel("onair","Now & next",channelName,programmes.map{p->PersonalItem((if(p.start<=now)"NOW · " else "NEXT · ")+p.title,p.description.take(500),java.text.SimpleDateFormat("HH:mm",java.util.Locale.UK).format(java.util.Date(p.start)))}.ifEmpty{listOf(PersonalItem(channelName,"Programme guide unavailable for this feed. Use Sources to change the picture."))})
            handler.post{if(active && token==generation && routeToken==sourceGeneration){panelData["onair"]=info;renderPanels()}}
        }
        if(panelGroups().flatten().none{it=="library" || it=="rundown"}){renderPanels();return}
        worker.submit {
            val saved=PersonalQueue.saved(this)
            val relevant=saved.lists.filter { l->if(domain=="classics")Regex("classic|comedy|british",RegexOption.IGNORE_CASE).containsMatchIn(l.name) else Regex("practical|homestead|outdoor|ranch|garden",RegexOption.IGNORE_CASE).containsMatchIn(l.name)}
            val titles=relevant.flatMap{saved.listTitles(it.id,emptyList())}.distinctBy{it.key}.take(8)
            val queue=saved.listTitles(PersonalQueue.ID,emptyList()).drop(prefs.getInt("queueCursor",0)).take(3)
            val library=PersonalPanel("library","Your programme lists","My Lists",titles.map{PersonalItem(it.title,it.description.take(500),it.year?.toString().orEmpty())}.ifEmpty{listOf(PersonalItem("Your saved programmes","Open Up next & My Lists to choose a programme. Catalogue entries do not guarantee playback."))})
            val next=PersonalPanel("rundown","Up next","Your mix",queue.map{PersonalItem(it.title,it.description.take(400),it.year?.toString().orEmpty())}.ifEmpty{listOf(PersonalItem(if(automatic)"Automatic mix · $interval minutes" else "Staying on this domain",pool.joinToString("  ·  "){names[it]}))})
            handler.post{if(active && token==generation){panelData["library"]=library;panelData["rundown"]=next;renderPanels()}}
        }
    }
    private fun updateClock(){
        fun time(zone:String)=java.text.SimpleDateFormat("HH:mm",java.util.Locale.UK).apply{timeZone=java.util.TimeZone.getTimeZone(zone)}.format(java.util.Date())
        val second=when(selected){0->"London "+time("Europe/London");1->videoMetadata[routes.getOrNull(route)?.first]?.let{it.optString("place")+" "+time(it.optString("timezone","UTC"))} ?: "UTC "+time("UTC");3->"UTC "+time("UTC");else->java.text.SimpleDateFormat("EEE d MMM",java.util.Locale.UK).format(java.util.Date())}
        clock.text=time(java.util.TimeZone.getDefault().id)+" LOCAL\n"+second
        if(pictureProblem.isNotBlank()){strip.text=pictureProblem;return}
        if(automatic && deadline>0 && !paused && dialog==null){val seconds=((deadline-System.currentTimeMillis())/1000).coerceAtLeast(0);strip.text="${routes.getOrNull(route)?.first ?: names[selected]}   ·   ${if(seconds<=10) "Changing domain in ${seconds}s · OK → Stay" else "Your mix · next in ${seconds/60}m ${seconds%60}s"}"}
    }
    private fun showTextDetails(detail:String){
        if(dialog!=null)return
        val opener=currentFocus;remaining=if(deadline>0)(deadline-System.currentTimeMillis()).coerceAtLeast(1000) else interval*60000L
        handler.removeCallbacks(rotate);handler.removeCallbacks(hideControls);ticker.stop()
        val text=PersonalStyle.label(this,18f).apply{this.text=detail;setPadding(dp(24),dp(16),dp(24),dp(16))}
        val scroll=ScrollView(this).apply{addView(text);isFocusable=true}
        val d=AlertDialog.Builder(this).setTitle("Information · source & freshness").setView(scroll).setPositiveButton("Refresh"){_,_->refreshData(true)}.setNegativeButton("Back",null).create()
        dialog=d;d.setOnDismissListener{dialog=null;scheduleRotation();showControls(opener);if(active)ticker.start()};d.show();d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(PersonalStyle.background));scroll.requestFocus()
    }
    private fun showTickerDetails(){sheet("Ticker controls",listOf(if(ticker.held)"Resume scrolling" else "Pause scrolling","Reduced motion: ${if(ticker.reducedMotion)"On" else "Off"}","Slow / normal speed")){i->when(i){0->ticker.held=!ticker.held;1->{ticker.reducedMotion=!ticker.reducedMotion;prefs.edit().putBoolean("tickerReduced",ticker.reducedMotion).apply()};2->{ticker.speed=if(ticker.speed==32f)20f else 32f;prefs.edit().putFloat("tickerSpeed",ticker.speed).apply()}};renderPanels();ticker.invalidate()}}
    private fun saveSettings() {
        prefs.edit().putBoolean("auto",automatic).putString("pool",pool.joinToString(",")).putBoolean("shuffle",randomOrder).putInt("interval",interval).apply()
    }
    private fun updateChrome() {
        title.text=names[selected].uppercase(java.util.Locale.ROOT)
        val pictureOnly=prefs.getBoolean("pictureOnly.${keys[selected]}",false)
        broadcast.pictureOnly(pictureOnly)
        broadcast.tickerVisible(prefs.getBoolean("ticker",true))
        ticker.visibility=if(pictureOnly || !prefs.getBoolean("ticker",true))View.GONE else View.VISIBLE
        ticker.reducedMotion=prefs.getBoolean("tickerReduced",false);ticker.speed=prefs.getFloat("tickerSpeed",32f)
        if(ticker.visibility==View.VISIBLE && active && !paused)ticker.start() else ticker.stop()
        strip.text="${routes.getOrNull(route)?.first ?: names[selected]}   ·   ${if(automatic) "Your mix · ${if(randomOrder) "Shuffle" else "In order"} · ${interval}m" else "Staying on this"}"
        updateClock()
    }
    private fun showControls(focusView:View?=null) {
        controls.visibility=View.VISIBLE
        (focusView ?: controls.getChildAt(0)).requestFocus();handler.removeCallbacks(hideControls);handler.postDelayed(hideControls,8000)
    }
    private fun scheduleRotation() {
        handler.removeCallbacks(rotate)
        if(active && automatic && !paused && dialog==null){deadline=System.currentTimeMillis()+remaining;handler.postDelayed(rotate,remaining)}
    }
    private fun advance() {
        val available=pool.filter { (cooldown[it] ?: 0L)<=System.currentTimeMillis() }
        if(available.isEmpty()){automatic=false;saveSettings();strip.text="No available rotation entries · choose a source from MENU";return}
        val next=rotation.next(selected,available,randomOrder)
        if(next==selected){remaining=interval*60000L;scheduleRotation()}else select(next)
    }
    private fun sheet(name:String,items:List<String>,choose:(Int)->Unit) {
        if(dialog!=null)return
        val opener=currentFocus
        remaining=if(deadline>0)(deadline-System.currentTimeMillis()).coerceAtLeast(1000) else interval*60000L
        handler.removeCallbacks(rotate);handler.removeCallbacks(hideControls)
        val d=AlertDialog.Builder(this).setTitle(name).setItems(items.toTypedArray()){_,index-> handler.post{choose(index)} }.setNegativeButton("Back",null).create()
        dialog=d; ticker.stop()
        d.setOnDismissListener {dialog=null;scheduleRotation();showControls(opener);if(active)ticker.start()}
        d.show()
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(PersonalStyle.background))
        d.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(PersonalStyle.mint)
    }
    private fun domains()=sheet("Choose your domain",names.mapIndexed {i,n->if(i==selected)"✓ $n" else n}){select(it)}
    private fun sources() {
        val names=routes.map{it.first}.distinct()
        sheet("Picture / source · ${this.names[selected]}",names+"Retry this picture (all its alternative routes)"){i->
            if(i==names.size){failureRounds=0;route=routes.indexOfFirst{it.first==routes.getOrNull(route)?.first}.coerceAtLeast(0);playRoute()} else {failureRounds=0;route=routes.indexOfFirst{it.first==names[i]};prefs.edit().putString("source.${keys[selected]}",names[i]).apply();playRoute()}
        }
    }
    private fun autoMenu():Unit=sheet("Auto change",listOf("Auto: ${if(automatic) "On" else "Off"}","Included domains (${pool.size})","Order: ${if(randomOrder) "Shuffle without repeats" else "In order"}","Live picture interval: $interval minutes","Skip once","Stay on this")){i->
        when(i){
            0->{automatic=!automatic;remaining=interval*60000L;saveSettings();scheduleRotation();updateChrome()}
            1->choosePool()
            2->{randomOrder=!randomOrder;rotation.reset();saveSettings();autoMenu()}
            3->sheet("Change live pictures on a timer",listOf("5 minutes","15 minutes","30 minutes","Custom duration")){if(it==3)customInterval() else {interval=listOf(5,15,30)[it];remaining=interval*60000L;saveSettings();scheduleRotation();updateChrome()}}
            4->advance()
            5->{automatic=false;saveSettings();scheduleRotation();updateChrome()}
        }
    }
    private fun customInterval() {
        handler.removeCallbacks(rotate)
        val input=android.widget.EditText(this).apply {inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText(interval.toString());selectAll()}
        val d=AlertDialog.Builder(this).setTitle("Live rotation · 1–120 minutes").setView(input).setPositiveButton("Save",null).setNegativeButton("Back",null).create()
        dialog=d;d.setOnDismissListener{dialog=null;scheduleRotation();showControls()};d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value=input.text.toString().toIntOrNull()
            if(value==null||value !in 1..120)input.error="Use 1–120 minutes" else {interval=value;remaining=value*60000L;saveSettings();d.dismiss();updateChrome()}
        }
    }
    private fun choosePool() {
        sheet("Include domains · select to toggle",names.mapIndexed{i,n->"${if(i in pool) "✓ ${pool.indexOf(i)+1}." else "○"} $n"}+"Done"){i->
            if(i<7){pool=if(i in pool)pool-i else pool+i;if(pool.isEmpty()){pool=listOf(selected);automatic=false};rotation.reset();saveSettings();choosePool()}else{updateChrome();scheduleRotation()}
        }
    }
    private fun information():Unit=sheet("Information & layout",listOf("${if(cards.visibility==View.VISIBLE) "Picture only" else "Broadcast layout · panels, ticker & clock"}","Sources & freshness","Refresh information","Ticker: ${if(prefs.getBoolean("ticker",true))"On" else "Off"}","Ticker speed / motion")){i->
        when(i){
            0->{prefs.edit().putBoolean("pictureOnly.${keys[selected]}",cards.visibility==View.VISIBLE).apply();updateChrome()}
            1->sheet("Sources & freshness",panelGroups().flatten().distinct().mapNotNull{panels.specs[it]}.map{spec->val data=panelData[spec.id];spec.source+" · "+spec.title+"\n"+(data?.let{"Fetched "+PersonalPanelParser.utc(it.fetchedAt)+(if(it.stale)" · cached / retrying" else "")} ?: "Connecting")+"\n"+spec.url}){}
            2->refreshData(true)
            3->{prefs.edit().putBoolean("ticker",!prefs.getBoolean("ticker",true)).apply();updateChrome()}
            4->showTickerDetails()
        }
    }
    private fun lists() {
        val saved=VodSavedCodec.decode(getSharedPreferences("vod-library",MODE_PRIVATE).getString("saved","").orEmpty())
        sheet("Up next & My Lists",listOf("Play / resume queue","Manage queue","Queue autoplay: ${if(prefs.getBoolean("queueAuto",false)) "On" else "Off"}")+saved.lists.map{it.name}+"Open On Demand"){choice->
            if(choice==0){playQueue();return@sheet}
            if(choice==1){manageQueue();return@sheet}
            if(choice==2){prefs.edit().putBoolean("queueAuto",!prefs.getBoolean("queueAuto",false)).apply();lists();return@sheet}
            val i=choice-3
            val launch=Intent(this,OnDemandActivity::class.java).putExtra("personal-channel",true)
            if(i<saved.lists.size)launch.putExtra("initial-shelf",saved.lists[i].id)
            startActivity(launch)
        }
    }
    private fun manageQueue() {
        val saved=PersonalQueue.saved(this);val list=saved.lists.firstOrNull{it.id==PersonalQueue.ID}
        val titles=saved.listTitles(PersonalQueue.ID,emptyList())
        if(list==null||titles.isEmpty()){lists();return}
        sheet("Queue · exact programmes",titles.map{it.title}){index->
            sheet(titles[index].title,listOf("Play this programme","Move earlier","Move later","Remove from queue")){action->
                if(action==0){prefs.edit().putInt("queueCursor",index).apply();playQueue()}
                else {
                    val keys=list.keys.toMutableList()
                    when(action){1->if(index>0)java.util.Collections.swap(keys,index,index-1);2->if(index<keys.lastIndex)java.util.Collections.swap(keys,index,index+1);3->keys.removeAt(index)}
                    val updated=saved.copy(lists=saved.lists.map{if(it.id==PersonalQueue.ID)it.copy(keys=keys.toSet())else it})
                    getSharedPreferences("vod-library",MODE_PRIVATE).edit().putString("saved",VodSavedCodec.encode(updated)).apply()
                    prefs.edit().putInt("queueCursor",0).apply();manageQueue()
                }
            }
        }
    }
    private fun playQueue() {
        val saved=PersonalQueue.saved(this);val titles=saved.listTitles(PersonalQueue.ID,emptyList())
        if(titles.isEmpty()){sheet("Your queue is empty",listOf("Open My Lists, choose an exact episode or film, then Add to My Channel queue")){lists()};return}
        val cursor=prefs.getInt("queueCursor",0)
        if(cursor>=titles.size){sheet("Queue complete",listOf("Play again","Choose from My Lists")){if(it==0){prefs.edit().putInt("queueCursor",0).apply();playQueue()}else lists()};return}
        val position=cursor.coerceAtLeast(0)
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this,OnDemandActivity::class.java).putExtra("personal-queue-key",titles[position].key),41)
    }
    @Deprecated("Internal activity result")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==41 && resultCode==RESULT_OK){
            val count=PersonalQueue.saved(this).listTitles(PersonalQueue.ID,emptyList()).size
            val next=prefs.getInt("queueCursor",0)+1;prefs.edit().putInt("queueCursor",next.coerceAtMost(count)).apply()
            if(next<count && prefs.getBoolean("queueAuto",false))handler.post{playQueue()}
        }
    }
    private fun menu():Unit=sheet("My Channel · ${names[selected]}",listOf("Customise your mix / domain","Picture / source","Auto change","Information & layout","Up next & My Lists","Sources & freshness","Return to TV guide")){i->
        when(i){0->domains();1->sources();2->autoMenu();3->information();4->lists();5->information();6->finish()}
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if(dialog==null && event.keyCode==KeyEvent.KEYCODE_DPAD_CENTER){
            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0){
                selectDown=android.os.SystemClock.uptimeMillis()
                revealedControls=controls.visibility!=View.VISIBLE
                if(revealedControls)showControls()
            }
            if(event.action==KeyEvent.ACTION_UP){
                if(android.os.SystemClock.uptimeMillis()-selectDown>=600)sources()
                else if(!revealedControls)currentFocus?.performClick()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {
        if(dialog!=null)return super.onKeyDown(keyCode,event)
        if(keyCode in listOf(KeyEvent.KEYCODE_CHANNEL_UP,KeyEvent.KEYCODE_CHANNEL_DOWN)){setResult(RESULT_OK,Intent().putExtra("channelDelta",if(keyCode==KeyEvent.KEYCODE_CHANNEL_UP)1 else -1));finish();return true}
        if(keyCode==KeyEvent.KEYCODE_MENU){if(event.repeatCount==0)menu();return true}
        if(keyCode==KeyEvent.KEYCODE_DPAD_CENTER && event.repeatCount==1){sources();return true}
        if(keyCode==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && event.repeatCount==0){if(!paused)remaining=(deadline-System.currentTimeMillis()).coerceAtLeast(1000);paused=!paused;if(paused)ticker.stop()else ticker.start();player?.playWhenReady=!paused;if(!paused)player?.seekToDefaultPosition();scheduleRotation();strip.text=if(paused)"Paused · resuming live returns to the live edge" else "Live picture resumed";return true}
        if(keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN)) {
            if(controls.visibility!=View.VISIBLE){showControls();return true}
            handler.removeCallbacks(hideControls);handler.postDelayed(hideControls,8000)
        }
        return super.onKeyDown(keyCode,event)
    }
    @Deprecated("TV Back navigation")
    override fun onBackPressed(){if(dialog!=null)dialog?.dismiss() else finish()}
}
