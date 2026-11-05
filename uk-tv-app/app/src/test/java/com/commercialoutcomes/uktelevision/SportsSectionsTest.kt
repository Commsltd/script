package com.commercialoutcomes.uktelevision
import org.junit.Assert.*
import org.junit.Test
class SportsSectionsTest {
    @Test fun sectionsKeepVariantsTogetherAndUnknownsVisible() {
        assertEquals(SportsSections.rank("FIFAPlus.uk@English"), SportsSections.rank("FIFAPlus.uk@SD"))
        assertEquals("Riding, rodeo & bullfighting", SportsSections.label("TDTextremaduratoroscanalextremadura.es@Spain"))
        assertEquals("Other sport", SportsSections.label("NewSport.us@HD"))
        assertTrue(SportsSections.rank("FIFAPlus.uk@English") < SportsSections.rank("PGATour.us@UK"))
    }
}
