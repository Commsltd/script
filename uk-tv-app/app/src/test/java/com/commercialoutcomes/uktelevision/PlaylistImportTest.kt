package com.commercialoutcomes.uktelevision

import org.junit.Assert.*
import org.junit.Test

class PlaylistImportTest {
    @Test fun youtubeAndDirectSourcesAreTyped() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="SkyNews.uk@HD" tvg-logo="https://logo.example/sky.png",Sky News
            https://example.com/sky/master.m3u8
            #EXTINF:-1 tvg-id="SkyNews.uk@HD",Sky News YouTube
            https://www.youtube.com/@SkyNews/live
        """.trimIndent()
        val stations = PlaylistImport.parse(UserPlaylist("News", "https://example.com/list.m3u"), text, false)
        assertEquals(1, stations.size)
        assertEquals(setOf("direct", "youtube"), stations.single().sources().map { it.kind }.toSet())
    }

    @Test fun strictPrivacyDropsHttpButKeepsHttps() {
        val text = """
            #EXTINF:-1 tvg-id="Test.uk",Test insecure
            http://example.com/live.m3u8
            #EXTINF:-1 tvg-id="Test2.uk",Test secure
            https://example.com/live2.m3u8
        """.trimIndent()
        val stations = PlaylistImport.parse(UserPlaylist("Test", "https://example.com/list.m3u"), text, true)
        assertEquals(listOf("Test2.uk"), stations.map { it.id })
    }

    @Test fun importedExactIdExtendsCuratedSourcePool() {
        val sourceA = """[{"id":"a","url":"https://a.example/a.m3u8","label":"A","host":"a.example","mime":"application/x-mpegURL","headers":{},"unsupportedDrm":false,"kind":"direct"}]"""
        val base = Station("BBCOne.uk@LondonHD","BBC One","BBCOne.uk","01 MAIN UK",1,"","",sourceA,"")
        val imported = PlaylistImport.parse(UserPlaylist("Backup", "https://example.com/b.m3u"), """
            #EXTINF:-1 tvg-id="BBCOne.uk@LondonHD",BBC One backup
            https://b.example/b.m3u8
        """.trimIndent(), false)
        val merged = PlaylistImport.merge(listOf(base), imported)
        assertEquals(1, merged.size)
        assertEquals(2, merged.single().sources().size)
    }
}
