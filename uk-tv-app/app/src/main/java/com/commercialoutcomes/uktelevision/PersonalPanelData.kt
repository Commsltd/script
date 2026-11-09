package com.commercialoutcomes.uktelevision

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Bounded data, independent from the views, network and video lifecycle. */
data class PersonalItem(val headline:String,val body:String="",val observed:String="",val url:String="")
data class PersonalPanel(val id:String,val title:String,val source:String,val items:List<PersonalItem>,
    val fetchedAt:Long=System.currentTimeMillis(),val stale:Boolean=false,val series:List<Double> = emptyList(),val seriesLabel:String="") {
    fun json():String = JSONObject().put("id",id).put("title",title).put("source",source).put("fetchedAt",fetchedAt).put("stale",stale)
        .put("items",JSONArray(items.take(8).map { JSONObject().put("headline",it.headline).put("body",it.body).put("observed",it.observed).put("url",it.url) }))
        .put("series",JSONArray(series.takeLast(40))).put("seriesLabel",seriesLabel).toString()
    companion object { fun decode(raw:String):PersonalPanel {
        val o=JSONObject(raw);val rows=o.getJSONArray("items");require(rows.length()>0);val points=o.optJSONArray("series") ?: JSONArray()
        return PersonalPanel(o.getString("id"),o.getString("title"),o.getString("source"),(0 until minOf(rows.length(),8)).map{val r=rows.getJSONObject(it);PersonalItem(r.optString("headline").take(240),r.optString("body").take(700),r.optString("observed").take(80),r.optString("url").take(1000))},o.getLong("fetchedAt"),o.optBoolean("stale"),(0 until minOf(points.length(),40)).map{points.getDouble(it)}.filter{it.isFinite()},o.optString("seriesLabel"))
    }}
}
data class PersonalFeed(val id:String,val kind:String,val title:String,val source:String,val url:String,val ttl:Long) {
    companion object {fun decode(o:JSONObject)=PersonalFeed(o.getString("id"),o.getString("kind"),o.getString("title"),o.getString("source"),o.getString("url"),o.optLong("ttl",300)*1000)}
}
object PersonalPanelParser {
    fun clean(s:String)=Jsoup.parse(s).text().replace(Regex("\\s+")," ").trim()
    fun parse(spec:PersonalFeed,raw:String,now:Long=System.currentTimeMillis()):PersonalPanel {
        var points=emptyList<Double>();var label=""
        val rows:List<PersonalItem> = when(spec.kind) {
            "rss" -> {
                val doc=Jsoup.parse(raw,"",Parser.xmlParser())
                doc.select("item, entry").take(8).map { e ->
                    val date=e.selectFirst("pubDate, published, updated")?.text().orEmpty()
                    PersonalItem(clean(e.selectFirst("title")?.text().orEmpty()).take(240),"",date,
                        e.selectFirst("link")?.let{it.attr("href").ifBlank{it.text()}}.orEmpty())
                }.filter{it.headline.isNotBlank()}.distinctBy{it.headline.lowercase(Locale.ROOT)}
            }
            "fx" -> {
                val dates=Jsoup.parse(raw,"",Parser.xmlParser()).select("Cube[time]")
                val latest=dates.maxByOrNull{it.attr("time")} ?: error("No currency observations")
                fun rate(e:org.jsoup.nodes.Element,c:String)=e.children().first{it.attr("currency")==c}.attr("rate").toDouble().also{require(it.isFinite()&&it>0)}
                val gbp=rate(latest,"GBP");val usd=rate(latest,"USD");val date=latest.attr("time")
                points=dates.sortedBy{it.attr("time")}.takeLast(30).map{1/rate(it,"GBP")};label="GBP / EUR · 30 observations"
                listOf(PersonalItem("£1 = €${fmt(1/gbp,4)}","Daily reference · not a live quote",date,spec.url),PersonalItem("£1 = $${fmt(usd/gbp,4)}","GBP / USD · daily reference",date,spec.url),PersonalItem("€1 = $${fmt(usd,4)}","EUR / USD · daily reference",date,spec.url))
            }
            "weather" -> {
                val o=JSONObject(raw);val c=o.getJSONObject("current");val d=o.getJSONObject("daily");val units=o.getJSONObject("current_units");val zone=o.optString("timezone")
                val dates=d.getJSONArray("time");val hi=d.getJSONArray("temperature_2m_max");val lo=d.getJSONArray("temperature_2m_min");val rain=d.getJSONArray("precipitation_probability_max")
                listOf(PersonalItem("${fmt(c.getDouble("temperature_2m"),0)}°C · ${weather(c.optInt("weather_code"))}","Wind ${fmt(c.getDouble("wind_speed_10m"),0)} ${units.optString("wind_speed_10m")}  ·  Humidity ${c.optInt("relative_humidity_2m")}%",c.getString("time")+" · "+zone,spec.url)) + (0 until minOf(3,dates.length())).map{i->PersonalItem("${dates.getString(i)} · ${fmt(lo.getDouble(i),0)}–${fmt(hi.getDouble(i),0)}°C","Rain chance ${rain.optInt(i)}%  ·  Sunrise ${d.getJSONArray("sunrise").getString(i).substringAfter('T')} / sunset ${d.getJSONArray("sunset").getString(i).substringAfter('T')}","Forecast · "+zone,spec.url)}
            }
            "kp" -> {
                val a=JSONArray(raw);val samples=(0 until a.length()).map{a.getJSONObject(it)}.sortedBy{it.optString("time_tag")};val r=samples.last()
                points=samples.takeLast(30).map{it.optDouble("estimated_kp",it.optDouble("kp_index"))}.filter{it.isFinite()};label="Kp · latest observations"
                listOf(PersonalItem("Planetary Kp ${r.get("kp_index")}","Estimated ${fmt(r.optDouble("estimated_kp",r.optDouble("kp_index")),1)} · scale 0–9",r.getString("time_tag")+" UTC",spec.url))
            }
            "iss" -> {
                val o=JSONObject(raw);val stamp=utc(o.getLong("timestamp")*1000)
                listOf(PersonalItem("ISS · ${o.optString("visibility")}","${fmt(o.getDouble("latitude"),2)}° latitude  ·  ${fmt(o.getDouble("longitude"),2)}° longitude",stamp,spec.url),PersonalItem("${fmt(o.getDouble("altitude"),0)} km above Earth","Speed ${fmt(o.getDouble("velocity"),0)} km/h",stamp,spec.url))
            }
            "show" -> {
                val o=JSONObject(raw);val cast=o.optJSONObject("_embedded")?.optJSONArray("cast") ?: JSONArray()
                listOf(PersonalItem(o.getString("name")+" · "+o.optString("premiered").take(4),clean(o.optString("summary")).take(700),"Programme reference · ${o.optString("status")}",o.optString("url")),PersonalItem("Cast",(0 until minOf(6,cast.length())).joinToString(" · "){cast.getJSONObject(it).getJSONObject("person").getString("name")},"TVmaze · CC BY-SA",o.optString("url")))
            }
            "ons" -> Jsoup.parse(raw).select("a[data-gtm-release-title]").take(8).map{e->PersonalItem(e.attr("data-gtm-release-title"),"Economic release · ONS",e.attr("data-gtm-release-date").let{if(it.length==8)"${it.take(4)}-${it.substring(4,6)}-${it.takeLast(2)}" else it}+" "+e.attr("data-gtm-release-time")+" UK", "https://www.ons.gov.uk"+e.attr("href"))}
            "quakes" -> {
                val a=JSONObject(raw).getJSONArray("features")
                (0 until minOf(a.length(),8)).map { val p=a.getJSONObject(it).getJSONObject("properties");PersonalItem("M${fmt(p.getDouble("mag"),1)} · ${p.optString("place")}","Earthquake observation · USGS",utc(p.getLong("time")),p.optString("url")) }
            }
            else -> error("Unknown feed parser")
        }
        require(rows.isNotEmpty()){"No valid records"}
        return PersonalPanel(spec.id,spec.title,spec.source,rows,now,series=points,seriesLabel=label)
    }
    fun utc(time:Long)=SimpleDateFormat("d MMM HH:mm 'UTC'",Locale.UK).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(java.util.Date(time))
    private fun fmt(n:Double,d:Int):String {require(n.isFinite());return String.format(Locale.UK,"%.${d}f",n)}
    private fun weather(code:Int)=when(code){0->"Clear";1,2->"Partly cloudy";3->"Overcast";45,48->"Fog";in 51..67->"Rain";in 71..77->"Snow";in 80..82->"Showers";in 95..99->"Thunderstorms";else->"Conditions"}
}
