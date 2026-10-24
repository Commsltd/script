package com.commercialoutcomes.uktelevision

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VodDiscoveryTest {
    @Test fun searchRetainsPageAndSeriesIdentityWithoutPreloading(){
        val page=VodDiscovery.parse(JSONObject("""{"page":2,"total_pages":4,"results":[{"id":1396,"name":"Breaking Bad","media_type":"tv","first_air_date":"2008-01-20","genre_ids":[18],"poster_path":"/poster.jpg"},{"id":9,"name":"Someone","media_type":"person"},{"id":10,"title":"Excluded","media_type":"movie","adult":true}]}"""))
        assertEquals(2,page.page);assertEquals(4,page.pages);assertEquals(1,page.titles.size)
        val show=page.titles.single();assertEquals("show",show.kind);assertEquals(2008,show.year)
        assertEquals("https://dulo.mov/title/tv/1396/",show.sources.last().url)
    }
    @Test fun selectedSeasonProducesExactEpisodePlaybackLinks(){
        val data=JSONObject("""{"episodes":[{"episode_number":1,"season_number":2,"name":"Seven Thirty-Seven"},{"episode_number":2,"season_number":2,"name":"Grilled"},{"episode_number":1,"season_number":1,"name":"Wrong season"}]}""")
        val episodes=VodDiscovery.parseDuloEpisodes("1396","Breaking Bad",2,data)
        assertEquals(2,episodes.size)
        assertEquals("https://dulo.mov/watch/tv/1396?s=2&e=2",episodes[1].sources.single().url)
        assertTrue(episodes[1].title.contains("S2 E2"))
    }
    @Test fun titleRemakesAreNotMerged(){
        val page=VodDiscovery.parse(JSONObject("""{"results":[{"id":1,"media_type":"movie","title":"The Thing","release_date":"1982-01-01"},{"id":2,"media_type":"movie","title":"The Thing","release_date":"2011-01-01"}]}"""))
        assertEquals(2,page.titles.size)
    }
}
