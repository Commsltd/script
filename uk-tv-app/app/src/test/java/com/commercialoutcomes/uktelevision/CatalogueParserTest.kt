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
    @Test fun unresolvedYoutubePageIsAddedOnlyByTheNewParser() {
        val json=fixture()
        val channel=json.getJSONArray("channels").getJSONObject(0)
        channel.put("youtubeLivePage","https://www.youtube.com/@SkyNews/live")
            .put("youtubeLabel","Sky News official YouTube").put("youtubeSourceId","sky-current")
        val sources=parse(json).stations.single().sources()
        assertEquals(2,sources.size)
        assertEquals("youtube",sources.last().kind)
        assertEquals("https://www.youtube.com/@SkyNews/live",sources.last().livePage)
    }
    @Test fun regionalSchedulesStaySeparateFromTheDefaultGuide() {
        val json = fixture()
        json.getJSONArray("channels").getJSONObject(0).getJSONArray("sources").getJSONObject(0)
            .put("region","Wales").put("guideChannelId","A.uk@Wales").put("priority",1)
        val regional = JSONObject(json.getJSONArray("programmes").getJSONObject(0).toString())
            .put("channelId","A.uk@Wales").put("key","wales").put("title","Wales news")
        json.put("regionalProgrammes",JSONArray().put(regional))
        val data = parse(json)
        assertEquals(1, data.stations.size)
        assertEquals("Wales news", data.programmes.first { it.channelId == "A.uk@Wales" }.title)
        assertEquals("Programme title", data.programmes.first { it.channelId == "A.uk@London" }.title)
        assertEquals(1, data.stations.single().sources().single().priority)
    }

    @Test fun largeCompressedGuideParsesWithoutWholeDocumentCopies() {
        val file = java.io.File.createTempFile("large-guide", ".json.gz")
        try {
            val metadata = fixture().apply { remove("programmes") }.toString().dropLast(1)
            java.util.zip.GZIPOutputStream(file.outputStream()).bufferedWriter().use { out ->
                out.write(metadata + ",\"programmes\":[")
                val description = "Programme résumé · ".repeat(24)
                for (n in 0 until 60000) {
                    if (n > 0) out.write(",")
                    out.write("""{"key":"p$n","channelId":"A.uk@London","start":1791190800000,"stop":1791194400000,"title":"Programme $n","description":"$description"}""")
                }
                out.write("]}")
            }
            var imported = 0; var largestBatch = 0; var lastTitle = ""
            val parsed = file.inputStream().use { input -> CatalogueParser.parse(input) { batch ->
                imported += batch.size; largestBatch = maxOf(largestBatch, batch.size)
                lastTitle = batch.last().title
            } }
            assertEquals(60000, imported)
            assertTrue(largestBatch <= 250)
            assertTrue(parsed.programmes.isEmpty())
            assertEquals("Programme 59999", lastTitle)
            assertEquals(60000, parsed.snapshot.descriptions)
        } finally { file.delete() }
    }

    @Test fun batchImportDoesNotRetainAllProgrammesAndValidatesLateMetadata() {
        val json = fixture()
        val shows = json.getJSONArray("programmes")
        val first = shows.getJSONObject(0).toString()
        for (n in 1..1000) shows.put(JSONObject(first).put("key", "key-$n"))
        var count = 0; var largest = 0
        val parsed = CatalogueParser.parse(json.toString().byteInputStream()) {
            count += it.size; largest = maxOf(largest, it.size)
        }
        assertEquals(1001, count); assertTrue(largest <= 250)
        assertTrue(parsed.programmes.isEmpty()); assertEquals(1001, parsed.snapshot.programmeCount)
        json.put("schemaVersion", 99)
        assertThrows(IllegalArgumentException::class.java) {
            CatalogueParser.parse(json.toString().byteInputStream()) { }
        }
    }

}
