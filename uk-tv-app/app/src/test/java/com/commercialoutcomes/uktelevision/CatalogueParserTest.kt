package com.commercialoutcomes.uktelevision

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class CatalogueParserTest {
    private fun fixture(): JSONObject = JSONObject("""{
      "schemaVersion":1,"builtAt":1791194269133,"sourceRevision":"test",
      "channels":[{"id":"A.uk@London","name":"A London","family":"A.uk","group":"01 MAIN UK","order":0,
        "sources":[{"id":"stream-a","url":"https://example.org/a.m3u8","label":"A","headers":{"User-Agent":"TV test"}}]}],
      "programmes":[{"key":"show-1","channelId":"A.uk@London","start":1791190800000,"stop":1791194400000,
        "title":"Programme title","subtitle":"Episode title","description":"A complete programme synopsis.","details":"Series 2 · Episode 3"}]
    }""")
    private fun parse(json: JSONObject) = CatalogueParser.parse(ByteArrayInputStream(json.toString().toByteArray()))
    @Test fun preservesSuppliedDescriptionsAndMetadata() {
        val data = parse(fixture())
        assertEquals("A complete programme synopsis.",data.programmes.single().description)
        assertEquals("Episode title",data.programmes.single().subtitle)
        assertEquals(1,data.snapshot.descriptions)
        assertEquals("TV test",data.stations.single().sources().single().headers["User-Agent"])
    }
    @Test fun gzipAndPlainImportsAgree() {
        val json = fixture()
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(json.toString().toByteArray()) }
        val compressed = CatalogueParser.parse(ByteArrayInputStream(output.toByteArray()))
        assertEquals(parse(json).programmes,compressed.programmes)
        assertEquals(parse(json).stations,compressed.stations)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsUnknownSchema() { parse(fixture().put("schemaVersion",99)) }
    @Test(expected=IllegalArgumentException::class) fun rejectsEmptyChannelList() { parse(fixture().put("channels",JSONArray())) }
    @Test(expected=IllegalArgumentException::class) fun rejectsEmptyProgrammeList() { parse(fixture().put("programmes",JSONArray())) }
    @Test(expected=IllegalArgumentException::class) fun rejectsUnknownProgrammeChannel() {
        val json = fixture(); json.getJSONArray("programmes").getJSONObject(0).put("channelId","other")
        parse(json)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsReversedTimes() {
        val json = fixture(); json.getJSONArray("programmes").getJSONObject(0).put("stop",1)
        parse(json)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsDuplicateChannelIds() {
        val json = fixture(); val channels = json.getJSONArray("channels"); channels.put(channels.getJSONObject(0))
        parse(json)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsNonNetworkMediaUri() {
        val json = fixture(); json.getJSONArray("channels").getJSONObject(0).getJSONArray("sources").getJSONObject(0).put("url","file:///private/a")
        parse(json)
    }
}
