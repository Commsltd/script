package com.commercialoutcomes.uktelevision
import org.junit.Test
import org.junit.Assert.*
class PersonalQueueTest {
 private fun title(kind:String,name:String)=VodTitle(name,2020,kind,"",sources=listOf(VodSource(VodProvider.MOVY,"https://movy.sx/$name")))
 @Test fun queueUsesExistingListsAndPreservesOrder(){val a=title("episode","S1 E1");val b=title("episode","S1 E2");val original=VodSaved().createList("Keep").toggleFavourite(a);val saved=PersonalQueue.add(PersonalQueue.add(original,a),b);assertTrue(saved.isFavourite(a));assertEquals("Keep",saved.lists.first().name);assertEquals(listOf(a.key,b.key),saved.lists.last().keys.toList())}
 @Test(expected=IllegalArgumentException::class) fun seriesMustChooseExactEpisode(){PersonalQueue.add(VodSaved(),title("show","Series"))}
 @Test fun repeatedAdditionDoesNotDuplicateEpisode(){val a=title("episode","S1 E1");val saved=PersonalQueue.add(PersonalQueue.add(VodSaved(),a),a);assertEquals(1,saved.lists.last().keys.size)}
}
