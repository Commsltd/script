package com.commercialoutcomes.uktelevision

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledCatalogueTest {
    @Test fun packagedGuideSeedsDatabaseWithoutAnyNetworkFetch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, TvDatabase::class.java).build()
        try {
            val repository = TvRepository(context, db)
            repository.seed() // This method performs asset/database I/O only; no HTTP path.
            assertTrue("Bundled channels missing", db.dao().stationCount() > 100)
            val snapshot = db.dao().currentSnapshot()!!
            assertTrue("Bundled programme data missing", snapshot.programmeCount > 1000)
            assertTrue("Bundled descriptions missing", snapshot.descriptions > 100)
            val favourite = Favourite(db.dao().stations().first().first().id)
            db.dao().addFavourite(favourite)
            repository.seed()
            assertTrue("Repeated startup removed favourites", favourite in db.dao().favourites().first())
        } finally {
            db.close()
        }
    }
    @Test fun failedBatchImportRollsBackWithoutLosingGuideOrFavourites() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, TvDatabase::class.java).build()
        try {
            val repository = TvRepository(context, db)
            val channel = """{"id":"test","name":"Test","family":"test","group":"TEST","order":0,"sources":[{"id":"s","url":"https://example.org/live.m3u8"}]}"""
            fun document(valid: Boolean): String {
                val shows = (0..600).joinToString(",") { n ->
                    """{"key":"p$n","channelId":"test","start":100,"stop":200,"title":"Test $n"}"""
                }
                // Channel metadata is deliberately last: validation fails after multiple inserts.
                return """{"schemaVersion":1,"builtAt":100,"programmes":[$shows],"channels":[${if(valid)channel else ""}]}"""
            }
            repository.importCatalogue(document(true).byteInputStream())
            db.dao().addFavourite(Favourite("test"))
            val before = db.dao().currentSnapshot()
            assertTrue(runCatching { repository.importCatalogue(document(false).byteInputStream()) }.isFailure)
            assertEquals(601, db.dao().programmes().first().size)
            assertEquals(before, db.dao().currentSnapshot())
            assertEquals(listOf(Favourite("test")), db.dao().favourites().first())
            assertEquals(601, db.dao().windowProgrammes(50, 250, 50, 250).first().size)
            assertEquals(1, db.dao().searchProgrammes("Test 600", 50).size)
        } finally { db.close() }
    }

}
