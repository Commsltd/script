package com.commercialoutcomes.uktelevision
import org.junit.Test
import org.junit.Assert.*
class PersonalRotationTest {
 @Test fun visitsPoolWithoutRepeats() {val r=PersonalRotation {it};var current=0;val seen=mutableSetOf(current);repeat(3){current=r.next(current,listOf(0,1,2,3),true);assertTrue(seen.add(current))};assertNotEquals(current,r.next(current,listOf(0,1,2,3),true))}
 @Test fun emptyAndSinglePoolStaySafe(){val r=PersonalRotation();assertEquals(2,r.next(2,emptyList(),true));assertEquals(2,r.next(2,listOf(2),true))}
 @Test fun orderedPoolWraps(){val r=PersonalRotation();assertEquals(0,r.next(3,listOf(0,2,3),false));assertEquals(2,r.next(0,listOf(0,2,3),false))}
 @Test fun poolChangesRemoveOldEntries(){val r=PersonalRotation{it};r.next(0,listOf(0,1,2),true);assertEquals(6,r.next(0,listOf(0,6),true))}
}
