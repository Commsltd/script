package com.commercialoutcomes.uktelevision

import org.junit.Assert.*
import org.junit.Test

class GuideRulesTest {
    private fun programme(start: Long, stop: Long) = Programme("p$start", "a", start, stop, "Programme", "", "Synopsis", "", "")
    private fun station(id: String) = Station(id, id, "BBCOne.uk", "01 MAIN UK", 0, "", "", "[]", "")
    @Test fun scheduleBoundsAreHalfOpen() {
        val p = programme(100, 200)
        assertEquals(p, GuideRules.at(listOf(p), 100))
        assertNull(GuideRules.at(listOf(p), 200))
    }
    @Test fun adjacentProgrammesChooseCorrectOne() {
        val a = programme(100,200); val b = programme(200,300)
        assertEquals(b, GuideRules.at(listOf(a,b),200))
    }
    @Test fun regionsAreNeverAutomaticFallbacks() {
        assertFalse(GuideRules.canAutoFallback(station("BBCOne.uk@London"), station("BBCOne.uk@Wales")))
        assertFalse(GuideRules.canAutoFallback(station("ITV1.uk"), station("ITV1.uk@Plus1")))
        assertTrue(GuideRules.canAutoFallback(station("BBCOne.uk@London"), station("BBCOne.uk@London")))
    }
    @Test fun recentlyWorkingSourceOutranksNeverTestedSource() {
        assertTrue(GuideRules.healthScore(StreamHealth("a","UK",lastOk=100)) > GuideRules.healthScore(null))
    }
    @Test fun failuresAffectPreferenceNotGlobalDeletion() {
        assertTrue(GuideRules.healthScore(StreamHealth("a","UK",failures=1,lastFailure=100)) < GuideRules.healthScore(null))
    }
    @Test fun windowIncludesPartialProgramme() {
        assertEquals(1,GuideRules.inWindow(listOf(programme(50,150)),100,200).size)
        assertEquals(0,GuideRules.inWindow(listOf(programme(50,100)),100,200).size)
    }
}
