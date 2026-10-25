package com.commercialoutcomes.uktelevision

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BritishClassicsLibraryTest {
    private val collections get()=BritishClassics.collections(File("src/main/assets/british-classics.json").readText())
    @Test fun substantialOriginalBritishCollectionsHaveMetadataAndValidRoutes() {
        val groups=collections
        assertEquals(listOf(90,108),groups.map{it.titles.size})
        val all=groups.flatMap{it.titles}
        assertEquals(198,all.map{it.key}.distinct().size)
        assertTrue(all.all{it.kind=="show"&&it.year!=null&&it.description.isNotBlank()&&it.poster.startsWith("https://")&&it.sources.all{s->s.provider.owns(s.url)}})
        for((name,year) in mapOf("Open All Hours" to 1976,"Only Fools and Horses" to 1981,"Porridge" to 1974,"The Office" to 2001,"All Creatures Great and Small" to 1978,"Poldark" to 1975,"Doctor Who" to 1963))assertTrue(name,all.any{it.title==name&&it.year==year})
        assertTrue(groups[1].titles.any{it.title=="New Tricks"})
        assertTrue(groups[1].titles.any{it.title=="Minder"})
    }
    @Test fun migrationPreservesUserLibraryAndReusesMatchingList() {
        val groups=collections;val title=groups[0].titles.first()
        val before=VodSaved().toggleFavourite(title).createList("Weekend").createList("UK COMEDY CLASSICS")
        val customId=before.lists.last().id
        val after=BritishClassics.install(before,groups,false)
        assertEquals(before.favourites,after.favourites)
        assertEquals(3,after.lists.size)
        assertEquals(90,after.lists.first{it.id==customId}.keys.size)
        assertEquals(before.lists.first(),after.lists.first())
        assertEquals(after,VodSavedCodec.decode(VodSavedCodec.encode(after)))
        assertEquals(after,BritishClassics.install(after,groups,false))
    }
    @Test fun sparseProviderRefreshCannotEraseSavedSynopses() {
        val title=collections[0].titles.first()
        val sparse=title.copy(description="",poster="",sources=listOf(title.sources.first()))
        val saved=VodSaved().toggleFavourite(sparse)
        val installed=BritishClassics.install(saved,collections,false)
        val visible=installed.available(listOf(sparse)).first{it.key==title.key}
        assertEquals(title.description,visible.description)
        assertEquals(title.poster,visible.poster)
        assertEquals(saved.favourites,installed.favourites)
    }
    @Test fun userEditsAndDeletionSurviveSubsequentLaunches() {
        val groups=collections;val seeded=BritishClassics.install(VodSaved(),groups,false)
        val removed=seeded.setLists(groups[0].titles.first(),emptySet()).renameList(groups[0].id,"My comedy").deleteList(groups[1].id)
        val reopened=BritishClassics.install(VodSavedCodec.decode(VodSavedCodec.encode(removed)),groups,true)
        assertEquals(removed,reopened)
        assertEquals(89,reopened.lists.single().keys.size)
        assertEquals("My comedy",reopened.lists.single().name)
        assertTrue(VodBrowse(providers=setOf(VodProvider.CREW)).apply(reopened.titles).isNotEmpty())
        assertTrue(VodBrowse(providers=emptySet()).apply(reopened.titles).isEmpty())
    }
}
