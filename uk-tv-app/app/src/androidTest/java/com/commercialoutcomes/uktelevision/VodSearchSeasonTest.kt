package com.commercialoutcomes.uktelevision

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class VodSearchSeasonTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private fun optIn()=assumeTrue(InstrumentationRegistry.getArguments().getString("liveChecks")=="true")
    @Test fun onlineSearchFindsUnloadedTitleAndDuloSeasonTwo() {
        optIn()
        val report=JSONObject()
        ActivityScenario.launch(OnDemandActivity::class.java).use { scenario ->
            val started=System.currentTimeMillis()
            scenario.onActivity{it.searchOnline("Fawlty Towers")}
            var rows=emptyList<VodTitle>();var loading=true
            val deadline=System.currentTimeMillis()+25000
            while(System.currentTimeMillis()<deadline){scenario.onActivity{rows=it.visibleTitles;loading=it.isLoading};if(!loading)break;Thread.sleep(250)}
            report.put("searchMs",System.currentTimeMillis()-started).put("searchTitles",rows.map{it.title})
            assertTrue(report.toString(),rows.any{it.title=="Fawlty Towers"})
            UiDevice.getInstance(inst).takeScreenshot(File(inst.targetContext.getExternalFilesDir(null),"online-search.png"))
        }
        runBlocking {
            val seasons=VodEpisodes.load(VodSource(VodProvider.DULO,"https://dulo.mov/title/tv/1396/"))
            val s2=seasons.first{it.sources.first().url.endsWith("season=2")}
            val episodes=VodEpisodes.load(s2.sources.single())
            report.put("seasons",seasons.size).put("season2Episodes",episodes.size).put("episode2",episodes[1].title)
            assertEquals(13,episodes.size)
            assertTrue(VodCatalogue.playbackPage(episodes[1].sources.single()).endsWith("/tv/watch/tv/1396?s=2&e=2"))
        }
        File(inst.targetContext.getExternalFilesDir(null),"search-seasons.json").writeText(report.toString(2))
    }
    @Test fun duloPlaybackHasNativeSeasonMenu(){
        optIn()
        val intent=Intent(inst.targetContext,VodPlayerActivity::class.java).putExtra("provider","DULO").putExtra("url","https://dulo.mov/tv/watch/tv/1396?s=2&e=2").putExtra("title","Breaking Bad S2E2")
        ActivityScenario.launch<VodPlayerActivity>(intent).use { scenario ->
            val device=UiDevice.getInstance(inst)
            Thread.sleep(15000)
            device.pressMenu()
            assertTrue(device.wait(Until.hasObject(By.text("Choose season / episode")),5000))
            device.findObject(By.text("Choose season / episode")).click()
            assertTrue(device.wait(Until.hasObject(By.textContains("Season 3")),20000))
            device.findObject(By.textContains("Season 3")).click()
            assertTrue(device.wait(Until.hasObject(By.textContains("S3 E1")),20000))
            device.takeScreenshot(File(inst.targetContext.getExternalFilesDir(null),"dulo-season3.png"))
            device.findObject(By.textContains("S3 E1 ·")).click()
            Thread.sleep(5000)
            var url="";scenario.onActivity{url=it.web.url.orEmpty()}
            assertTrue(url,url.contains("s=3&e=1"))
            File(inst.targetContext.getExternalFilesDir(null),"dulo-season-change.json").writeText(JSONObject().put("url",url).toString())
        }
    }
    @Test fun duloSelectedEpisodeActuallyPlays(){
        optIn()
        val args=InstrumentationRegistry.getArguments()
        val series=args.getString("seriesId","1396");val season=args.getString("season","3");val episode=args.getString("episode","1")
        require(listOf(series,season,episode).all { it.matches(Regex("[0-9]+")) })
        val intent=Intent(inst.targetContext,VodPlayerActivity::class.java).putExtra("provider","DULO").putExtra("url","https://dulo.mov/tv/watch/tv/$series?s=$season&e=$episode").putExtra("title",args.getString("title","Breaking Bad S3E1"))
        ActivityScenario.launch<VodPlayerActivity>(intent).use { scenario ->
            var report=JSONObject()
            fun sample(){
                val latch=java.util.concurrent.CountDownLatch(1)
                scenario.onActivity { a->
                    val p=a.nativePlayback?.player
                    if(p!=null){report=JSONObject().put("native",true).put("time",p.currentPosition/1000.0).put("frames",p.videoDecoderCounters?.renderedOutputBufferCount?:0).put("playing",p.isPlaying).put("height",p.videoSize.height).put("diagnostic",a.nativePlayback?.diagnostic);latch.countDown()}
                    else a.web.evaluateJavascript("""(()=>{const v=document.querySelector('video');return JSON.stringify({native:false,time:v?.currentTime||0,frames:v?.getVideoPlaybackQuality?.().totalVideoFrames||0,playing:!!v&&!v.paused,height:v?.videoHeight||0,text:document.body.innerText.slice(-500)})})()"""){raw->runCatching{report=JSONObject(org.json.JSONTokener(raw).nextValue().toString())};latch.countDown()}
                }
                latch.await(5,java.util.concurrent.TimeUnit.SECONDS)
            }
            val deadline=System.currentTimeMillis()+args.getString("startupTimeoutMs","100000").toLong().coerceIn(10000,100000)
            while(System.currentTimeMillis()<deadline){sample();if(report.optBoolean("playing")&&report.optInt("frames")>120)break;Thread.sleep(1000)}
            File(inst.targetContext.getExternalFilesDir(null),"dulo-playback.json").writeText(report.toString(2))
            assertTrue(report.toString(),report.optBoolean("playing")&&report.optInt("frames")>120)
            val before=report.optDouble("time");Thread.sleep(10000);sample()
            File(inst.targetContext.getExternalFilesDir(null),"dulo-playback.json").writeText(report.toString(2))
            assertTrue(report.toString(),report.optDouble("time")>before+6)
        }
    }

    @Test fun cachedLibraryReopensWithoutAnotherDownload(){
        optIn()
        var count=0
        ActivityScenario.launch(OnDemandActivity::class.java).use { scenario ->
            val deadline=System.currentTimeMillis()+60000;var busy=true
            while(System.currentTimeMillis()<deadline){scenario.onActivity{busy=it.isLoading;count=it.catalogue.titles.size};if(!busy)break;Thread.sleep(500)}
            assertTrue("Expected browse titles",count>0);assertFalse("Catalogue should finish",busy)
            Thread.sleep(500)
        }
        val started=System.currentTimeMillis()
        ActivityScenario.launch(OnDemandActivity::class.java).use { scenario ->
            var busy=true;var cached=0;var series=0
            scenario.onActivity{busy=it.isLoading;cached=it.catalogue.titles.size;series=it.catalogue.titles.count{t->t.kind=="show"}}
            assertFalse("Fresh cache should not start another catalogue job",busy)
            assertTrue(cached>0);assertTrue("Series must be available to browse",series>0)
            File(inst.targetContext.getExternalFilesDir(null),"vod-cache.json").writeText(JSONObject().put("reopenMs",System.currentTimeMillis()-started).put("titles",cached).put("series",series).put("loading",busy).toString(2))
        }
    }

}
