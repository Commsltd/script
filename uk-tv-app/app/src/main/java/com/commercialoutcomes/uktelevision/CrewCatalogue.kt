package com.commercialoutcomes.uktelevision

import org.json.JSONObject

/** Only titles returned by The Crew's own directory are labelled as Crew titles. */
object CrewCatalogue {
    data class Folder(val id:String,val label:String)
    data class Page(val titles:List<VodTitle>,val folders:List<Folder>,val skipped:Int)
    fun parse(data:JSONObject):Page {
        val rows=data.optJSONArray("titles")
        val titles=(0 until (rows?.length()?:0)).mapNotNull { i ->
            val row=rows!!.optJSONObject(i)?:return@mapNotNull null
            val url=row.optString("url");val name=row.optString("title");val kind=row.optString("kind")
            if(!VodProvider.CREW.owns(url)||name.isBlank()||kind !in setOf("movie","show"))return@mapNotNull null
            if((kind=="show")!=url.startsWith("crew://tv/"))return@mapNotNull null
            val genres=row.optJSONArray("genres")
            VodTitle(name,row.optInt("year").takeIf { it in 1880..2200 },kind,
                row.optString("poster").takeIf { it.startsWith("https://") }.orEmpty(),row.optString("description"),
                listOf(VodSource(VodProvider.CREW,url)),(0 until (genres?.length()?:0)).flatMap { VodGenres.normalise(genres!!.optString(it)) }.distinct())
        }
        val folders=data.optJSONArray("folders")
        return Page(VodCatalogue.merge(titles),(0 until (folders?.length()?:0)).mapNotNull { i ->
            val f=folders!!.optJSONObject(i)?:return@mapNotNull null
            val id=f.optString("id");val label=f.optString("label")
            if(Regex("[a-f0-9]{32}").matches(id)&&label.isNotBlank())Folder(id,label) else null
        },data.optInt("skipped"))
    }
}
