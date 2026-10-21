package com.commercialoutcomes.uktelevision

import org.junit.Assert.*
import org.junit.Test

class SourceAdviceTest {
    @Test fun anAccessRefusalDoesNotProveGeoBlocking() {
        val advice=SourceAdvice.explain(SourceCheck(0,"HTTP_403",country="United Kingdom"))
        assertTrue(advice.contains("cause is unconfirmed"))
        assertTrue(advice.contains("United Kingdom"))
        assertFalse(SourceAdvice.geoMessage("403 Forbidden"))
        assertTrue(SourceAdvice.geoMessage("This video is not available in your country"))
    }
    @Test fun countryAdviceComesFromKnownServiceRoutesOnly() {
        fun source(url:String)=StreamSource("BBCOne.uk",url,"UK channel","","",emptyMap(),false)
        assertEquals("United Kingdom",SourceAdvice.country(source("https://vs-hls-push-uk-live.akamaized.net/a.m3u8")))
        assertEquals("",SourceAdvice.country(source("https://example.org/bbc/uk.m3u8")))
    }
    @Test fun cachedReachabilityExpiresAndIsNotProofOfPlayback() {
        val check=SourceCheck(1000,"MEDIA_REACHABLE")
        assertTrue(check.fresh(2000));assertFalse(check.fresh(301000))
        assertTrue(SourceAdvice.explain(check).contains("not yet verified"))
    }
}
