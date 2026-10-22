package com.commercialoutcomes.uktelevision

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VodLiveTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun optIn()=assumeTrue(InstrumentationRegistry.getArguments().getString("liveChecks")=="true")
    @Test fun inspectProviderPagesOnDevice() {
        optIn()
        for(provider in VodProvider.entries) {
            runCatching { VodCatalogue.fetch(provider.home) }.onSuccess {
                File(context.getExternalFilesDir(null),"vod-${provider.name}.html").writeText(it.outerHtml())
            }
        }
    }
    @Test fun duloCatalogueHasBrowsableTitles() {
        optIn()
        val doc=VodCatalogue.fetch(VodProvider.DULO.home)
        File(context.getExternalFilesDir(null),"dulo-catalogue-page.html").writeText(doc.outerHtml())
        val rows=VodCatalogue.parse(VodProvider.DULO,doc)
        assertTrue("${doc.title()} · ${doc.outerHtml().length} bytes · ${doc.location()}",rows.isNotEmpty())
    }
    @Test fun nativeLibraryLoadsSeveralProvidersOnDevice() {
        optIn()
        ActivityScenario.launch(OnDemandActivity::class.java).use { scenario ->
            var catalogue=VodResult(emptyList(),emptyMap()); var loaded=false
            val end=System.currentTimeMillis()+120000
            while(System.currentTimeMillis()<end) {
                scenario.onActivity { catalogue=it.catalogue;loaded=!it.isLoading }
                if(catalogue.statuses.size==VodProvider.entries.size && loaded)break
                Thread.sleep(300)
            }
            val json=JSONObject().put("titleCount",catalogue.titles.size)
                .put("providers",JSONObject(catalogue.statuses.mapKeys { it.key.label }))
                .put("multiSourceTitles",catalogue.titles.count { it.sources.size>1 })
                .put("titles",JSONArray(catalogue.titles.map { t -> JSONObject().put("title",t.title).put("year",t.year).put("kind",t.kind).put("sources",JSONArray(t.sources.map { JSONObject().put("provider",it.provider.name).put("url",it.url) })) }))
            File(context.getExternalFilesDir(null),"vod-catalogue.json").writeText(json.toString(2))
            Thread.sleep(3500)
            UiDevice.getInstance(instrumentation).takeScreenshot(File(context.getExternalFilesDir(null),"vod-library.png"))
            assertTrue(json.toString(),catalogue.titles.flatMap { it.sources }.map { it.provider }.distinct().size>=2)
            assertTrue("Dulo titles must appear in the native library",catalogue.titles.any { t->t.sources.any { it.provider==VodProvider.DULO } })
        }
    }
    @Test fun selectedPlexFilmPlaysInsideUkTv() = verifyFilm(VodSource(VodProvider.PLEX,"https://watch.plex.tv/en-GB/movie/the-man-from-earth"), "The Man from Earth")
    @Test fun selectedPlutoFilmPlaysInsideUkTv() = verifyFilm(VodSource(VodProvider.PLUTO,"https://pluto.tv/gb_ie/movies/6876361c63893d441e24e420/"), "The Last Witch Hunter")
    @Test fun selectedTubiFilmPlaysInsideUkTv() = verifyFilm(VodSource(VodProvider.TUBI,"https://tubitv.com/movies/467588/the-man-from-earth"), "The Man from Earth")
    @Test fun selectedDuloFilmPlaysInsideUkTv() = verifyFilm(VodSource(VodProvider.DULO,"https://dulo.mov/title/movie/278/"), "The Shawshank Redemption")
    @Test fun selectedMovyFilmPlaysInsideUkTv() = verifyFilm(VodSource(VodProvider.MOVY,"https://www.movy.sx/movie/278"), "The Shawshank Redemption")
    @Test fun nativeSeriesSelectionPlaysTheChosenMovyEpisode() {
        optIn()
        val seasons=runBlocking { VodEpisodes.load(VodSource(VodProvider.MOVY,"https://www.movy.sx/tv/1396")) }
        assertEquals(5,seasons.size)
        val episodes=runBlocking { VodEpisodes.load(seasons.first().sources.first()) }
        assertEquals(7,episodes.size)
        assertTrue(episodes.first().sources.first().url.endsWith("/1396/1/1"))
        verifyFilm(episodes.first().sources.first(),episodes.first().title)
    }
    private fun verifyFilm(source: VodSource, title: String) {
        optIn()
        val url=runBlocking { VodCatalogue.playbackPage(source) }
        val intent=Intent(context,VodPlayerActivity::class.java).putExtra("provider",source.provider.name).putExtra("url",url).putExtra("title",title)
        ActivityScenario.launch<VodPlayerActivity>(intent).use { scenario ->
            val device=UiDevice.getInstance(instrumentation)
            var output=JSONObject(); var startTime:Double?=null;var played=false
            val deadline=System.currentTimeMillis()+210000
            while(System.currentTimeMillis()<deadline) {
                // Decline optional consent in this opt-in test without repeatedly
                // walking Fire OS's very expensive accessibility tree.
                scenario.onActivity { it.web.evaluateJavascript("""(()=>{for(const b of document.querySelectorAll('button'))if(['I Do Not Agree','Reject','Reject All','No Thanks'].includes(b.textContent.trim()))b.click()})()""",null) }
                val latch=CountDownLatch(1)
                scenario.onActivity { activity -> activity.web.evaluateJavascript("""(()=>{const rows=[];function scan(d){for(const v of d.querySelectorAll('video'))rows.push({paused:v.paused,time:v.currentTime,duration:v.duration,width:v.videoWidth,ready:v.readyState,frames:v.getVideoPlaybackQuality?.().totalVideoFrames,rect:v.getBoundingClientRect().toJSON(),error:v.error?.message});for(const f of d.querySelectorAll('iframe')){try{if(f.contentDocument)scan(f.contentDocument)}catch(e){}}}scan(document);return JSON.stringify({url:location.href,fullscreen:!!document.fullscreenElement,viewport:{width:innerWidth,height:innerHeight,visibleWidth:visualViewport.width,visibleHeight:visualViewport.height},text:document.body.innerText.slice(-1000),videos:rows})})()""") {
                    runCatching { output=JSONObject(org.json.JSONTokener(it).nextValue().toString()) };latch.countDown()
                } }
                latch.await(5,TimeUnit.SECONDS)
                val videos=output.optJSONArray("videos")
                val video=if(videos!=null && videos.length()>0)videos.optJSONObject(0) else null
                if(video!=null && !video.optBoolean("paused",true) && video.optInt("width")>0 && video.optInt("frames")>20 && video.optDouble("duration")>1200) {
                    if(startTime==null)startTime=video.optDouble("time")
                    played=video.optDouble("time")-(startTime ?: 0.0)>=15
                }
                File(context.getExternalFilesDir(null),"vod-playback-${source.provider.name}.json").writeText(output.put("verifiedPlaying",played).toString(2))
                if(played)break
                Thread.sleep(1000)
            }
            device.takeScreenshot(File(context.getExternalFilesDir(null),"vod-playback-${source.provider.name}.png"))
            assertTrue(output.toString(),played)
            if(source.provider==VodProvider.TUBI)assertTrue("Tubi must enter full screen",output.optBoolean("fullscreen"))
            if(source.provider==VodProvider.MOVY) {
                val viewport=output.getJSONObject("viewport")
                assertEquals("The entire player must fit horizontally",viewport.getDouble("width"),viewport.getDouble("visibleWidth"),2.0)
                assertEquals("The entire player must fit vertically",viewport.getDouble("height"),viewport.getDouble("visibleHeight"),2.0)
            }
        }
    }
}
