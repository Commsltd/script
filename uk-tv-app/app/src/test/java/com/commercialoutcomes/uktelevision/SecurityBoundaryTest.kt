package com.commercialoutcomes.uktelevision

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SecurityBoundaryTest {
    @Test fun untrustedMediaHeadersCannotInjectRequestsOrOverrideRouting() {
        val headers=MediaRequestHeaders.crew("User-Agent=TV&Referer=https%3A%2F%2Fexample.com%2F&Host=127.0.0.1&X-Test=ok%0D%0AAuthorization%3A+secret&Connection=upgrade&Cookie=valid%3Dsession")
        assertEquals(mapOf("User-Agent" to "TV","Referer" to "https://example.com/","Cookie" to "valid=session"),headers)
        assertThrows(IllegalArgumentException::class.java) { MediaRequestHeaders.crew("x".repeat(17000)) }
    }

    @Test fun providerIdentityRejectsAmbiguousAuthorities() {
        val provider = VodProvider.DULO
        assertTrue(provider.owns("https://dulo.mov:443/title/tv/2292/?s=1&e=1"))
        for (url in listOf("https://dulo.mov.evil.example/", "https://dulo.mov@evil.example/", "https://evil@dulo.mov/",
            "https://dulo.mov:444/", "https://dulo.mov\\@evil.example/", "https://dulo.mov%2f.evil.example/",
            "https://dulo.mov/\n", "http://dulo.mov/", "https://dulo.mov./")) assertFalse(url, provider.owns(url))
    }

    @Test fun internetPlayerRejectsLocalAddressesAndSpecialSchemes() {
        for (host in listOf("127.0.0.1", "127.1", "2130706433", "0x7f000001", "0177.0.0.1", "192.168.68.55", "10.1.2.3",
            "172.16.0.1", "169.254.169.254", "100.64.0.1", "[::1]", "[::ffff:127.0.0.1]", "[fd00::1]", "[fe80::1]",
            "localhost", "router.local", "router.home.arpa", "localhost.", "192.168.1.1.nip.io:5555"))
            assertFalse(host, WebAddressPolicy.publicHttps("https://$host/"))
        for (url in listOf("file:///data/data/app/secret", "content://settings/system", "intent://launch", "javascript:alert(1)",
            "http://cdn.example.com/a", "blob:https://127.0.0.1/abc", "ftp://example.com/a"))
            assertFalse(url, WebAddressPolicy.resource(url))
        for (url in listOf("https://video.example.com/a.m3u8?token=value", "https://8.8.8.8/", "https://[2606:4700:4700::1111]/"))
            assertTrue(url, WebAddressPolicy.publicHttps(url))
        assertTrue(WebAddressPolicy.frame("about:blank"))
        assertTrue(WebAddressPolicy.resource("blob:https://dulo.mov/123"))
        assertTrue(WebAddressPolicy.resource("data:image/png;base64,AAAA"))
        assertFalse(WebAddressPolicy.frame("data:text/html,<script></script>"))
    }

    private fun testDns(answers:List<String>):Dns=object:Dns {
        override fun lookup(hostname:String)=answers.map(InetAddress::getByName)
    }

    @Test fun dnsRejectsPrivateAnswersIncludingMixedPublicPrivateResponses() {
        for (answers in listOf(listOf("127.0.0.1"), listOf("8.8.8.8", "192.168.1.1"), listOf("fd00::1234"))) {
            val dns = PublicVodHttp.publicDns(testDns(answers))
            assertThrows(IOException::class.java) { dns.lookup("innocent-looking.example.com") }
        }
        assertEquals("8.8.8.8", PublicVodHttp.publicDns(testDns(listOf("8.8.8.8"))).lookup("cdn.example.com").single().hostAddress)
    }

    private fun redirectClient(location: String, received: MutableList<Request>): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).addInterceptor(PublicVodHttp.redirects)
        .addInterceptor { chain ->
            val request = chain.request(); received += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("Test")
                .code(if (received.size == 1) 302 else 200).header("Location", location).body("OK".toResponseBody()).build()
        }.build()

    @Test fun unsafeRedirectsNeverReachTheTransport() {
        for (location in listOf("https://127.0.0.1:18765/status", "https://192.168.0.1/", "http://cdn.example.com/a", "file:///etc/passwd")) {
            val received = mutableListOf<Request>()
            assertThrows(IOException::class.java) { redirectClient(location,received).newCall(Request.Builder().url("https://video.example.com/a").build()).execute().close() }
            assertEquals(location, 1, received.size)
        }
    }

    @Test fun publicCdnRedirectWorksWithoutForwardingCredentials() {
        val received = mutableListOf<Request>()
        redirectClient("https://cdn.example.com/feature.m3u8",received).newCall(Request.Builder().url("https://video.example.com/a")
            .header("Cookie","fake=session").header("Authorization","Bearer test").build()).execute().use { assertEquals(200,it.code) }
        assertEquals(2,received.size)
        assertNull(received.last().header("Cookie")); assertNull(received.last().header("Authorization"))
        assertEquals("https://dulo.mov/", PublicVodHttp.referer("https://dulo.mov/title/movie/1/?private=value"))
    }

    @Test fun boundedReadsRejectBeforeConsumingAnOversizedResponse() {
        val stream=ByteArrayInputStream(ByteArray(100000))
        assertThrows(IOException::class.java) { stream.readBounded(1024) }
        assertEquals(100000-1025,stream.available())
        assertEquals(1024,ByteArrayInputStream(ByteArray(1024)).readBounded(1024).size)
    }

    @Test fun unexpectedZipEntryIsNotInflatedOrWritten() {
        val bytes=ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("../../unexpected")); zip.write(ByteArray(6*1024*1024)); zip.closeEntry()
        }
        val target=Files.createTempDirectory("uktv-artwork-test").toFile()
        try {
            assertThrows(IOException::class.java) { ArtworkStore.extract(ByteArrayInputStream(bytes.toByteArray()),target) }
            assertTrue(target.listFiles().orEmpty().isEmpty())
        } finally { target.deleteRecursively() }
    }

    @Test fun boundedArtworkArchiveStillExtracts() {
        val bytes=ByteArrayOutputStream(); val name="a".repeat(32)+".svg"
        ZipOutputStream(bytes).use { zip -> zip.putNextEntry(ZipEntry(name)); zip.write("<svg/>".toByteArray()); zip.closeEntry() }
        val target=Files.createTempDirectory("uktv-artwork-test").toFile()
        try { ArtworkStore.extract(ByteArrayInputStream(bytes.toByteArray()),target); assertEquals("<svg/>",target.resolve(name).readText()) }
        finally { target.deleteRecursively() }
    }

    @Test fun youtubeEventsAreBoundedAndOnlyAffectThePlayerState() {
        assertEquals(listOf("state" to 1, "ready" to 0),WebPlayerEvents.parse(JSONObject.quote("[[\"state\",1],[\"ready\",0]]")))
        assertTrue(WebPlayerEvents.parse(JSONObject.quote("[[\"launch\",1],[\"state\",\"1\"]]")).isEmpty())
        assertTrue(WebPlayerEvents.parse("x".repeat(5000)).isEmpty())
        assertTrue(WebPlayerEvents.parse(JSONObject.quote("["+List(13){"[\"state\",1]"}.joinToString(",")+"]")).isEmpty())
    }

    @Test fun lookalikeYoutubeHostIsNotTreatedAsAnOfficialPlayer() {
        val rows=PlaylistImport.parse(UserPlaylist("Test","https://example.com/list.m3u"),"#EXTINF:-1 tvg-id=\"Test\",Test\nhttps://evilyoutube.com/watch?v=abcdefghijk",false)
        assertEquals("direct",rows.single().sources().single().kind)
    }
}
