package com.commercialoutcomes.uktelevision
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
class PersonalPanelParserTest {
    private fun spec(kind:String)=PersonalFeed("test",kind,"Panel","Source","https://example.org",300000)
    @Test fun rssDeduplicatesAndKeepsOriginalDates(){
        val p=PersonalPanelParser.parse(spec("rss"),"<rss><channel><item><title>One &amp; two</title><pubDate>Mon, 9 Nov 2026 10:00:00 GMT</pubDate><link>https://example.org/1</link></item><item><title>One &amp; two</title></item></channel></rss>",1)
        assertEquals(1,p.items.size);assertEquals("One & two",p.items[0].headline);assertTrue(p.items[0].observed.contains("10:00"));assertEquals(1,p.fetchedAt)
    }
    @Test fun atomRetainsAttributionAndPublicationTime(){val p=PersonalPanelParser.parse(spec("rss"),"<feed><entry><title>Research</title><published>2026-11-09</published><link href='https://arxiv.org/abs/1'/></entry></feed>");assertEquals("2026-11-09",p.items[0].observed);assertTrue(p.items[0].url.contains("arxiv"))}
    @Test fun fxUsesLatestObservationNotXmlOrdering(){val p=PersonalPanelParser.parse(spec("fx"),"<Envelope><Cube time='2026-11-06'><Cube currency='GBP' rate='0.8'/><Cube currency='USD' rate='1.2'/></Cube><Cube time='2026-11-05'><Cube currency='GBP' rate='1'/><Cube currency='USD' rate='1'/></Cube></Envelope>");assertEquals("£1 = €1.2500",p.items[0].headline);assertEquals("2026-11-06",p.items[0].observed);assertEquals(listOf(1.0,1.25),p.series)}
    @Test(expected=IllegalArgumentException::class) fun emptyFeedsDoNotBecomeSuccess(){PersonalPanelParser.parse(spec("rss"),"<rss/>")}
    @Test fun cacheRoundTripPreservesFailureAndObservation(){val p=PersonalPanel("id","title","source",listOf(PersonalItem("headline","body","old observation","url")),123,true,listOf(1.0,2.0),"history");assertEquals(p,PersonalPanel.decode(p.json()))}
    @Test fun recordsAreBounded(){val raw="<rss>"+(0..100).joinToString(""){"<item><title>News $it</title></item>"}+"</rss>";assertEquals(8,PersonalPanelParser.parse(spec("rss"),raw).items.size)}
    @Test fun publicCameraRoutesRefreshFromPlayerMetadata(){
        val url="https://live.hdontap.com/example/playlist.m3u8?t=fresh"
        assertEquals(url,PersonalMediaResolver.parse("hdontap","<html><script id='player-data' type='application/json'>{\"streamSrc\":\"$url\"}</script></html>"))
        assertEquals(url,PersonalMediaResolver.parse("pixelcaster","{\"signed_url\":\"$url\"}"))
    }
    @Test(expected=IllegalArgumentException::class) fun camerasCannotReturnCleartext(){PersonalMediaResolver.parse("pixelcaster","{\"signed_url\":\"http://example.com/video.m3u8\"}")}
    @Test fun fxJsonKeepsPairHistoryAndDailyChangeTogether(){
        val raw="""[{"date":"2026-11-05","base":"EUR","quote":"GBP","rate":1.0},{"date":"2026-11-06","base":"EUR","quote":"GBP","rate":0.8}]"""
        val p=PersonalPanelParser.parse(spec("fx-json"),raw)
        assertEquals("GBP / EUR",p.items.first().metric);assertEquals(25.0,p.items.first().change!!,0.001)
        assertEquals(listOf(1.0,1.25),p.items.first().history)
        assertEquals(p,PersonalPanel.decode(p.json()))
    }
    @Test fun staleNewsDoesNotMasqueradeAsCurrent(){
        val now=PersonalPanelParser.publishedTime("2026-11-09T12:00:00Z")!!
        val raw="<rss><item><title>Old</title><pubDate>2022-04-01</pubDate></item><item><title>Current</title><pubDate>Mon, 09 Nov 2026 10:00:00 GMT</pubDate></item></rss>"
        val p=PersonalPanelParser.parse(spec("rss").copy(maxAge=86400000),raw,now)
        assertEquals(listOf("Current"),p.items.map{it.headline})
    }
    @Test fun releaseMetadataKeepsVersionAndPublicationDate(){
        val p=PersonalPanelParser.parse(spec("github-release"),"""{"tag_name":"v26.13.0","published_at":"2026-10-28T16:57:41Z","html_url":"https://github.com/nodejs/node/releases/tag/v26.13.0","draft":false,"prerelease":false}""")
        assertEquals("v26.13.0",p.items.single().headline);assertEquals("2026-10-28T16:57:41Z",p.items.single().observed)
    }
    @Test fun checkedLiveFeedFixturesAllParse(){
        val root=System.getenv("PERSONAL_LIVE_FIXTURES") ?: return
        val config=JSONObject(File("src/main/assets/my-channel/panel-feeds.json").readText()).getJSONObject("feeds")
        config.keys().forEach { id->val p=PersonalPanelParser.parse(PersonalFeed.decode(config.getJSONObject(id)),File(root,"$id.txt").readText());assertTrue("$id empty",p.items.isNotEmpty());assertTrue(p.items.size<=8);println("LIVE FEED VERIFIED $id: ${p.items.size} records") }
    }
}
