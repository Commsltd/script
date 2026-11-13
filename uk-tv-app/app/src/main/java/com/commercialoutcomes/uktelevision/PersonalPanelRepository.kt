package com.commercialoutcomes.uktelevision

import android.content.Context
import org.json.JSONObject
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Fetches only the visible domain. Every feed has its own disk cache and failure state. */
class PersonalPanelRepository(context:Context) : AutoCloseable {
    private val config=JSONObject(context.assets.open("my-channel/panel-feeds.json").bufferedReader().use{it.readText()})
    val specs=config.getJSONObject("feeds").let{o->o.keys().asSequence().associateWith{PersonalFeed.decode(o.getJSONObject(it))}}
    private val directory=File(context.cacheDir,"personal-panels-v5").apply{mkdirs()}
    private val executor=Executors.newFixedThreadPool(2)
    private val calls=java.util.Collections.newSetFromMap(ConcurrentHashMap<Call,Boolean>())
    private val failures=ConcurrentHashMap<String,Int>()
    private val retryAt=ConcurrentHashMap<String,Long>()
    private val tasks=mutableListOf<Future<*>>()
    private val client=PublicVodHttp.client.newBuilder().callTimeout(15,TimeUnit.SECONDS).build()
    @Volatile private var generation=0
    fun groups(domain:String,weather:String="yellowstone"):List<List<String>> {val a=config.getJSONObject("domains").getJSONArray(domain);return (0 until a.length()).map{i->val g=a.getJSONArray(i);(0 until g.length()).map{g.getString(it).let{key->if(key=="camera-weather")weather else key}}}}
    fun cached(id:String):PersonalPanel? = runCatching {
        val file=File(directory,"$id.json");require(file.length() in 1..65536)
        val panel=PersonalPanel.decode(file.readText());panel.copy(stale=panel.stale || System.currentTimeMillis()-panel.fetchedAt>(specs[id]?.ttl ?: 0))
    }.getOrNull()
    @Synchronized fun cancel() {generation++;tasks.forEach{it.cancel(true)};tasks.clear();calls.forEach{it.cancel()};calls.clear()}
    @Synchronized fun refresh(domain:String,force:Boolean=false,weather:String="yellowstone",updated:(String,PersonalPanel)->Unit) {
        cancel();val token=generation
        groups(domain,weather).flatten().distinct().mapNotNull{specs[it]}.forEach { spec ->
            val previous=cached(spec.id)
            if(previous!=null)updated(spec.id,previous)
            if(!force && previous!=null && !previous.stale)return@forEach
            if(!force && System.currentTimeMillis()<(retryAt[spec.id] ?: 0L)){
                if(previous==null)updated(spec.id,unavailable(spec))
                return@forEach
            }
            tasks+=executor.submit {
                if(token!=generation)return@submit
                var loaded:PersonalPanel?=null
                for(candidate in listOf(spec)+spec.alternatives){
                    if(token!=generation || Thread.currentThread().isInterrupted)return@submit
                    loaded=runCatching {
                        val from=java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.UK).apply{timeZone=java.util.TimeZone.getTimeZone("UTC")}.format(java.util.Date(System.currentTimeMillis()-90L*86400000))
                        val url=candidate.url.replace("{from90}",from)
                        val call=client.newCall(Request.Builder().url(url).header("User-Agent","UKTelevision/0.9.12 personal-channel").build());calls.add(call)
                        if(token!=generation){call.cancel();calls.remove(call);return@submit}
                        val raw=try{call.execute().use{r->check(r.isSuccessful){"HTTP ${r.code}"};r.body!!.byteStream().use{it.readBounded(524288)}.toString(Charsets.UTF_8)}}finally{calls.remove(call)}
                        PersonalPanelParser.parse(candidate.copy(url=url),raw)
                    }.getOrNull()
                    if(loaded!=null)break
                }
                val result=loaded ?: previous?.copy(stale=true) ?: unavailable(spec)
                if(token==generation && !Thread.currentThread().isInterrupted){
                    if(result.stale){val count=((failures[spec.id] ?: 0)+1).coerceAtMost(5);failures[spec.id]=count;retryAt[spec.id]=System.currentTimeMillis()+(60000L shl (count-1))+(Math.random()*15000).toLong()}
                    else {failures.remove(spec.id);retryAt.remove(spec.id)}
                    if(!result.stale)runCatching {val temp=File(directory,"${spec.id}.tmp");temp.writeText(result.json());check(temp.renameTo(File(directory,"${spec.id}.json")))}
                    updated(spec.id,result)
                }
            }
        }
    }
    private fun unavailable(spec:PersonalFeed)=PersonalPanel(spec.id,spec.title,spec.source,listOf(PersonalItem("Temporarily unavailable","Other panels and the picture continue. Retry from MENU.")),stale=true,available=false)
    override fun close(){cancel();executor.shutdownNow()}
}
