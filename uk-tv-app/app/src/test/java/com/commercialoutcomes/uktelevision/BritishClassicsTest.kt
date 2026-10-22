package com.commercialoutcomes.uktelevision

import org.junit.Assert.*
import org.junit.Test

class BritishClassicsTest {
    @Test fun familiarNamesResolveWithoutChangingUnrelatedQueries() {
        assertEquals("Only Fools and Horses",BritishClassics.query(" OFAH "))
        assertEquals("The Morecambe and Wise Show",BritishClassics.query("Morcombe and Wise"))
        assertEquals("Minder",BritishClassics.query(" Minder "))
        assertEquals("The Office",BritishClassics.query("The Office"))
    }
}
