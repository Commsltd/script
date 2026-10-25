package com.commercialoutcomes.uktelevision
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MustSeeFilmsTest {
    private val collection get()=BritishClassics.collections(File("src/main/assets/must-see-films.json").readText()).single()
    @Test fun completeFilmsAndNotesPreserveSuppliedVersionsAndOrder() {
        val c=collection
        assertEquals("100 Must See Films",c.name);assertEquals(100,c.titles.size);assertEquals(100,c.notes.size)
        assertEquals(100,c.titles.map{it.key}.distinct().size)
        assertEquals("Citizen Kane",c.titles.first().title);assertEquals("The Substance",c.titles.last().title)
        assertEquals(1942,c.titles[1].year);assertEquals(2003,c.titles.first{it.title=="Oldboy"}.year)
        assertTrue(c.titles.all{it.kind=="movie"&&it.sources.all{s->s.provider.owns(s.url)}})
        assertTrue(c.titles.first{it.title=="Dr. Strangelove"}.sources.any{it.url=="crew://movie/935"})
    }
    @Test fun refreshCannotReorderListOrDiscardNotesAndUserEditsPersist() {
        val c=collection;val prior=VodSaved().toggleFavourite(c.titles[10]).createList("Weekend")
        val seeded=BritishClassics.install(prior,listOf(c),false)
        val reopened=VodSavedCodec.decode(VodSavedCodec.encode(seeded))
        assertEquals(prior.favourites,reopened.favourites);assertEquals(prior.lists.first(),reopened.lists.first())
        val rows=reopened.listTitles(c.id,c.titles.reversed())
        assertEquals(c.titles.map{it.title},rows.map{it.title})
        assertTrue(rows.first().description.startsWith("Power, wealth, media and ambition."))
        val remembered=reopened.toggleFavourite(rows.first())
        assertEquals(rows.first().description,remembered.listTitles(c.id,emptyList()).first().description)
        val edited=reopened.setLists(c.titles.first(),emptySet()).renameList(c.id,"My films")
        assertEquals(edited,BritishClassics.install(edited,listOf(c),true))
        assertEquals(99,edited.listTitles(c.id,emptyList()).size)
    }
}
