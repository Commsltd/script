package com.commercialoutcomes.uktelevision

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CrewCatalogueTest {
    @Test fun crewTitlesSurviveSavedLibraryAndMergeWithOtherProviders() {
        val page=CrewCatalogue.parse(JSONObject("""{"titles":[{"title":"Example","kind":"movie","year":1994,"url":"crew://movie/278","genres":["Drama"]}],"folders":[{"id":"0123456789abcdef0123456789abcdef","label":"Next page"}]}"""))
        val title=page.titles.single()
        val saved=VodSaved().toggleFavourite(title).createList("Weekend")
        val reopened=VodSavedCodec.decode(VodSavedCodec.encode(saved.setLists(title,setOf(saved.lists.single().id))))
        assertTrue(reopened.isFavourite(title));assertEquals(title,reopened.titles.single())
        val web=title.copy(sources=listOf(VodSource(VodProvider.MOVY,"https://www.movy.sx/movie/278")))
        assertEquals(setOf(VodProvider.CREW,VodProvider.MOVY),VodCatalogue.merge(listOf(title,web)).single().sources.map { it.provider }.toSet())
        assertEquals("Next page",page.folders.single().label)
    }
    @Test fun malformedCrewIdentitiesCannotBecomePlayableSources() {
        for(url in listOf("crew://movie/0","crew://movie/1?play=true","crew://tv/../2","https://crew/movie/1","crew://user@movie/1"))assertFalse(VodProvider.CREW.owns(url))
        assertTrue(VodProvider.CREW.owns("crew://tv/1396"))
        assertTrue(CrewCatalogue.parse(JSONObject("""{"titles":[{"title":"Wrong kind","kind":"movie","url":"crew://tv/1"}]}""")).titles.isEmpty())
    }
    @Test fun multipleProvidersUseUnionAndExcludingCrewKeepsSharedTitles() {
        fun film(name:String,vararg providers:VodProvider)=VodTitle(name,2000,"movie","",sources=providers.map { VodSource(it,if(it==VodProvider.CREW)"crew://movie/1" else it.home) })
        val shared=film("Shared",VodProvider.CREW,VodProvider.PLEX)
        val rows=listOf(shared,film("Crew only",VodProvider.CREW),film("Tubi only",VodProvider.TUBI),film("Movy only",VodProvider.MOVY))
        val selected=setOf(VodProvider.PLEX,VodProvider.TUBI)
        assertEquals(listOf("Shared","Tubi only"),VodBrowse(providers=selected).apply(rows).map { it.title })
        assertEquals(listOf("Shared","Crew only"),VodBrowse(providers=setOf(VodProvider.CREW)).apply(rows).map { it.title })
        assertTrue(VodBrowse(providers=emptySet()).apply(rows).isEmpty())
        assertEquals(selected,VodProviderSelection.decode(VodProviderSelection.encode(selected)))
        assertEquals(emptySet<VodProvider>(),VodProviderSelection.decode(""))
        assertEquals(VodProvider.entries.toSet(),VodProviderSelection.decode(null))
        assertEquals(selected,VodBrowse(providers=selected).copy(query="Shared").providers)
    }
}
