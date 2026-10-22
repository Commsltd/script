package com.commercialoutcomes.uktelevision

import org.json.JSONArray
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class VodLibraryTest {
    private fun movie(name:String,year:Int?,genres:List<String> = listOf("Drama"),provider:VodProvider = VodProvider.MOVY,kind:String="movie")=
        VodTitle(name,year,kind,"https://example.com/poster.jpg",sources=listOf(VodSource(provider,provider.home.trimEnd('/')+"/movie/"+name.replace(" ","-"))),genres=genres)
    @Test fun filtersIntersectWithoutReplacingTheSelectedProviderOrFormat() {
        val rows=listOf(movie("A",1994),movie("B",2004),movie("C",1994,provider=VodProvider.PLEX),movie("D",1994,listOf("Comedy")),movie("E",1994,kind="show"))
        assertEquals(listOf("A"),VodBrowse(kind="movie",provider=VodProvider.MOVY,genre="Drama",fromYear=1990,toYear=1999).apply(rows).map { it.title })
        assertEquals(listOf("B"),VodBrowse(fromYear=2004,toYear=2004).apply(rows).map { it.title })
    }
    @Test fun unknownMetadataIsKeptInAllAndSortedAfterKnownYears() {
        val rows=listOf(movie("Z",null,emptyList()),movie("B",2020),movie("A",1994))
        assertEquals(3,VodBrowse().apply(rows).size)
        assertEquals(listOf("B","A","Z"),VodBrowse(sort=VodSort.NEWEST).apply(rows).map { it.title })
        assertEquals(listOf("A","B","Z"),VodBrowse(sort=VodSort.OLDEST).apply(rows).map { it.title })
        assertEquals(listOf("Z"),VodBrowse(genre="Unknown genre",unknownYear=true).apply(rows).map { it.title })
        assertEquals(listOf("Z","B","A"),VodBrowse(sort=VodSort.TITLE_DESC).apply(rows).map { it.title })
    }
    @Test fun favouritesAndListMembershipSurviveReloadWithoutLiveCatalogue() {
        val film=movie("Saved film",1994)
        var saved=VodSaved().toggleFavourite(film).createList("Weekend").createList("Family")
        val weekend=saved.lists.first().id;val family=saved.lists.last().id
        saved=saved.setLists(film,setOf(weekend,family))
        val reopened=VodSavedCodec.decode(VodSavedCodec.encode(saved))
        assertTrue(reopened.isFavourite(film));assertEquals(listOf(film),reopened.titles)
        assertTrue(reopened.lists.all { film.key in it.keys })
        val removed=reopened.setLists(film,setOf(family)).toggleFavourite(film).deleteList(weekend)
        assertFalse(removed.isFavourite(film));assertEquals(listOf(film),removed.titles)
        assertTrue(removed.deleteList(family).titles.isEmpty())
    }
    @Test fun refreshedCardsKeepSavedAlternativesAndDoNotDuplicateChangedMetadata() {
        val old=movie("Saved",null).copy(sources=listOf(VodSource(VodProvider.MOVY,"https://www.movy.sx/movie/278"),VodSource(VodProvider.PLEX,"https://watch.plex.tv/movie/saved")))
        val saved=VodSaved().toggleFavourite(old)
        val fresh=old.copy(year=1994,sources=old.sources.take(1))
        val available=saved.available(listOf(fresh))
        assertEquals(1,available.size);assertEquals(1994,available.single().year)
        assertEquals(2,available.single().sources.size);assertTrue(saved.isFavourite(available.single()))
    }
    @Test fun renamingKeepsMembershipAndRejectsDuplicateNames() {
        val film=movie("A",2000);var saved=VodSaved().createList("Weekend").createList("Family")
        val id=saved.lists.first().id;saved=saved.setLists(film,setOf(id)).renameList(id,"Friday night")
        assertEquals(setOf(film.key),saved.lists.first().keys)
        assertTrue(runCatching { saved.createList(" family ") }.isFailure)
        assertTrue(runCatching { saved.renameList(id,"Family") }.isFailure)
        assertTrue(runCatching { saved.createList("  ") }.isFailure)
    }
    @Test fun changedMetadataForSameProviderUrlDoesNotOrphanASavedTitle() {
        val old=movie("A",null);val updated=old.copy(year=1994,genres=listOf("Drama","Crime"))
        val saved=VodSaved().toggleFavourite(old).remember(updated)
        assertTrue(saved.isFavourite(updated));assertEquals(1,saved.titles.size)
        assertEquals(listOf("Drama","Crime"),saved.titles.single().genres)
    }
    @Test fun genreMetadataIsCombinedAcrossProvidersWithoutGuessingMissingGenres() {
        val a=movie("Example",2000,listOf("Drama"));val b=movie("Example",2000,listOf("Crime"),VodProvider.PLEX)
        assertEquals(listOf("Crime","Drama"),VodCatalogue.merge(listOf(a,b)).single().genres)
        assertEquals(listOf("Sci-Fi","Action"),VodGenres.ids(JSONArray("[878,28,99999]")))
        val tubi=Jsoup.parse("""<div class="web-content-tile"><a href="/movies/123/example"><img alt="Example"></a><div class="web-content-tile__tags">Kids &amp; Family · Animation</div></div>""",VodProvider.TUBI.home)
        assertEquals(listOf("Animation","Family","Kids"),VodCatalogue.parse(VodProvider.TUBI,tubi).single().genres)
        val plain=Jsoup.parse("""<a data-id="tv.plex.provider.vod-1" aria-label="Example (2000)" href="/movie/example"></a>""",VodProvider.PLEX.home)
        assertTrue(VodCatalogue.parse(VodProvider.PLEX,plain).single().genres.isEmpty())
    }
}
