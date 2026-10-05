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
}
