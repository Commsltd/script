package com.commercialoutcomes.uktelevision

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ArtworkPrivacyTest {
    @Test
    fun strictPrivacyUsesBundledArtworkButRejectsThirdPartyArtwork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ArtworkStore.seed(context)

        val catalogue = BundledCatalogue.read(context)
        val mirrored = catalogue.stations.firstOrNull { it.logo.startsWith(ArtworkStore.PREFIX) }
            ?: error("Bundled catalogue contains no mirrored channel logo")

        val model = ArtworkStore.model(context, mirrored.logo, PrivacyMode.STRICT)
        assertNotNull(model)
        assertTrue(model is File && model.isFile)

        assertNull(
            ArtworkStore.model(
                context,
                "https://third-party.example/logo.png",
                PrivacyMode.STRICT
            )
        )
    }
}
