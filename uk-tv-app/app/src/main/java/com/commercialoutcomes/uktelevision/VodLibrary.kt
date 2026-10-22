package com.commercialoutcomes.uktelevision

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class VodSort(val label: String) { FEATURED("Featured"), TITLE_ASC("Title A–Z"), TITLE_DESC("Title Z–A"), NEWEST("Newest first"), OLDEST("Oldest first") }
data class VodBrowse(val kind: String = "", val provider: VodProvider? = null, val genre: String = "", val fromYear: Int? = null, val toYear: Int? = null, val unknownYear: Boolean = false, val sort: VodSort = VodSort.FEATURED, val query: String = "") {
    fun apply(titles: List<VodTitle>): List<VodTitle> {
        val rows=titles.filter { t -> (kind.isBlank() || t.kind==kind) && (provider==null || t.sources.any { it.provider==provider }) &&
            (genre.isBlank() || if(genre=="Unknown genre")t.genres.isEmpty() else genre in t.genres) &&
            (if(unknownYear)t.year==null else (fromYear==null || t.year?.let { it>=fromYear }==true) && (toYear==null || t.year?.let { it<=toYear }==true)) &&
            (query.isBlank() || normalTitle(t.title).contains(normalTitle(query))) }
        val name=compareBy<VodTitle> { normalTitle(it.title) }.thenBy { it.key }
        return when(sort) {
            VodSort.FEATURED -> rows
            VodSort.TITLE_ASC -> rows.sortedWith(name)
            VodSort.TITLE_DESC -> rows.sortedWith(compareByDescending<VodTitle> { normalTitle(it.title) }.thenBy { it.key })
            VodSort.NEWEST -> rows.sortedWith(compareBy<VodTitle> { it.year==null }.thenByDescending { it.year }.then(name))
            VodSort.OLDEST -> rows.sortedWith(compareBy<VodTitle> { it.year==null }.thenBy { it.year }.then(name))
        }
    }
    val yearLabel get()=when { unknownYear->"Unknown year";fromYear==null->"All years";fromYear==toYear->fromYear.toString();else->"${fromYear}s" }
}
data class VodList(val id: String, val name: String, val keys: Set<String> = emptySet())
data class VodSaved(val titles: List<VodTitle> = emptyList(), val favourites: Set<String> = emptySet(), val lists: List<VodList> = emptyList()) {
    private fun matches(a: VodTitle,b: VodTitle)=a.key==b.key || a.sources.any { x -> b.sources.any { it.url==x.url } }
    fun available(current: List<VodTitle>): List<VodTitle> {
        val refreshed=current.map { fresh ->
            val old=titles.filter { matches(it,fresh) }
            fresh.copy(sources=(fresh.sources+old.flatMap { it.sources }).distinctBy { it.url },genres=(fresh.genres+old.flatMap { it.genres }).distinct())
        }
        return VodCatalogue.merge(refreshed+titles.filter { old -> current.none { matches(old,it) } })
    }
    fun keyFor(title: VodTitle): String = titles.firstOrNull { saved -> saved.key==title.key || saved.sources.any { old -> title.sources.any { it.url==old.url } } }?.key ?: title.key
    fun isFavourite(title: VodTitle) = keyFor(title) in favourites
    fun remember(title: VodTitle): VodSaved {
        val key=keyFor(title)
        val old=titles.firstOrNull { it.key==key }
        val saved=if(old==null)title else old.copy(sources=(old.sources+title.sources).distinctBy { it.url },genres=(old.genres+title.genres).distinct())
        return copy(titles=titles.filterNot { it.key==key }+saved)
    }
    fun toggleFavourite(title: VodTitle): VodSaved {
        val next=remember(title);val key=next.keyFor(title)
        return next.copy(favourites=if(key in next.favourites)next.favourites-key else next.favourites+key).pruned()
    }
    fun createList(name: String): VodSaved {
        val clean=name.trim();require(clean.isNotBlank() && clean.length<=60) { "Use a list name of 1–60 characters." }
        require(lists.none { it.name.equals(clean,true) }) { "A list with that name already exists." }
        return copy(lists=lists+VodList(UUID.randomUUID().toString(),clean))
    }
    fun renameList(id: String,name: String): VodSaved {
        val clean=name.trim();require(clean.isNotBlank() && clean.length<=60) { "Use a list name of 1–60 characters." }
        require(lists.none { it.id!=id && it.name.equals(clean,true) }) { "A list with that name already exists." }
        return copy(lists=lists.map { if(it.id==id)it.copy(name=clean) else it })
    }
    fun deleteList(id: String)=copy(lists=lists.filterNot { it.id==id }).pruned()
    fun setLists(title: VodTitle, ids: Set<String>): VodSaved {
        val next=remember(title);val key=next.keyFor(title)
        return next.copy(lists=next.lists.map { it.copy(keys=if(it.id in ids)it.keys+key else it.keys-key) }).pruned()
    }
    private fun pruned(): VodSaved { val used=favourites+lists.flatMap { it.keys };return copy(titles=titles.filter { it.key in used }) }
}
object VodSavedCodec {
    fun encode(saved: VodSaved): String = JSONObject().put("version",1).put("favourites",JSONArray(saved.favourites.toList()))
        .put("lists",JSONArray(saved.lists.map { JSONObject().put("id",it.id).put("name",it.name).put("keys",JSONArray(it.keys.toList())) }))
        .put("titles",JSONArray(saved.titles.map { t -> JSONObject().put("title",t.title).put("year",t.year).put("kind",t.kind).put("poster",t.poster).put("description",t.description)
            .put("genres",JSONArray(t.genres)).put("sources",JSONArray(t.sources.map { JSONObject().put("provider",it.provider.name).put("url",it.url) })) })).toString()
    private fun strings(a: JSONArray?): List<String> = if(a==null)emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    fun decode(raw: String): VodSaved = runCatching {
        val root=JSONObject(raw);val rows=root.optJSONArray("titles") ?: JSONArray()
        val titles=(0 until rows.length()).mapNotNull { i -> runCatching {
            val t=rows.getJSONObject(i);val a=t.getJSONArray("sources")
            val sources=(0 until a.length()).mapNotNull { n -> runCatching { val s=a.getJSONObject(n);val p=VodProvider.valueOf(s.getString("provider"));val url=s.getString("url");if(p.owns(url))VodSource(p,url) else null }.getOrNull() }
            require(sources.isNotEmpty())
            VodTitle(t.getString("title"),t.optInt("year").takeIf { it in 1880..2100 },t.getString("kind"),t.optString("poster"),t.optString("description"),sources,strings(t.optJSONArray("genres")))
        }.getOrNull() }
        val keys=titles.map { it.key }.toSet();val lists=root.optJSONArray("lists") ?: JSONArray()
        VodSaved(titles,strings(root.optJSONArray("favourites")).toSet().intersect(keys),(0 until lists.length()).mapNotNull { i -> runCatching {
            val l=lists.getJSONObject(i);VodList(l.getString("id"),l.getString("name"),strings(l.optJSONArray("keys")).toSet().intersect(keys))
        }.getOrNull() })
    }.getOrDefault(VodSaved())
}
object VodGenres {
    private val tmdb=mapOf(28 to "Action",12 to "Adventure",16 to "Animation",35 to "Comedy",80 to "Crime",99 to "Documentary",18 to "Drama",10751 to "Family",14 to "Fantasy",36 to "History",27 to "Horror",10402 to "Music",9648 to "Mystery",10749 to "Romance",878 to "Sci-Fi",10770 to "TV movie",53 to "Thriller",10752 to "War",37 to "Western",10759 to "Action & Adventure",10762 to "Kids",10763 to "News",10764 to "Reality",10765 to "Sci-Fi & Fantasy",10766 to "Soap",10767 to "Talk",10768 to "War & Politics")
    fun ids(a: JSONArray?): List<String> = if(a==null)emptyList() else (0 until a.length()).mapNotNull { tmdb[a.optInt(it)] }.flatMap(::normalise).distinct()
    fun normalise(text: String): List<String> = text.split('·',',','|').flatMap { raw ->
        when(val name=raw.trim()) {
            "Action & Adventure"->listOf("Action","Adventure")
            "Sci-Fi & Fantasy"->listOf("Sci-Fi","Fantasy")
            "Kids & Family","Children & Family","Family & Kids"->listOf("Kids","Family")
            "Science Fiction","Science-Fiction","Sci Fi"->listOf("Sci-Fi")
            "Documentaries"->listOf("Documentary")
            "","null"->emptyList()
            else->listOf(name)
        }
    }.distinct()
    fun category(doc: org.jsoup.nodes.Document): List<String> {
        val path=runCatching { java.net.URL(doc.location()).path }.getOrDefault("")
        val match=Regex("/on-demand/category/(action|animation|comedy|crime|documentary|drama|horror|music|romance|sci-fi|thriller|western)/?$").find(path) ?: return emptyList()
        val slug=match.groupValues[1];return listOf(if(slug=="sci-fi")"Sci-Fi" else slug.replaceFirstChar { it.uppercase() })
    }
}
