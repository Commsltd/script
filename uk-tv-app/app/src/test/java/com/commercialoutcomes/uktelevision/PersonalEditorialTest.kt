package com.commercialoutcomes.uktelevision
import org.junit.Test
import org.junit.Assert.*
class PersonalEditorialTest {
    @Test fun duplicateReportingDoesNotFillBriefing(){
        val items=listOf(PersonalItem("Bank raises interest rates again today"),PersonalItem("Bank raises interest rates again today!"),PersonalItem("Wildlife camera reveals migrating birds"))
        assertEquals(2,PersonalEditorial.distinct(items).size)
    }
    @Test fun leadRotatesWithPublisherAttribution(){
        val p=PersonalPanel("x","News","Publisher",listOf(PersonalItem("First","Source summary"),PersonalItem("Second","Second summary")))
        val result=PersonalEditorial.brief(mapOf("x" to p),listOf("x"),1).items.single()
        assertEquals("Second",result.headline);assertEquals("Second summary",result.body);assertTrue(result.observed.contains("Publisher"))
    }
    @Test fun unavailableFeedCannotBePresentedAsReporting(){
        val p=PersonalPanel("x","News","Publisher",listOf(PersonalItem("Unavailable")),available=false)
        assertFalse(PersonalEditorial.brief(mapOf("x" to p),listOf("x"),0).available)
    }
    @Test fun perspectiveUsesActualObservationWindow(){
        val p=PersonalPanel("fx","FX","ECB",listOf(PersonalItem("GBP/EUR",history=listOf(1.0,1.2))))
        val text=PersonalEditorial.currencyContext(p).items.single().body
        assertTrue(text.contains("+20.00%"));assertTrue(text.contains("2 daily observations"));assertTrue(text.contains("fees"))
    }
    @Test fun publisherSummaryIsPlainTextAndBounded(){
        val feed=PersonalFeed("x","rss","News","Publisher","https://example.org",300000)
        val p=PersonalPanelParser.parse(feed,"<rss><item><title>Headline</title><description>&lt;b&gt;Publisher explanation&lt;/b&gt;</description></item></rss>")
        assertEquals("Publisher explanation",p.items.single().body)
    }
}
