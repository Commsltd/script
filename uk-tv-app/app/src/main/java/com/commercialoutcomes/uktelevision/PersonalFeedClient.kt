package com.commercialoutcomes.uktelevision
import org.json.JSONObject
import java.net.URL
import java.util.Locale
object PersonalFeedClient {
 fun snapshot(domain:String):String {
  fun get(url:String):String {val request=okhttp3.Request.Builder().url(url).build();return PublicVodHttp.client.newCall(request).execute().use {r->check(r.isSuccessful);r.body!!.byteStream().use{it.readBounded(262144)}.toString(Charsets.UTF_8)}}
  val data=runCatching {JSONObject(get("https://raw.githubusercontent.com/Commsltd/script/uk-tv-app-data/youtube-status.json")).getJSONObject("personalFeeds")}.getOrElse {JSONObject(get("https://raw.githubusercontent.com/Commsltd/script/uk-tv-personal-data/feeds.json"))};val cards=mutableListOf<String>()
  if(domain=="business") {
   val fx=data.optJSONObject("fx");val rates=fx?.optJSONObject("rates");val gbp=rates?.optDouble("GBP",Double.NaN) ?: Double.NaN;val usd=rates?.optDouble("USD",Double.NaN) ?: Double.NaN
   if(gbp.isFinite()&&gbp>0&&usd.isFinite())cards.add("CURRENCY REFERENCE\n£1 → €${String.format(Locale.UK,"%.4f",1/gbp)}   ·   USD ${String.format(Locale.UK,"%.4f",usd/gbp)}\nECB · ${fx?.optString("date")} · daily reference${if(fx?.optString("status")=="stale") " · cached" else ""}")
  }
  if(domain=="space") {val kp=data.optJSONObject("kp");val r=runCatching {val rows=org.json.JSONArray(get("https://services.swpc.noaa.gov/json/planetary_k_index_1m.json"));rows.getJSONObject(rows.length()-1)}.getOrNull() ?: kp?.optJSONObject("reading");if(r!=null)cards.add("SPACE WEATHER\nPlanetary Kp ${r.optString("kp_index","Unavailable")}\nNOAA · ${r.optString("time_tag")} UTC${if(kp?.optString("status")=="stale") " · cached" else ""}")}
  val news=data.optJSONObject(domain) ?: error("No information");var items=news.optJSONArray("items") ?: error("No headlines")
  runCatching {
   val parser=android.util.Xml.newPullParser();parser.setInput(get(news.getString("url")).reader())
   val fresh=org.json.JSONArray();var item:JSONObject?=null
   while(parser.eventType!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT && fresh.length()<3){
    if(parser.eventType==org.xmlpull.v1.XmlPullParser.START_TAG){when(parser.name){"item"->item=JSONObject();"title"->if(item!=null)item.put("title",parser.nextText());"pubDate"->if(item!=null)item.put("date",parser.nextText());"link"->if(item!=null)item.put("url",parser.nextText())}}
    if(parser.eventType==org.xmlpull.v1.XmlPullParser.END_TAG && parser.name=="item"){item?.let{fresh.put(it)};item=null};parser.next()
   }
   if(fresh.length()>0){items=fresh;news.put("status","ok")}
  }.onFailure {news.put("status","stale")}
  cards.add("${news.optString("source")} · HEADLINES${if(news.optString("status")=="stale") " · CACHED" else ""}\n\n"+(0 until minOf(3,items.length())).joinToString("\n\n"){i->val item=items.getJSONObject(i);"${item.optString("title").take(240)}\n${item.optString("date").take(60)}"})
  return cards.joinToString("\n\n────────────────\n\n")
 }
}
