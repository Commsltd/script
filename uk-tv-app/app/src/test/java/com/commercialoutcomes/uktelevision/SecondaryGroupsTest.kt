package com.commercialoutcomes.uktelevision

import org.junit.Assert.*
import org.junit.Test

class SecondaryGroupsTest {
    @Test fun torosSharesSportsWithoutChangingCountryShelves() {
        assertEquals("09 SPORT", SecondaryGroups.group("TDTextremaduratoroscanalextremadura.es@Spain"))
        assertEquals("25 USA", SecondaryGroups.group("ABCNewsLive.us@SD"))
        assertNull(SecondaryGroups.group("TDTgeneralistasla1.es@Spain"))
    }
}
