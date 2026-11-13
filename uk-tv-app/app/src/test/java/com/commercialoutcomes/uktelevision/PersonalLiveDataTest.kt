package com.commercialoutcomes.uktelevision
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
class PersonalLiveDataTest {
    @Test fun expiredWarningsAreNotCurrent(){
        val spec=PersonalFeed("aemet","alerts","Warnings","AEMET","https://www.aemet.es",600000)
        val raw="<rss><channel><item><title>Rain</title><description>de 10:00 01-01-2020 CET a 12:00 01-01-2020 CET.</description></item></channel></rss>"
        assertEquals("No current warnings returned",PersonalPanelParser.parse(spec,raw).items.single().headline)
    }
    @Test fun indexChangeUsesPreviousSessionNotStartOfMonth(){
        val spec=PersonalFeed("ftse","market","FTSE 100","Yahoo","https://example.org",900000)
        val raw="""{"chart":{"result":[{"meta":{"regularMarketPrice":120,"regularMarketTime":1794571200,"symbol":"^FTSE","currency":"GBP"},"indicators":{"quote":[{"close":[80,100,120]}]}}]}}"""
        val p=PersonalPanelParser.parse(spec,raw)
        assertEquals(20.0,p.items.single().change!!,0.001);assertTrue(p.items.single().body.contains("Delayed"))
    }
    @Test fun liveCapturedNewFeedsParse(){
        val root=System.getenv("PERSONAL_NEW_FEED_FIXTURES") ?: return
        val config=JSONObject(File("src/main/assets/my-channel/panel-feeds.json").readText()).getJSONObject("feeds")
        for(id in listOf("ftse","sp500","ibex","calendar","aemet","launches")){
            val p=PersonalPanelParser.parse(PersonalFeed.decode(config.getJSONObject(id)),File(root,"uktv-$id.txt").readText())
            assertTrue(id,p.items.isNotEmpty());assertTrue(p.items.size<=8)
        }
    }
    @Test fun programmeCandidatesRespectListsAndProviderExclusions(){
        val first=VodTitle("Porridge",1974,"show","",sources=listOf(VodSource(VodProvider.MOVY,"https://www.movy.sx/tv/1")))
        val second=VodTitle("Other",2020,"movie","",sources=listOf(VodSource(VodProvider.CREW,"crew://movie/2")))
        val saved=VodSaved(listOf(first,second),lists=listOf(VodList("classics","UK comedy classics",setOf(first.key)),VodList(PersonalQueue.ID,"Queue",setOf(second.key))))
        assertEquals(listOf(first),PersonalProgrammePlan.candidates(saved,"classics"))
        assertTrue(PersonalProgrammePlan.playable(first,setOf(VodProvider.TUBI)).isEmpty())
        assertTrue(PersonalProgrammePlan.playable(second,VodProvider.entries.toSet()).isEmpty())
    }
    @Test fun programmeMixFallsBackAndSkipsCompletedEpisodes()=kotlinx.coroutines.runBlocking {
        val a=VodSource(VodProvider.MOVY,"https://www.movy.sx/tv/1")
        val b=VodSource(VodProvider.DULO,"https://dulo.mov/series/test")
        val show=VodTitle("A series",2020,"show","",sources=listOf(a,b))
        val first=VodTitle("Episode 1",null,"episode","",sources=listOf(b.copy(url=b.url+"/1")))
        val second=first.copy(title="Episode 2",sources=listOf(b.copy(url=b.url+"/2")))
        val seen=mutableListOf<VodSource>()
        val found=PersonalProgrammePlan.next(show,setOf(VodProvider.MOVY,VodProvider.DULO),setOf(first.key)){source->seen+=source;if(source==a)error("Unavailable") else listOf(first,second)}
        assertEquals(second,found);assertEquals(listOf(a,b),seen)
    }
    @Test fun programmeMixBoundsSeasonRequests()=kotlinx.coroutines.runBlocking {
        val source=VodSource(VodProvider.MOVY,"https://www.movy.sx/tv/1")
        val show=VodTitle("A series",2020,"show","",sources=listOf(source))
        var count=0
        assertNull(PersonalProgrammePlan.next(show,setOf(VodProvider.MOVY),emptySet()){s->count++;if(s==source)(1..100).map{VodTitle("Season $it",null,"season","",sources=listOf(source.copy(url=source.url+"/$it")))} else emptyList()})
        assertEquals(8,count)
    }
}
