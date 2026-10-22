package com.commercialoutcomes.uktelevision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Public metadata endpoint used by Movy's own search. No provider API key is
 * copied into the app. Metadata identifies a title, not playback availability. */
object VodDiscovery {
    data class Page(val titles:List<VodTitle>,val page:Int,val pages:Int)
    suspend fun search(query:String,page:Int=1):Page=withContext(Dispatchers.IO) {
        require(query.trim().length in 2..120)
        parse(json("/search/multi?query=${URLEncoder.encode(query.trim(),"UTF-8")}&page=$page&include_adult=false&language=en-GB"))
    }
    suspend fun popular(kind:String="movie",page:Int=1):Page=withContext(Dispatchers.IO) {
        require(kind in setOf("movie","tv"))
        parse(json("/$kind/popular?page=$page&language=en-GB"),kind)
    }
    fun json(path:String):JSONObject {
        require(path.startsWith("/")&&!path.startsWith("//"))
        val c=URL("https://db.wecollege.net/3$path").openConnection() as HttpURLConnection
        c.connectTimeout=6000;c.readTimeout=8000
        c.setRequestProperty("User-Agent","Mozilla/5.0");c.setRequestProperty("Referer","https://www.movy.sx/")
        try {
            require(c.responseCode==200){"Online catalogue returned HTTP ${c.responseCode}"}
            val bytes=c.inputStream.use { input ->
                val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192)
                while(true){val n=input.read(buf);if(n<0)break;require(out.size()+n<=2*1024*1024){"Catalogue response too large"};out.write(buf,0,n)}
                out.toByteArray()
            }
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { c.disconnect() }
    }
    fun parse(json:JSONObject,fallbackKind:String=""):Page {
        val results=json.optJSONArray("results")
        val titles=(0 until (results?.length()?:0)).mapNotNull { i ->
            val row=results!!.getJSONObject(i);val type=row.optString("media_type",fallbackKind)
            val id=row.optLong("id",0);val name=row.optString(if(type=="tv")"name" else "title")
            if(type !in setOf("movie","tv") || id<=0 || name.isBlank() || row.optBoolean("adult"))return@mapNotNull null
            val date=row.optString(if(type=="tv")"first_air_date" else "release_date")
            val poster=row.optString("poster_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w342$it" }.orEmpty()
            VodTitle(name,date.take(4).toIntOrNull(),if(type=="tv")"show" else "movie",poster,row.optString("overview"),
                listOf(VodSource(VodProvider.MOVY,"https://www.movy.sx/$type/$id"),VodSource(VodProvider.DULO,"https://dulo.mov/title/$type/$id/")),VodGenres.ids(row.optJSONArray("genre_ids")))
        }
        return Page(VodCatalogue.merge(titles),json.optInt("page",1),json.optInt("total_pages",1).coerceIn(0,500))
    }
    suspend fun duloEpisodes(source:VodSource):List<VodTitle> = withContext(Dispatchers.IO) {
        val uri=java.net.URI(source.url)
        val id=Regex("^/title/tv/(\\d+)/?$").matchEntire(uri.path)?.groupValues?.get(1) ?: return@withContext emptyList()
        val season=uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("season=") }?.substringAfter('=')?.toIntOrNull()
        val detail=json("/tv/$id?language=en-GB")
        require(detail.optLong("id").toString()==id){"Series identity changed"}
        val name=detail.getString("name")
        if(season==null) {
            val seasons=detail.optJSONArray("seasons") ?: return@withContext emptyList()
            return@withContext (0 until seasons.length()).mapNotNull { i ->
                val s=seasons.getJSONObject(i);val n=s.optInt("season_number",-1)
                if(n<0 || s.optInt("episode_count")<=0)null else VodTitle("$name · ${s.optString("name","Season $n")}",null,"season","",sources=listOf(VodSource(VodProvider.DULO,"https://dulo.mov/title/tv/$id/?season=$n")))
            }
        }
        require(season>=0)
        val data=json("/tv/$id/season/$season?language=en-GB")
        require(data.optInt("season_number",-1)==season){"Season identity changed"}
        parseDuloEpisodes(id,name,season,data)
    }
    fun parseDuloEpisodes(id:String,name:String,season:Int,data:JSONObject):List<VodTitle> {
        val episodes=data.optJSONArray("episodes") ?: return emptyList()
        return (0 until episodes.length()).mapNotNull { i ->
            val ep=episodes.getJSONObject(i);val n=ep.optInt("episode_number",0)
            if(n<=0 || ep.optInt("season_number",season)!=season)null else
                VodTitle("$name · S$season E$n · ${ep.optString("name","Episode $n")}",null,"episode","",ep.optString("overview"),
                    listOf(VodSource(VodProvider.DULO,"https://dulo.mov/watch/tv/$id?s=$season&e=$n")))
        }
    }
}
