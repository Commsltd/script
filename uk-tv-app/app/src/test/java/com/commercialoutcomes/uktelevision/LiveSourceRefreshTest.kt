package com.commercialoutcomes.uktelevision

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class LiveSourceRefreshTest {
    private fun source(id: String, token: String = id) = StreamSource(id,
        "https://cdn.example/live.m3u8?token=$token", id, "cdn.example",
        "application/x-mpegURL", emptyMap(), false)

    @Test fun expiredAddressCanBeReplacedWithoutLosingRegionalAlternatives() {
        val old = listOf(source("national", "expired")) + (1..12).map { source("region$it") }
        val fresh = listOf(source("national", "renewed"), source("new-alternative"))
        val merged = LiveSourceRules.merge(old, fresh)
        assertEquals(14, merged.size)
        assertEquals("renewed", merged.first().url.substringAfter("token="))
        assertEquals(setOf("national", "new-alternative"), LiveSourceRules.changedIds(old, fresh))
        assertEquals(old.drop(1), merged.drop(2))
        assertEquals(merged, LiveSourceRules.merge(merged, fresh))
    }

    @Test fun renamingAFailedAddressDoesNotMakeItANewPlaybackAttempt() {
        val old = listOf(source("old", "same"))
        val fresh = listOf(source("new", "same"))
        assertTrue(LiveSourceRules.changedIds(old, fresh).isEmpty())
        assertEquals(1, LiveSourceRules.merge(old, fresh).size)
        assertEquals(old, LiveSourceRules.merge(old, emptyList()))
    }

    private fun inventory(url: String = "https://cdn.example/a.m3u8", builtAt: Long = 1000): JSONObject =
        JSONObject().put("schemaVersion", 1).put("builtAt", builtAt).put("channels", JSONArray().put(
            JSONObject().put("id", "BBCOne.uk@National").put("sources", JSONArray().put(
                JSONObject().put("id", "a").put("url", url)))))

    @Test fun inventoryOnlyAppliesToTheExactChannelIdentity() {
        val result = LiveSourceRules.parse(inventory().toString(), 1001)
        assertEquals(1, result["BBCOne.uk@National"]?.size)
        assertNull(result["BBCTwo.uk@National"])
        assertNull(result["BBCOne.uk@London"])
    }

    @Test fun staleFutureAndCredentialBearingInventoriesAreRejected() {
        val invalid = listOf(inventory(builtAt = -8 * 86400000L), inventory(builtAt = 999999),
            inventory("file:///etc/passwd"), inventory("https://user:pass@cdn.example/a.m3u8"))
        invalid.forEach { assertTrue(runCatching { LiveSourceRules.parse(it.toString(), 1000) }.isFailure) }
    }

    @Test fun providerPagesAndDuplicateIdentitiesAreRejected() {
        val web = inventory()
        web.getJSONArray("channels").getJSONObject(0).getJSONArray("sources").getJSONObject(0).put("kind", "web")
        assertTrue(runCatching { LiveSourceRules.parse(web.toString(), 1000) }.isFailure)
        val duplicate = inventory()
        val channels = duplicate.getJSONArray("channels")
        channels.put(channels.getJSONObject(0))
        assertTrue(runCatching { LiveSourceRules.parse(duplicate.toString(), 1000) }.isFailure)
    }

    @Test fun compressedInventoryHasADecompressedSizeLimit() {
        fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(bytes) }
        }.toByteArray()
        val text = inventory().toString()
        assertEquals(text, LiveSourceRules.read(gzip(text.toByteArray()).inputStream()))
        assertTrue(runCatching { LiveSourceRules.read(gzip(ByteArray(6 * 1024 * 1024 + 1)).inputStream()) }.isFailure)
    }
    @Test fun hourlyStatusCarriesTheSameExactSourceInventory() {
        val wrapped = JSONObject().put("builtAt", 1000).put("channels", 1049)
            .put("liveSources", inventory())
        assertEquals(LiveSourceRules.parse(inventory().toString(), 1001),
            LiveSourceRules.parse(wrapped.toString(), 1001))
    }

    @Test fun backgroundRenewalMakesTheFailedIdentityEligibleAgain() {
        val old = listOf(source("national", "expired"), source("regional"))
        val fresh = listOf(source("national", "renewed"))
        val tried = setOf("national", "regional")
        val remaining = LiveSourceRules.remainingAttempts(tried, old, fresh)
        val merged = LiveSourceRules.merge(old, fresh)
        assertEquals(listOf("national"), merged.filter { it.id !in remaining }.map { it.id })
        assertEquals(setOf("regional"), remaining)
        assertEquals(remaining, LiveSourceRules.remainingAttempts(remaining, merged, fresh))
        assertEquals(tried, LiveSourceRules.remainingAttempts(tried, old, old))
    }

}
