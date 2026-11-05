package com.commercialoutcomes.uktelevision

import android.app.Activity
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
    private val names = listOf("Business & Markets", "World", "Spain", "Space", "British Classics", "Practical Living", "Technology & AI")
    private val keys = listOf("business", "world", "spain", "space", "classics", "practical", "technology")
    private val feeds = listOf("business", "world", "", "", "entertainment_and_arts", "science_and_environment", "technology")
    private lateinit var defaults: JSONObject
    private lateinit var video: PlayerView
    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var footer: TextView
    private var player: ExoPlayer? = null
    private var selected = 0
    private var automatic = true
    private var active = false
    private var generation = 0
    private var dataJob: java.util.concurrent.Future<*>? = null
    private var routes = emptyList<Pair<String,String>>()
    private var route = 0
    private val rotate = Runnable { if (active && automatic) select((0..3).filter { it != selected }.random()) }
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
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(28,20,28,20); setBackgroundColor(Color.rgb(7,16,25)) }
        fun label(size:Float) = TextView(this).apply { setTextColor(Color.WHITE); textSize=size; setPadding(8,8,8,8) }
        title=label(24f); footer=label(15f); info=label(19f)
        video=PlayerView(this).apply { useController=false }
        val body=LinearLayout(this)
        body.addView(video,LinearLayout.LayoutParams(0,-1,2f));body.addView(info,LinearLayout.LayoutParams(0,-1,1f))
        root.addView(title);root.addView(body,LinearLayout.LayoutParams(-1,0,1f));root.addView(footer);setContentView(root)
    }
    override fun onResume() { super.onResume();active=true;select(selected) }
    override fun onPause() {
        active=false;generation++;dataJob?.cancel(true);handler.removeCallbacksAndMessages(null);video.player=null;player?.release();player=null
        super.onPause()
    }
    override fun onDestroy() { worker.shutdownNow();super.onDestroy() }
    private fun select(index:Int) {
        selected=index;generation++;dataJob?.cancel(true);handler.removeCallbacksAndMessages(null)
        getSharedPreferences("my-channel",MODE_PRIVATE).edit().putInt("preset",selected).putBoolean("auto",automatic).apply()
        title.text="MY CHANNEL  •  ${names[selected]}"
        footer.text="← → Change screen   •   OK Auto: ${if(automatic) "ON · 15 minutes" else "OFF"}   •   MENU Next source   •   BACK Guide"
        info.text="Refreshing information…"
        val sources=defaults.getJSONObject(keys[selected]).getJSONArray("video")
        routes=buildList { for(i in 0 until sources.length()) { val source=sources.getJSONObject(i);val urls=source.getJSONArray("urls");for(j in 0 until urls.length()) add(source.getString("name") to urls.getString(j)) } }
        route=0;playRoute();refreshData();if(automatic)handler.postDelayed(rotate,900000)
    }
    private fun playRoute() {
        handler.removeCallbacks(retry);handler.removeCallbacks(timeout);video.player=null;player?.release();player=null
        if(route>=routes.size) { title.text="MY CHANNEL  •  ${names[selected]} · Video unavailable";handler.postDelayed(retry,60000);return }
        val entry=routes[route];title.text="MY CHANNEL  •  ${names[selected]}  |  ${entry.first}"
        val p=ExoPlayer.Builder(this).setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5000,15000,1000,2000).setTargetBufferBytes(8 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()).build()
        p.trackSelectionParameters=p.trackSelectionParameters.buildUpon().setMaxVideoSize(1280,720).build()
        player=p;video.player=p
        p.addListener(object:Player.Listener {
            override fun onPlayerError(error:PlaybackException) { if(player===p) nextRoute() }
            override fun onRenderedFirstFrame() { if(player===p) handler.removeCallbacks(timeout) }
            override fun onPlaybackStateChanged(state:Int) { if(player===p && state==Player.STATE_BUFFERING) { handler.removeCallbacks(timeout);handler.postDelayed(timeout,20000) } }
        })
        p.setMediaItem(MediaItem.fromUri(entry.second));p.prepare();p.playWhenReady=true;handler.postDelayed(timeout,20000)
    }
    private fun nextRoute() { route++;playRoute() }
    private fun refreshData() {
        val token=generation;val index=selected
        dataJob=worker.submit {
            val result=runCatching {
                val address=when(index) { 2 -> "https://feeds.elpais.com/mrss-s/pages/ep/site/elpais.com/section/espana/portada";3 -> "https://www.nasa.gov/feed/";else -> "https://feeds.bbci.co.uk/news/${feeds[index]}/rss.xml" }
                val connection=URL(address).openConnection().apply { connectTimeout=8000;readTimeout=8000 }
                val bytes=connection.getInputStream().use { input -> val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=1000000);out.write(buffer,0,n)};out.toByteArray() }
                val parser=Xml.newPullParser();parser.setInput(bytes.inputStream(),null)
                val headlines=mutableListOf<String>();var item=false
                while(parser.eventType!=XmlPullParser.END_DOCUMENT && headlines.size<5) {
                    if(parser.eventType==XmlPullParser.START_TAG) { if(parser.name=="item")item=true; if(item && parser.name=="title")headlines.add(parser.nextText()) }
                    if(parser.eventType==XmlPullParser.END_TAG && parser.name=="item")item=false
                    parser.next()
                }
                require(headlines.isNotEmpty())
                extraData(index)+"${when(index){2->"El País";3->"NASA";else->"BBC"}} · Updated ${java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date())}\n\n"+headlines.joinToString("\n\n")
            }.getOrNull()
            handler.post { if(active && generation==token) { if(result!=null) info.text=result else if(info.text.startsWith("Refreshing"))info.text="Information unavailable — retrying in five minutes" else info.text=info.text.toString().substringBefore("\n\nRefresh failed")+"\n\nRefresh failed · previous headlines retained";handler.postDelayed(refresh,300000) } }
        }
    }
    private fun extraData(index:Int):String {
        if(index!=0 && index!=3)return ""
        return runCatching {
            val url=if(index==0) "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml" else "https://services.swpc.noaa.gov/json/planetary_k_index_1m.json"
            val c=URL(url).openConnection().apply {connectTimeout=8000;readTimeout=8000}
            val raw=c.getInputStream().use { input -> val out=java.io.ByteArrayOutputStream();val b=ByteArray(8192);while(true){val n=input.read(b);if(n<0)break;require(out.size()+n<=1000000);out.write(b,0,n)};out.toString("UTF-8") }
            if(index==3) {
                val readings=org.json.JSONArray(raw);val last=readings.getJSONObject(readings.length()-1)
                "NOAA Kp: ${last.optString("kp_index")} · ${last.optString("time_tag")} UTC\n\n"
            } else {
                val xml=Xml.newPullParser();xml.setInput(raw.reader());val rates=mutableListOf<String>();var date=""
                while(xml.eventType!=XmlPullParser.END_DOCUMENT){if(xml.eventType==XmlPullParser.START_TAG){xml.getAttributeValue(null,"time")?.let{date=it};val currency=xml.getAttributeValue(null,"currency");if(currency in listOf("GBP","USD"))rates.add("$currency ${xml.getAttributeValue(null,"rate")}")};xml.next()}
                "ECB daily reference · $date\nEUR 1 = ${rates.joinToString(" / ")}\n\n"
            }
        }.getOrDefault("${if(index==0) "Currency rates" else "Space weather"} unavailable\n\n")
    }
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {
        if(event.repeatCount>0)return super.onKeyDown(keyCode,event)
        when(keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> select((selected+6)%7)
            KeyEvent.KEYCODE_DPAD_RIGHT -> select((selected+1)%7)
            KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> { automatic=!automatic;select(selected) }
            KeyEvent.KEYCODE_MENU -> nextRoute()
            else -> return super.onKeyDown(keyCode,event)
        };return true
    }
}
