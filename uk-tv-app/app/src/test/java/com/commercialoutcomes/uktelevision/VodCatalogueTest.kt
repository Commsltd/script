package com.commercialoutcomes.uktelevision

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class VodCatalogueTest {
    private fun title(year:Int?,p:VodProvider,kind:String="movie",name:String="Example")=VodTitle(name,year,kind,"",sources=listOf(VodSource(p,p.home+"/example")))
    @Test fun alternativesMergeWithoutMixingRemakesOrSeries() {
        val rows=VodCatalogue.merge(listOf(title(1990,VodProvider.PLEX),title(1990,VodProvider.TUBI),title(2020,VodProvider.PLUTO),title(1990,VodProvider.PLEX,"show")))
        assertEquals(3,rows.size);assertEquals(2,rows.first().sources.size)
        assertEquals(2,VodCatalogue.merge(listOf(title(null,VodProvider.PLEX),title(null,VodProvider.TUBI))).size)
    }
    @Test fun repeatedPagesDoNotDuplicateSourceChoices() {
        val t=title(2000,VodProvider.PLEX)
        assertEquals(listOf(t),VodCatalogue.merge(listOf(t,t,t)))
    }
    @Test fun plexOnlyIncludesFreeVodLinksAndPreservesPosterAndYear() {
        val doc=Jsoup.parse("""<div><a data-id="tv.plex.provider.vod-123" aria-label="A Film (2007)" href="/en-GB/movie/a-film"></a><img src="https://images.plex.tv/example.jpg"></div>
            <a aria-label="Paid Film (2008)" href="/en-GB/movie/paid">Paid</a>""",VodProvider.PLEX.home)
        val rows=VodCatalogue.parse(VodProvider.PLEX,doc)
        assertEquals(1,rows.size);assertEquals("A Film",rows[0].title);assertEquals(2007,rows[0].year);assertEquals("https://images.plex.tv/example.jpg",rows[0].poster)
    }
    @Test fun tubiMetadataAndProtocolRelativeArtworkAreRetained() {
        val doc=Jsoup.parse("""<div class="web-content-tile"><a href="/movies/123/a-film"><img alt="A Film" src="//canvas.tubitv.com/poster.jpg"></a><div class="web-content-tile__year">2007</div></div>""",VodProvider.TUBI.home)
        val row=VodCatalogue.parse(VodProvider.TUBI,doc).single()
        assertEquals(2007,row.year);assertTrue(row.poster.startsWith("https://"))
    }
    @Test fun providerLinksCannotOpenExternalAppsOrUnrelatedSites() {
        assertFalse(VodProvider.PLEX.owns("intent://movie#Intent;end"))
        assertFalse(VodProvider.PLEX.owns("https://watch.plex.tv.bad.example/movie"))
        assertFalse(VodProvider.PLEX.owns("https://user:secret@watch.plex.tv/movie"))
        assertTrue(VodProvider.PLEX.owns("https://watch.plex.tv/en-GB/watch/movie/example"))
    }
    @Test fun episodeListKeepsOnlyTheSelectedSeries() {
        val source=VodSource(VodProvider.PLEX,"https://watch.plex.tv/en-GB/show/example/season/1")
        val doc=Jsoup.parse("""<a href="/en-GB/show/example/season/1/episode/1" aria-label="S1 E1 · Pilot"></a>
            <a href="/en-GB/show/another/season/1/episode/1">Wrong series</a>""",source.url)
        val rows=VodEpisodes.parse(source,doc)
        assertEquals(1,rows.size);assertEquals("episode",rows.single().kind)
    }
    @Test fun movyCatalogueIgnoresUnrelatedLinksAndRepeatedCards() {
        val doc=Jsoup.parse("""<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"rows":[
            {"slug":"/movie/278","title":"Example","release_date":"1994-09-23","poster":"https://images.example/poster.jpg"},
            {"slug":"/movie/278","title":"Example","release_date":"1994-09-23"},
            {"slug":"https://bad.example/tv/1","title":"Wrong"},
            {"slug":"/tv/1396","title":"Series","release_date":"2008-01-20"}]}}}</script>""",VodProvider.MOVY.home)
        val rows=VodCatalogue.parse(VodProvider.MOVY,doc)
        assertEquals(2,rows.size);assertEquals(1994,rows[0].year);assertEquals("show",rows[1].kind)
        assertTrue(rows.all { VodProvider.MOVY.owns(it.sources.single().url) })
    }
    @Test fun movySeasonsKeepTheSelectedSeriesAndPublishedEpisodeCount() {
        val doc=Jsoup.parse("""<script id="__NEXT_DATA__">{"props":{"pageProps":{"details":{"tmdbId":1396,"title":"Example","seasons":[{"season_number":1,"name":"Season 1","episode_count":7},{"season_number":2,"name":"Season 2","episode_count":13}]}}}}</script>""",VodProvider.MOVY.home)
        assertEquals(2,VodEpisodes.parse(VodSource(VodProvider.MOVY,"https://www.movy.sx/tv/1396"),doc).size)
        val episodes=VodEpisodes.parse(VodSource(VodProvider.MOVY,"https://www.movy.sx/tv/1396/2"),doc)
        assertEquals(13,episodes.size);assertTrue(episodes.all { it.sources.single().url.startsWith("https://www.movy.sx/tv/1396/2/") })
        assertTrue(VodEpisodes.parse(VodSource(VodProvider.MOVY,"https://www.movy.sx/tv/1396/9"),doc).isEmpty())
    }
}
