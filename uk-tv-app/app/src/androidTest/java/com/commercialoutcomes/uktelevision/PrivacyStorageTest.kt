package com.commercialoutcomes.uktelevision

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyStorageTest {
    @Test
    fun playlistCredentialsAreEncryptedAtRestAndRecoverable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("privacy-storage-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val secret = "https://viewer:super-secret@example.invalid/list.m3u?token=private-token"
        PlaylistImport.save(prefs, listOf(UserPlaylist("Private provider", secret)))

        val rawStorage = prefs.all.values.joinToString("|")
        assertFalse(rawStorage.contains("viewer:super-secret"))
        assertFalse(rawStorage.contains("private-token"))
        assertEquals(secret, PlaylistImport.load(prefs).single().url)

        prefs.edit().clear().commit()
    }
}
