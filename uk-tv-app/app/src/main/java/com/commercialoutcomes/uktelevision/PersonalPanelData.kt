package com.commercialoutcomes.uktelevision

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Bounded data, independent from the views, network and video lifecycle. */
data class PersonalItem(val headline:String,val body:String="",val observed:String="",val url:String="",val metric:String="",val number:String="",val change:Double?=null,val history:List<Double> = emptyList())
data class PersonalPanel(val id:String,val title:String,val source:String,val items:List<PersonalItem>,
    val fetchedAt:Long=System.currentTimeMillis(),val stale:Boolean=false,val series:List<Double> = emptyList(),val seriesLabel:String="",val available:Boolean=true) {
    fun json():String = JSONObject().put("id",id).put("title",title).put("source",source).put("fetchedAt",fetchedAt).put("stale",stale).put("available",available)
        .put("items",JSONArray(items.take(8).map { JSONObject().put("headline",it.headline).put("body",it.body).put("observed",it.observed).put("url",it.url).put("metric",it.metric).put("number",it.number).put("change",it.change).put("history",JSONArray(it.history.takeLast(30))) }))
        .put("series",JSONArray(series.takeLast(40))).put("seriesLabel",seriesLabel).toString()
    companion object { fun decode(raw:String):PersonalPanel {
        val o=JSONObject(raw);val rows=o.getJSONArray("items");require(rows.length()>0);val points=o.optJSONArray("series") ?: JSONArray()
        return PersonalPanel(o.getString("id"),o.getString("title"),o.getString("source"),(0 until minOf(rows.length(),8)).map{val r=rows.getJSONObject(it);PersonalItem(r.optString("headline").take(240),r.optString("body").take(700),r.optString("observed").take(80),r.optString("url").take(1000),r.optString("metric"),r.optString("number"),r.optDouble("change").takeIf{it.isFinite()},r.optJSONArray("history")?.let{a->(0 until minOf(a.length(),30)).map{a.getDouble(it)}.filter{it.isFinite()}} ?: emptyList())},o.getLong("fetchedAt"),o.optBoolean("stale"),(0 until minOf(points.length(),40)).map{points.getDouble(it)}.filter{it.isFinite()},o.optString("seriesLabel"),o.optBoolean("available",true))
    }}
}
data class PersonalFeed(val id:String,val kind:String,val title:String,val source:String,val url:String,val ttl:Long,val maxAge:Long=0,val alternatives:List<PersonalFeed> = emptyList()) {
    companion object {fun decode(o:JSONObject):PersonalFeed=PersonalFeed(o.getString("id"),o.getString("kind"),o.getString("title"),o.getString("source"),o.getString("url"),o.optLong("ttl",300)*1000,o.optLong("maxAge",0)*1000,o.optJSONArray("alternatives")?.let{a->(0 until a.length()).map{decode(a.getJSONObject(it).put("id",o.getString("id")).put("title",o.getString("title")))}} ?: emptyList())}
}
object PersonalPanelParser {
    fun clean(s:String)=Jsoup.parse(s).text().replace(Regex("\\s+")," ").trim()
    fun parse(spec:PersonalFeed,raw:String,now:Long=System.currentTimeMillis()):PersonalPanel {
        var points=emptyList<Double>();var label=""
        val rows:List<PersonalItem> = when(spec.kind) {
            "rss" -> {
                val doc=Jsoup.parse(raw,"",Parser.xmlParser())
                doc.select("item, entry").take(24).map { e ->
                    val date=e.selectFirst("pubDate, published, updated")?.text().orEmpty()
                    PersonalItem(clean(e.selectFirst("title")?.text().orEmpty()).take(240),clean(e.selectFirst("description, summary, content")?.text().orEmpty()).take(550),date,
                        e.selectFirst("link")?.let{it.attr("href").ifBlank{it.text()}}.orEmpty())
                }.filter{it.headline.isNotBlank() && !(spec.id=="nasa" && (it.url.contains("/es/") || Regex("(?i)^(la nasa |el |los |las |¿|¡)").containsMatchIn(it.headline)))}
                .filter{spec.maxAge==0L || publishedTime(it.observed)?.let{stamp->now-stamp<=spec.maxAge && stamp-now<86400000}==true}
                .distinctBy{it.headline.lowercase(Locale.ROOT)}.take(8)
            }
            "market" -> {
                val r=JSONObject(raw).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
                val m=r.getJSONObject("meta");val q=r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close")
                val values=(0 until q.length()).mapNotNull{q.optDouble(it).takeIf{v->v.isFinite() && v>0}}.takeLast(30)
                val price=m.getDouble("regularMarketPrice");val previous=values.dropLast(1).lastOrNull()
                val delta=previous?.let{(price/it-1)*100}
                listOf(PersonalItem(spec.title,"Delayed market indication · prior-session comparison · "+m.optString("currency"),utc(m.getLong("regularMarketTime")*1000),"https://finance.yahoo.com/quote/"+m.getString("symbol"),spec.title,fmt(price,2),delta,values))
            }
            "launches" -> {
                val a=JSONObject(raw).getJSONArray("results")
                (0 until a.length()).map{a.getJSONObject(it)}.filter{publishedTime(it.optString("net"))?.let{t->t>=now}==true}.take(6).map{r->PersonalItem(r.getString("name"),r.optJSONObject("mission")?.optString("description").orEmpty().take(450)+" · "+r.optJSONObject("status")?.optString("name").orEmpty(),r.getString("net")+" · schedule may change",r.optString("url"))}.ifEmpty{listOf(PersonalItem("No future launches in this response","Check again later; schedules can change."))}
            }
            "alerts" -> {
                val doc=Jsoup.parse(raw,"",Parser.xmlParser())
                require(doc.selectFirst("channel")!=null)
                doc.select("item").map{e->PersonalItem(clean(e.selectFirst("title")?.text().orEmpty()),clean(e.selectFirst("description")?.text().orEmpty()),e.selectFirst("pubDate")?.text().orEmpty(),e.selectFirst("link")?.text().orEmpty())}
                    .filter{item->Regex("a (\\d{2}:\\d{2} \\d{2}-\\d{2}-\\d{4})").find(item.body)?.groupValues?.get(1)?.let{end->runCatching{SimpleDateFormat("HH:mm dd-MM-yyyy",Locale.UK).apply{timeZone=TimeZone.getTimeZone("Europe/Madrid")}.parse(end)!!.time>now}.getOrDefault(false)}==true}
                    .take(8).ifEmpty{listOf(PersonalItem("No current warnings returned","Spain-wide feed. Check AEMET for the latest local detail.",utc(now),"https://www.aemet.es/es/eltiempo/prediccion/avisos"))}
            }
            "github-release" -> {
                val o=JSONObject(raw);require(!o.optBoolean("draft"))
                listOf(PersonalItem(o.getString("tag_name"),clean(o.optString("body")).take(550).ifBlank{if(o.optBoolean("prerelease"))"Pre-release" else "Published release"},o.getString("published_at"),o.getString("html_url")))
            }
            "fx", "fx-json" -> {
                val observations=sortedMapOf<String,MutableMap<String,Double>>()
                if(spec.kind=="fx")Jsoup.parse(raw,"",Parser.xmlParser()).select("Cube[time]").forEach{day->
                    observations[day.attr("time")]=day.children().associate{it.attr("currency") to it.attr("rate").toDouble()}.toMutableMap()
                }else{val a=JSONArray(raw);for(i in 0 until a.length()){val r=a.getJSONObject(i);require(r.getString("base").equals("EUR",true));observations.getOrPut(r.getString("date")){mutableMapOf()}[r.getString("quote").uppercase(Locale.ROOT)]=r.getDouble("rate")}}
                require(observations.isNotEmpty())
                val date=observations.lastKey();val result=mutableListOf<PersonalItem>()
                val pairs=listOf(Triple("GBP / EUR","GBP","EUR"),Triple("GBP / USD","GBP","USD"),Triple("EUR / USD","EUR","USD"),Triple("EUR / JPY","EUR","JPY"),Triple("EUR / CHF","EUR","CHF"))
                fun symbol(c:String)=when(c){"GBP"->"£";"EUR"->"€";"USD"->"$";else->c+" "}
                for((name,base,quote) in pairs){
                    val history=observations.values.mapNotNull{rates->val a=if(base=="EUR")1.0 else rates[base];val b=if(quote=="EUR")1.0 else rates[quote];if(a!=null && b!=null && a>0 && b>0 && a.isFinite() && b.isFinite())b/a else null}.takeLast(30)
                    if(history.isEmpty())continue
                    val rate=history.last();val change=if(history.size>1)(rate/history[history.lastIndex-1]-1)*100 else null
                    result+=PersonalItem("${symbol(base)}1 = ${symbol(quote)}${fmt(rate,4)}","Daily reference · not a live quote",date,spec.url,name,fmt(rate,4),change,history)
                }
                points=result.first().history;label="GBP / EUR · 30 observations";result
            }
            "weather" -> {
                val o=JSONObject(raw);val c=o.getJSONObject("current");val d=o.getJSONObject("daily");val units=o.getJSONObject("current_units");val zone=o.optString("timezone")
                val dates=d.getJSONArray("time");val hi=d.getJSONArray("temperature_2m_max");val lo=d.getJSONArray("temperature_2m_min");val rain=d.getJSONArray("precipitation_probability_max")
                val location=spec.id.uppercase(Locale.ROOT)
                listOf(PersonalItem("${fmt(c.getDouble("temperature_2m"),0)}°C · ${weather(c.optInt("weather_code"))}","${weather(c.optInt("weather_code"))} · wind ${fmt(c.getDouble("wind_speed_10m"),0)} ${units.optString("wind_speed_10m")} · humidity ${c.optInt("relative_humidity_2m")}%",c.getString("time")+" · "+zone,spec.url,"NOW · $location","${fmt(c.getDouble("temperature_2m"),0)}°C")) + (0 until minOf(3,dates.length())).map{i->
                    val day=SimpleDateFormat("EEE d MMM",Locale.UK).format(SimpleDateFormat("yyyy-MM-dd",Locale.UK).parse(dates.getString(i))!!).uppercase(Locale.ROOT)
                    PersonalItem("$day · ${fmt(lo.getDouble(i),0)}–${fmt(hi.getDouble(i),0)}°C","Rain ${rain.optInt(i)}% · sunrise ${d.getJSONArray("sunrise").getString(i).substringAfter('T')} / sunset ${d.getJSONArray("sunset").getString(i).substringAfter('T')}","Forecast · "+zone,spec.url,day,"${fmt(lo.getDouble(i),0)}–${fmt(hi.getDouble(i),0)}°C")
                }
            }
            "kp" -> {
                val a=JSONArray(raw);val samples=(0 until a.length()).map{a.getJSONObject(it)}.sortedBy{it.optString("time_tag")};val r=samples.last()
                points=samples.takeLast(30).map{it.optDouble("estimated_kp",it.optDouble("kp_index"))}.filter{it.isFinite()};label="Kp · latest observations"
                listOf(PersonalItem("Planetary Kp ${r.get("kp_index")}","Estimated ${fmt(r.optDouble("estimated_kp",r.optDouble("kp_index")),1)} · scale 0–9",r.getString("time_tag")+" UTC",spec.url))
            }
            "iss" -> {
                val o=JSONObject(raw);val stamp=utc(o.getLong("timestamp")*1000)
                listOf(
                    PersonalItem("ISS · ${o.optString("visibility")}","Observed position, not a flight prediction",stamp,spec.url,"ILLUMINATION",o.optString("visibility").uppercase(Locale.ROOT)),
                    PersonalItem("${fmt(o.getDouble("altitude"),0)} km above Earth","ISS altitude",stamp,spec.url,"ALTITUDE","${fmt(o.getDouble("altitude"),0)} km"),
                    PersonalItem("${fmt(o.getDouble("velocity"),0)} km/h","ISS orbital speed",stamp,spec.url,"SPEED","${fmt(o.getDouble("velocity"),0)} km/h"),
                    PersonalItem("Latitude ${fmt(o.getDouble("latitude"),2)}°","ISS latitude",stamp,spec.url,"LATITUDE",fmt(o.getDouble("latitude"),2)),
                    PersonalItem("Longitude ${fmt(o.getDouble("longitude"),2)}°","ISS longitude",stamp,spec.url,"LONGITUDE",fmt(o.getDouble("longitude"),2))
                )
            }
            "show" -> {
                val o=JSONObject(raw);val cast=o.optJSONObject("_embedded")?.optJSONArray("cast") ?: JSONArray()
                listOf(PersonalItem(o.getString("name")+" · "+o.optString("premiered").take(4),clean(o.optString("summary")).take(700),"Programme reference · ${o.optString("status")}",o.optString("url")),PersonalItem("Cast",(0 until minOf(6,cast.length())).joinToString(" · "){cast.getJSONObject(it).getJSONObject("person").getString("name")},"TVmaze · CC BY-SA",o.optString("url")))
            }
            "ons", "ons-upcoming" -> Jsoup.parse(raw).select("a[data-gtm-release-title]").filter{e->spec.kind!="ons-upcoming" || (Regex("(?i)inflation|consumer price|labour market|GDP|gross domestic|retail sales|public sector finances|trade|interest").containsMatchIn(e.attr("data-gtm-release-title")) && runCatching{SimpleDateFormat("yyyyMMdd",Locale.UK).parse(e.attr("data-gtm-release-date"))!!.time>=now-86400000}.getOrDefault(false))}.distinctBy{e->if(spec.kind=="ons-upcoming")e.attr("data-gtm-release-date")+":"+(Regex("(?i)labour market|consumer price|inflation|gross domestic|GDP|retail sales|public sector finances|trade|interest").find(e.attr("data-gtm-release-title"))?.value?.lowercase(Locale.ROOT) ?: e.attr("data-gtm-release-title")) else e.attr("data-gtm-release-title")}.take(8).map{e->PersonalItem(e.attr("data-gtm-release-title"),"Economic release · ONS",e.attr("data-gtm-release-date").let{if(it.length==8)"${it.take(4)}-${it.substring(4,6)}-${it.takeLast(2)}" else it}+" "+e.attr("data-gtm-release-time")+" UK", "https://www.ons.gov.uk"+e.attr("href"))}
            "quakes" -> {
                val a=JSONObject(raw).getJSONArray("features")
                (0 until minOf(a.length(),8)).map { val p=a.getJSONObject(it).getJSONObject("properties");PersonalItem("M${fmt(p.getDouble("mag"),1)} · ${p.optString("place")}","Earthquake observation · USGS",utc(p.getLong("time")),p.optString("url")) }
            }
            else -> error("Unknown feed parser")
        }
        require(rows.isNotEmpty()){"No valid records"}
        return PersonalPanel(spec.id,spec.title,spec.source,rows,now,series=points,seriesLabel=label)
    }
    fun publishedTime(raw:String):Long? {
        val normalized=raw.trim().replace(Regex("Z$"),"+0000").replace(Regex("([+-]\\d{2}):(\\d{2})$"),"$1$2")
        return listOf("EEE, dd MMM yyyy HH:mm:ss Z","yyyy-MM-dd'T'HH:mm:ssZ","yyyy-MM-dd'T'HH:mm:ss.SSSZ","yyyy-MM-dd").firstNotNullOfOrNull{pattern->runCatching{SimpleDateFormat(pattern,Locale.ENGLISH).apply{isLenient=false;timeZone=TimeZone.getTimeZone("UTC")}.parse(normalized)?.time}.getOrNull()}
    }
    fun utc(time:Long)=SimpleDateFormat("d MMM HH:mm 'UTC'",Locale.UK).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(java.util.Date(time))
    private fun fmt(n:Double,d:Int):String {require(n.isFinite());return String.format(Locale.UK,"%.${d}f",n)}
    private fun weather(code:Int)=when(code){0->"Clear";1,2->"Partly cloudy";3->"Overcast";45,48->"Fog";in 51..67->"Rain";in 71..77->"Snow";in 80..82->"Showers";in 95..99->"Thunderstorms";else->"Conditions"}
}
