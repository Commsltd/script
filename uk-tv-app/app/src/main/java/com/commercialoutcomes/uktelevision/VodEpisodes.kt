package com.commercialoutcomes.uktelevision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import java.net.URL

object VodEpisodes {
    suspend fun load(source: VodSource): List<VodTitle> = withContext(Dispatchers.IO) {
        require(source.provider.owns(source.url))
        if(source.provider==VodProvider.DULO)VodDiscovery.duloEpisodes(source) else parse(source,VodCatalogue.fetch(source.url))
    }
    fun parse(source: VodSource,doc: Document): List<VodTitle> {
        if(source.provider==VodProvider.MOVY) {
            val path=URL(source.url).path.trim('/').split('/')
            if(path.size !in 2..3 || path[0]!="tv" || path[1].toLongOrNull()==null)return emptyList()
            val details=org.json.JSONObject(doc.selectFirst("script#__NEXT_DATA__")?.data().orEmpty())
                .getJSONObject("props").getJSONObject("pageProps").getJSONObject("details")
            require(details.optString("tmdbId")==path[1]) { "The service returned a different series." }
            val seasons=details.optJSONArray("seasons") ?: return emptyList()
            val title=details.optString("title")
            val base="https://www.movy.sx/tv/${path[1]}"
            if(path.size==2)return (0 until seasons.length()).mapNotNull { i ->
                val season=seasons.getJSONObject(i);val number=season.optInt("season_number",-1)
                if(number<0 || season.optInt("episode_count")<=0)null else
                    VodTitle("$title · ${season.optString("name","Season $number")}",null,"season","",sources=listOf(VodSource(source.provider,"$base/$number")))
            }
            val selected=path[2].toIntOrNull() ?: return emptyList()
            val season=(0 until seasons.length()).map { seasons.getJSONObject(it) }.firstOrNull { it.optInt("season_number",-1)==selected } ?: return emptyList()
            return (1..season.optInt("episode_count").coerceIn(0,300)).map { episode ->
                VodTitle("$title · S$selected · Episode $episode",null,"episode","",sources=listOf(VodSource(source.provider,"$base/$selected/$episode")))
            }
        }
        val rows=mutableListOf<VodTitle>()
        val seriesPath=URL(source.url).path.substringBefore("/season/").trimEnd('/')
        for(a in doc.select("a[href]")) {
            val url=a.absUrl("href")
            if(!source.provider.owns(url))continue
            val path=URL(url).path
            val kind=when(source.provider) {
                VodProvider.PLEX -> when {
                    !path.startsWith("$seriesPath/season/")->null
                    path.contains("/episode/")->"episode"
                    !URL(source.url).path.contains("/season/")->"season"
                    else->null
                }
                VodProvider.TUBI -> if(Regex("^/tv-shows/\\d+/").containsMatchIn(path))"episode" else null
                VodProvider.PLUTO -> if(path.startsWith("$seriesPath/episode/") || path.startsWith("$seriesPath/episodes/"))"episode" else null
                VodProvider.MOVY, VodProvider.DULO -> null
            } ?: continue
            val name=a.attr("aria-label").ifBlank { a.text() }.ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }
            if(name.isBlank())continue
            rows.add(VodTitle(name,null,kind,a.selectFirst("img")?.absUrl("src").orEmpty(),sources=listOf(VodSource(source.provider,url))))
        }
        return rows.distinctBy { it.sources.first().url }
    }
}
