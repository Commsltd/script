package com.commercialoutcomes.uktelevision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

/** Providers supply their catalogue on this device's route, never the build server's country. */
enum class VodProvider(val label: String, val home: String, val domain: String) {
    PLEX("Plex", "https://watch.plex.tv/en-GB/on-demand", "watch.plex.tv"),
    PLUTO("Pluto TV", "https://pluto.tv/", "pluto.tv"),
    TUBI("Tubi", "https://tubitv.com/", "tubitv.com"),
    MOVY("Movy", "https://www.movy.sx/", "www.movy.sx"),
    DULO("Dulo", "https://dulo.mov/movies/", "dulo.mov");
    fun owns(url: String): Boolean = runCatching { val u=URL(url); u.protocol=="https" && u.host==domain && u.userInfo==null }.getOrDefault(false)
}
data class VodSource(val provider: VodProvider, val url: String)
data class VodTitle(val title: String, val year: Int?, val kind: String, val poster: String,
    val description: String = "", val sources: List<VodSource>, val genres: List<String> = emptyList()) {
    val key: String = if (year != null) "$kind:$year:${normalTitle(title)}" else sources.first().url
    val subtitle: String get() = listOfNotNull(year?.toString(), if(kind=="show") "TV series" else "Film", sources.joinToString(" · ") { it.provider.label }).joinToString("  ·  ")
}
data class VodResult(val titles: List<VodTitle>, val statuses: Map<VodProvider,String>, val nextPages: Map<VodProvider,List<String>> = emptyMap())
private val titlePunctuation = Regex("[^\\p{L}\\p{N}]+")
fun normalTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT)
    .replace(titlePunctuation," ").trim()

object VodCatalogue {
    fun merge(rows: List<VodTitle>): List<VodTitle> = rows.groupBy { it.key }.values.map { matches ->
        matches.first().copy(sources=matches.flatMap { it.sources }.distinctBy { it.url },
            poster=matches.firstOrNull { it.poster.isNotBlank() }?.poster.orEmpty(),
            description=matches.firstOrNull { it.description.isNotBlank() }?.description.orEmpty(),
            genres=matches.flatMap { it.genres }.distinct().sorted())
    }
    fun collections(provider: VodProvider, doc: Document): List<String> = doc.select("a[href]").map { it.absUrl("href") }.filter { url ->
        provider.owns(url) && when(provider) {
            VodProvider.PLEX -> URL(url).path.contains("/on-demand/category/") || URL(url).path.contains("/on-demand/list/")
            VodProvider.PLUTO -> Regex("^/[^/]+/(movies|shows)/([^/]+/)?$").matches(URL(url).path)
            VodProvider.TUBI -> URL(url).path.startsWith("/category/")
            VodProvider.MOVY -> URL(url).path in setOf("/browse/movie","/browse/tv")
            VodProvider.DULO -> URL(url).path.trimEnd('/') in setOf("/movies","/shows","/genres") || URL(url).path.startsWith("/genre/")
        }
    }.filter { it!=doc.location() }.distinct().take(60)
    suspend fun load(): VodResult = supervisorScope {
        val results=VodProvider.entries.map { provider -> async(Dispatchers.IO) {
            provider to runCatching { val doc=fetch(provider.home); parse(provider,doc) to collections(provider,doc) }
        } }.map { it.await() }
        val statuses=results.associate { (p,r) -> p to r.fold(
            { if(it.first.isEmpty()) "No catalogue returned on this connection" else "${it.first.size} titles loaded" },
            { it.message?.take(120) ?: "Could not connect" }) }
        VodResult(merge(results.flatMap { it.second.getOrNull()?.first.orEmpty() }),statuses,
            results.associate { it.first to it.second.getOrNull()?.second.orEmpty() })
    }
    suspend fun more(previous: VodResult): VodResult = supervisorScope {
        val results=previous.nextPages.map { (provider,urls) -> async(Dispatchers.IO) {
            val titles=urls.take(2).flatMap { url -> runCatching { parse(provider,fetch(url)) }.getOrDefault(emptyList()) }
            Triple(provider,titles,urls.drop(2))
        } }.map { it.await() }
        val merged=merge(previous.titles+results.flatMap { it.second })
        VodResult(merged,previous.statuses.mapValues { (p,old) ->
            val count=merged.count { t->t.sources.any { it.provider==p } };if(count>0)"$count titles loaded" else old },
            results.associate { it.first to it.third })
    }
    suspend fun forGenre(previous: VodResult, genre: String): VodResult = supervisorScope {
        // Use only genre collections already linked by the provider itself.
        val selected=previous.nextPages.mapValues { (_,urls) -> urls.filter { url ->
            VodGenres.normalise(URL(url).path.trimEnd('/').substringAfterLast('/').replace('-', ' ')).any { it.equals(genre,true) } ||
                (genre=="Sci-Fi" && URL(url).path.trimEnd('/').endsWith("/sci-fi"))
        }.take(2) }
        val additions=selected.map { (provider,urls) -> async(Dispatchers.IO) { urls.flatMap { runCatching { parse(provider,fetch(it)) }.getOrDefault(emptyList()) } } }.flatMap { it.await() }
        val merged=merge(previous.titles+additions)
        previous.copy(titles=merged,statuses=previous.statuses.mapValues { (p,old) ->
            val count=merged.count { title->title.sources.any { it.provider==p } };if(count>0)"$count titles loaded" else old
        })
    }
    fun fetch(url: String): Document {
        val connection=URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout=10000; connection.readTimeout=15000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.setRequestProperty("Accept-Language","en-GB,en;q=0.9")
        try {
            require(connection.responseCode==200) { "Provider returned HTTP ${connection.responseCode}" }
            val data=connection.inputStream.use { input ->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) { val n=input.read(buffer);if(n<0)break;require(output.size()+n<=6*1024*1024) { "Catalogue too large" };output.write(buffer,0,n) }
                output.toString("UTF-8")
            }
            return Jsoup.parse(data,connection.url.toString())
        } finally { connection.disconnect() }
    }
    fun parse(provider: VodProvider, doc: Document): List<VodTitle> {
        if(provider==VodProvider.MOVY)return parseMovy(doc)
        if(provider==VodProvider.DULO)return parseDulo(doc)
        val rows=mutableListOf<VodTitle>()
        val plutoMetadata=mutableMapOf<String,JSONObject>()
        if(provider==VodProvider.PLUTO) {
            fun walk(o: Any?) { when(o) {
                is JSONObject -> { if(o.has("contentType") && o.has("id"))plutoMetadata[o.optString("id")]=o; o.keys().forEach { walk(o.opt(it)) } }
                is JSONArray -> (0 until o.length()).forEach { walk(o.opt(it)) }
            } }
            runCatching { walk(JSONObject(doc.selectFirst("script#__NEXT_DATA__")?.data().orEmpty())) }
        }
        for(a in doc.select("a[href]")) {
            val url=a.absUrl("href")
            if(!provider.owns(url))continue
            val path=URL(url).path
            val kind=when(provider) {
                VodProvider.PLEX -> when { path.contains("/movie/") -> "movie";path.contains("/show/")->"show";else->null }
                VodProvider.TUBI -> when { Regex("^/movies/\\d+/").containsMatchIn(path)->"movie";Regex("^/series/\\d+/").containsMatchIn(path)->"show";else->null }
                VodProvider.PLUTO -> when { Regex("/movies/[^/]+/?$").containsMatchIn(path)->"movie";Regex("/(series|shows)/[^/]+/?$").containsMatchIn(path)->"show";else->null }
                VodProvider.MOVY, VodProvider.DULO -> null
            } ?: continue
            val card=if(provider==VodProvider.TUBI) a.parents().firstOrNull { it.hasClass("web-content-tile") } ?: a else if(provider==VodProvider.PLEX) a.parent() ?: a else a
            val img=card.selectFirst("img")
            val label=a.attr("aria-label").ifBlank { img?.attr("alt").orEmpty() }.ifBlank { a.text() }
            if(label.isBlank())continue
            val yearSuffix=Regex("\\s*\\((\\d{4})\\)$").find(label)
            val metadata=plutoMetadata[path.trimEnd('/').substringAfterLast('/')]
            val year=yearSuffix?.groupValues?.get(1)?.toIntOrNull()
                ?: card.selectFirst(".web-content-tile__year")?.text()?.toIntOrNull()
                ?: metadata?.optString("airDate")?.take(4)?.toIntOrNull()
            val title=yearSuffix?.let { label.substring(0,it.range.first) } ?: label
            // Plex links from the discovery database are excluded: this page must identify a free VOD item.
            if(provider==VodProvider.PLEX && !a.attr("data-id").startsWith("tv.plex.provider.vod-"))continue
            val poster=img?.absUrl("src").orEmpty().takeIf { it.startsWith("https://") }.orEmpty()
            rows.add(VodTitle(title,year?.takeIf { it in 1880..2100 },kind,poster,
                metadata?.optString("description").orEmpty(),listOf(VodSource(provider,url)),
                when(provider) {
                    VodProvider.PLUTO -> VodGenres.normalise(metadata?.optString("genre").orEmpty())
                    VodProvider.TUBI -> VodGenres.normalise(card.selectFirst(".web-content-tile__tags")?.text().orEmpty())
                    VodProvider.PLEX -> VodGenres.category(doc)
                    else -> emptyList()
                }))
        }
        if(rows.isEmpty() && SourceAdvice.geoMessage(doc.text()))throw IllegalStateException("Provider reports a location restriction. The required country was not supplied.")
        return merge(rows)
    }
    private fun parseMovy(doc: Document): List<VodTitle> {
        val rows=mutableListOf<VodTitle>()
        val json=JSONObject(doc.selectFirst("script#__NEXT_DATA__")?.data().orEmpty()).getJSONObject("props").getJSONObject("pageProps")
        fun walk(value:Any?) { when(value) {
            is JSONObject -> {
                val slug=value.optString("slug")
                val title=value.optString("title")
                if(Regex("^/(movie|tv)/\\d+$").matches(slug) && title.isNotBlank()) {
                    val kind=if(slug.startsWith("/tv/"))"show" else "movie"
                    val year=value.optString("release_date").take(4).toIntOrNull()
                    rows.add(VodTitle(title,year,kind,value.optString("poster").takeIf { it.startsWith("https://") }.orEmpty(),
                        value.optString("description").takeUnless { it=="null" }.orEmpty(),listOf(VodSource(VodProvider.MOVY,URL(URL(doc.location()),slug).toString())),VodGenres.ids(value.optJSONArray("genre_ids"))))
                }
                value.keys().forEach { if(it!="messages")walk(value.opt(it)) }
            }
            is JSONArray -> (0 until value.length()).forEach { walk(value.opt(it)) }
        } }
        walk(json)
        return merge(rows)
    }
    private fun parseDulo(doc: Document): List<VodTitle> {
        val rows=mutableListOf<VodTitle>()
        // Detail metadata is also used to verify cross-provider alternatives.
        val canonical=doc.selectFirst("link[rel=canonical]")?.absUrl("href") ?: doc.location()
        val detail=Regex("^/title/(movie|tv)/(\\d+)/?$").matchEntire(runCatching { URL(canonical).path }.getOrDefault(""))
        if(detail!=null && VodProvider.DULO.owns(canonical))for(script in doc.select("script[type=application/ld+json]"))runCatching {
            val j=JSONObject(script.data())
            if(j.optString("@type") in setOf("Movie","TVSeries")) {
                val genres=j.optJSONArray("genre")
                rows.add(VodTitle(j.getString("name"),j.optString("datePublished").take(4).toIntOrNull(),if(detail.groupValues[1]=="tv")"show" else "movie",
                    j.optString("image"),j.optString("description"),listOf(VodSource(VodProvider.DULO,canonical)),
                    if(genres==null)emptyList() else (0 until genres.length()).flatMap { VodGenres.normalise(genres.optString(it)) }))
            }
        }
        for(a in doc.select("a[href][aria-label^=View details for]")) {
            val url=a.absUrl("href")
            if(!VodProvider.DULO.owns(url))continue
            val match=Regex("^/title/(movie|tv)/(\\d+)/?$").matchEntire(URL(url).path) ?: continue
            val card=a.parent() ?: continue
            val year=card.select("span").map { it.ownText() }.firstOrNull { Regex("(19|20)\\d{2}").matches(it) }?.toIntOrNull()
            val name=a.attr("aria-label").removePrefix("View details for ").trim()
            if(name.isBlank())continue
            rows.add(VodTitle(name,year,if(match.groupValues[1]=="tv")"show" else "movie",card.selectFirst("img")?.absUrl("src").orEmpty(),
                card.selectFirst("p.line-clamp-3")?.text().orEmpty(),listOf(VodSource(VodProvider.DULO,url))))
        }
        return merge(rows)
    }
    /** Both providers publish TMDB identities. Verify the target page before
     * offering it; never guess matches from a title alone or merge remakes. */
    suspend fun alternatives(title: VodTitle): VodTitle = withContext(Dispatchers.IO) {
        val identity=title.sources.firstNotNullOfOrNull { source ->
            val pattern=when(source.provider) {
                VodProvider.MOVY -> Regex("^/(movie|tv)/(\\d+)/?$")
                VodProvider.DULO -> Regex("^/title/(movie|tv)/(\\d+)/?$")
                else -> null
            }
            pattern?.matchEntire(URL(source.url).path)?.groupValues?.drop(1)
        } ?: return@withContext title
        val candidates=listOf(VodSource(VodProvider.MOVY,"https://www.movy.sx/${identity[0]}/${identity[1]}"),
            VodSource(VodProvider.DULO,"https://dulo.mov/title/${identity[0]}/${identity[1]}/"))
        var result=title
        for(candidate in candidates)if(result.sources.none { it.provider==candidate.provider })runCatching {
            val found=parse(candidate.provider,fetch(candidate.url)).firstOrNull { row ->
                row.sources.any { URL(it.url).path.trimEnd('/')==URL(candidate.url).path.trimEnd('/') } &&
                    normalTitle(row.title)==normalTitle(title.title) && row.kind==title.kind &&
                    (title.year==null || row.year==title.year)
            }
            if(found!=null)result=result.copy(sources=result.sources+candidate,genres=(result.genres+found.genres).distinct())
        }
        result
    }
    suspend fun playbackPage(source: VodSource): String = withContext(Dispatchers.IO) {
        require(source.provider.owns(source.url))
        if(source.provider==VodProvider.MOVY)return@withContext source.url.substringBefore('?')+"?play=true"
        if(source.provider==VodProvider.DULO) {
            if(URL(source.url).path.startsWith("/tv/"))return@withContext source.url
            if(URL(source.url).path.startsWith("/watch/") || URL(source.url).path.startsWith("/title/tv/"))return@withContext source.url.replace("https://dulo.mov/","https://dulo.mov/tv/")
            val doc=fetch(source.url)
            val watch=doc.select("a[href]").map { it.absUrl("href") }.firstOrNull {
                source.provider.owns(it) && URL(it).path.startsWith("/watch/")
            }
            require(watch!=null) { "Dulo did not supply a playback link for this title." }
            return@withContext watch.replace("https://dulo.mov/watch/","https://dulo.mov/tv/watch/")
        }
        if(source.provider!=VodProvider.PLEX)return@withContext source.url
        val doc=fetch(source.url)
        val watch=doc.selectFirst("a[data-id=watch]")?.absUrl("href").orEmpty()
        require(source.provider.owns(watch) && URL(watch).path.contains("/watch/")) { "This title has no free Plex playback link on your current connection." }
        watch
    }
}
