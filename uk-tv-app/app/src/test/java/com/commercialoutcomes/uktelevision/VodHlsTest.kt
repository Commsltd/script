package com.commercialoutcomes.uktelevision

import org.junit.Assert.*
import org.junit.Test

class VodHlsTest {
    private val film="#EXTM3U\n#EXTINF:600,\na.ts\n#EXTINF:600.5,\nb.ts\n#EXT-X-ENDLIST\n"
    @Test fun matchingCompleteFilmIsEligible(){assertTrue(VodHls.matches(film,1200.5))}
    @Test fun trailersLiveStreamsAndProtectedMediaStayWithProvider(){
        assertFalse(VodHls.matches(film,3308.0))
        assertFalse(VodHls.matches(film,120.0))
        assertFalse(VodHls.matches(film.replace("#EXT-X-ENDLIST",""),1200.5))
        assertFalse(VodHls.matches(film+"#EXT-X-KEY:METHOD=SAMPLE-AES",1200.5))
        assertFalse(VodHls.matches(film+"#EXT-X-DATERANGE:CLASS=ad",1200.5))
    }
}
