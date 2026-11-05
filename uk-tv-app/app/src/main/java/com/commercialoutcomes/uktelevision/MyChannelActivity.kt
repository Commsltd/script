package com.commercialoutcomes.uktelevision

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.Button
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
import java.net.URL
import java.util.concurrent.Executors
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/** One decoder; no WebView or external computer required. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MyChannelActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val names = listOf("Business & Markets", "World Window", "Spain & Local", "Space & Earth", "British Classics", "Practical Living", "Technology & AI")
    private val keys = listOf("business", "world", "spain", "space", "classics", "practical", "technology")
    private val feeds = listOf("business", "world", "", "", "entertainment_and_arts", "science_and_environment", "technology")
    private lateinit var defaults: JSONObject
    private lateinit var video: PlayerView
    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var footer: TextView
    private val prefs by lazy { getSharedPreferences("my-channel",MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var cards: ScrollView
    private lateinit var controls: LinearLayout
    private lateinit var strip: TextView
    private var dialog: AlertDialog?=null
    private var pool=listOf(0,1,2,3)
    private var interval=15
    private var randomOrder=true
    private val rotation=PersonalRotation()
    private var fetchFailures=0
    private var paused=false
    private var selectDown=0L
    private var revealedControls=false
    private var deadline=0L
    private var remaining=900000L
    private val cooldown=mutableMapOf<Int,Long>()
    private val hideControls=Runnable { if(dialog==null){controls.visibility=View.GONE;footer.visibility=View.GONE} }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private var player: ExoPlayer? = null
    private var selected = 0
    private var automatic = true
    private var active = false
    private var generation = 0
    private var dataJob: java.util.concurrent.Future<*>? = null
    private var routes = emptyList<Pair<String,String>>()
    private var route = 0
    private var failureRounds=0
    private val rotate = Runnable { if(active && automatic && !paused && dialog==null) advance() }
    private val retry = Runnable { if(active){route=0;playRoute()} }
    private val timeout = Runnable { if (active) nextRoute() }
    private val refresh = Runnable { if (active) refreshData() }
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
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(40),dp(24),dp(40),dp(24)); setBackgroundColor(Color.rgb(18,32,34)) }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        fun label(size:Float) = TextView(this).apply { setTextColor(Color.WHITE); textSize=size; setPadding(8,8,8,8) }
        title=label(24f).apply { typeface=Typeface.create("serif",Typeface.NORMAL);setTextColor(Color.rgb(222,233,214)) }
        footer=label(14f); info=label(18f)
        video=PlayerView(this).apply { useController=false }
        body=LinearLayout(this)
        cards=ScrollView(this).apply { setPadding(dp(18),dp(10),dp(8),dp(10));setBackgroundColor(Color.rgb(27,45,43));addView(info) }
        body.addView(video,LinearLayout.LayoutParams(0,-1,7f));body.addView(cards,LinearLayout.LayoutParams(0,-1,3f))
        strip=label(15f).apply { setTextColor(Color.rgb(188,210,194)) }
        controls=LinearLayout(this)
        fun action(text:String,block:()->Unit){controls.addView(Button(this).apply { this.text=text;isAllCaps=false;textSize=15f;minHeight=dp(48);setOnClickListener{block()} },LinearLayout.LayoutParams(0,dp(52),1f))}
        action("Domains"){domains()};action("Picture / source"){sources()};action("Skip"){advance()};action("Stay on this"){automatic=false;saveSettings();scheduleRotation();updateChrome()};action("Menu"){menu()}
        root.addView(title);root.addView(body,LinearLayout.LayoutParams(-1,0,1f));root.addView(strip);root.addView(controls);root.addView(footer);setContentView(root)

    }
    override fun onResume() { super.onResume();active=true;select(selected) }
    override fun onPause() {
        active=false;dialog?.dismiss();dialog=null;generation++;dataJob?.cancel(true);handler.removeCallbacksAndMessages(null);video.player=null;player?.release();player=null
        super.onPause()
    }
    override fun onDestroy() { worker.shutdownNow();super.onDestroy() }
    private fun select(index:Int) {
        selected=index;paused=false;failureRounds=0;generation++;dataJob?.cancel(true);handler.removeCallbacksAndMessages(null)
        getSharedPreferences("my-channel",MODE_PRIVATE).edit().putInt("preset",selected).putBoolean("auto",automatic).apply()
        title.text="MY CHANNEL  •  ${names[selected]}"
        updateChrome()
        info.text=prefs.getString("cached.${keys[selected]}",null)?.let { "Saved information · refreshing\n\n$it" } ?: "Refreshing information…"
        val sources=defaults.getJSONObject(keys[selected]).getJSONArray("video")
        routes=buildList { for(i in 0 until sources.length()) { val source=sources.getJSONObject(i);val urls=source.getJSONArray("urls");for(j in 0 until urls.length()) add(source.getString("name") to urls.getString(j)) } }
        val source=prefs.getString("source.${keys[selected]}","")
        route=routes.indexOfFirst{it.first==source}.coerceAtLeast(0)
        remaining=interval*60000L
        playRoute();refreshData();scheduleRotation();updateChrome();showControls()
    }
    private fun playRoute() {
        handler.removeCallbacks(retry);handler.removeCallbacks(timeout);video.player=null;player?.release();player=null
        if(route>=routes.size) { title.text="My Channel  /  ${names[selected]}";strip.text="Picture unavailable · MENU for sources · Retrying in one minute";cooldown[selected]=System.currentTimeMillis()+300000;if(++failureRounds<2)handler.postDelayed(retry,60000) else {automatic=false;saveSettings();handler.removeCallbacks(rotate);strip.text="Picture unavailable · MENU to choose a source or retry"};return }
        val entry=routes[route];title.text="My Channel  /  ${names[selected]}";updateChrome()
        val p=ExoPlayer.Builder(this).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(PublicVodHttp.client))).setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5000,15000,1000,2000).setTargetBufferBytes(8 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()).build()
        p.trackSelectionParameters=p.trackSelectionParameters.buildUpon().setMaxVideoSize(1280,720).build()
        player=p;video.player=p
        p.addListener(object:Player.Listener {
            override fun onPlayerError(error:PlaybackException) { if(player===p) nextRoute() }
            override fun onRenderedFirstFrame() { if(player===p) {handler.removeCallbacks(timeout);cooldown.remove(selected)} }
            override fun onPlaybackStateChanged(state:Int) { if(player===p && state==Player.STATE_BUFFERING) { handler.removeCallbacks(timeout);handler.postDelayed(timeout,20000) } }
        })
        p.setMediaItem(MediaItem.fromUri(entry.second));p.prepare();p.playWhenReady=true;handler.postDelayed(timeout,20000)
    }
    private fun nextRoute() { route++;playRoute() }
    private fun refreshData() {
        val token=generation;val domain=keys[selected]
        dataJob=worker.submit {
            val result=runCatching { PersonalFeedClient.snapshot(domain).take(8000) }.getOrNull()
            handler.post {
                if(active && token==generation){
                    if(result!=null){fetchFailures=0;info.text=result;prefs.edit().putString("cached.$domain",result).apply()}
                    else {fetchFailures=(fetchFailures+1).coerceAtMost(4);info.text=prefs.getString("cached.$domain",null)?.let{"CACHED · refresh unavailable\n\n$it"} ?: "Information unavailable · retrying. Picture playback is independent."}
                    handler.postDelayed(refresh,if(result!=null)300000L else (60000L shl fetchFailures).coerceAtMost(900000L))
                }
            }
        }
    }
    private fun saveSettings() {
        prefs.edit().putBoolean("auto",automatic).putString("pool",pool.joinToString(",")).putBoolean("shuffle",randomOrder).putInt("interval",interval).apply()
    }
    private fun updateChrome() {
        title.text="My Channel  /  ${names[selected]}"
        val pictureOnly=prefs.getBoolean("pictureOnly.${keys[selected]}",false)
        cards.visibility=if(pictureOnly)View.GONE else View.VISIBLE
        strip.visibility=if(pictureOnly)View.GONE else View.VISIBLE
        strip.text="WATCHING  ${routes.getOrNull(route)?.first ?: names[selected]}    •    ${if(automatic) "YOUR MIX  ${if(randomOrder) "Shuffle" else "In order"} · ${interval}m" else "STAYING ON THIS"}"
        footer.text="OK Controls    MENU Customise    HOLD OK Sources    BACK Guide"
    }
    private fun showControls(focusView:View?=null) {
        controls.visibility=View.VISIBLE;footer.visibility=View.VISIBLE
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
        dialog=d
        d.setOnDismissListener {dialog=null;scheduleRotation();showControls(opener)}
        d.show()
    }
    private fun domains()=sheet("Choose your domain",names.mapIndexed {i,n->if(i==selected)"✓ $n" else n}){select(it)}
    private fun sources() {
        val names=routes.map{it.first}.distinct()
        sheet("Picture / source · ${this.names[selected]}",names+"Try next route for this picture"){i->
            if(i==names.size)nextRoute() else {route=routes.indexOfFirst{it.first==names[i]};prefs.edit().putString("source.${keys[selected]}",names[i]).apply();playRoute()}
        }
    }
    private fun autoMenu():Unit=sheet("Auto change",listOf("Auto: ${if(automatic) "On" else "Off"}","Included domains (${pool.size})","Order: ${if(randomOrder) "Shuffle without repeats" else "In order"}","Live picture interval: $interval minutes","Skip once","Stay on this")){i->
        when(i){
            0->{automatic=!automatic;remaining=interval*60000L;saveSettings();scheduleRotation();updateChrome()}
            1->choosePool()
            2->{randomOrder=!randomOrder;rotation.reset();saveSettings();autoMenu()}
            3->sheet("Change live pictures on a timer",listOf("5 minutes","15 minutes","30 minutes")){interval=listOf(5,15,30)[it];remaining=interval*60000L;saveSettings();scheduleRotation();updateChrome()}
            4->advance()
            5->{automatic=false;saveSettings();scheduleRotation();updateChrome()}
        }
    }
    private fun choosePool() {
        sheet("Include domains · select to toggle",names.mapIndexed{i,n->"${if(i in pool) "✓ ${pool.indexOf(i)+1}." else "○"} $n"}+"Done"){i->
            if(i<7){pool=if(i in pool)pool-i else pool+i;if(pool.isEmpty()){pool=listOf(selected);automatic=false};rotation.reset();saveSettings();choosePool()}else{updateChrome();scheduleRotation()}
        }
    }
    private fun information():Unit=sheet("Information & layout",listOf("${if(cards.visibility==View.VISIBLE) "Hide" else "Show"} information · picture only","Sources & freshness","Refresh information","Local weather / cameras")){i->
        when(i){
            0->{prefs.edit().putBoolean("pictureOnly.${keys[selected]}",cards.visibility==View.VISIBLE).apply();updateChrome()}
            1->sheet("Sources & freshness",listOf("Headlines: BBC / El País / NASA · publication source","Currencies: ECB · daily reference, not live prices","Space weather: NOAA · observation time in UTC","Live quotes / FTSE 100: not connected","Crucix / World Monitor: optional, not connected")){ }
            2->{handler.removeCallbacks(refresh);dataJob?.cancel(true);refreshData()}
            3->sheet("Local connections",listOf("No home location or private camera is assumed","AEMET needs an explicit area and source connection","Windy discovery needs an API connection","Space Live / Sen is available under World and Space")){ }
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
        if(keyCode==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && event.repeatCount==0){paused=!paused;player?.playWhenReady=!paused;if(!paused)player?.seekToDefaultPosition();scheduleRotation();strip.text=if(paused)"Paused · resuming live returns to the live edge" else "Live picture resumed";return true}
        if(keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN)) {
            if(controls.visibility!=View.VISIBLE){showControls();return true}
            handler.removeCallbacks(hideControls);handler.postDelayed(hideControls,8000)
        }
        return super.onKeyDown(keyCode,event)
    }
    @Deprecated("TV Back navigation")
    override fun onBackPressed(){if(dialog!=null)dialog?.dismiss() else finish()}
}
