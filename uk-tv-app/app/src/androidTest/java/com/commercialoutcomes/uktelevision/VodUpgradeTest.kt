package com.commercialoutcomes.uktelevision

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VodUpgradeTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private fun optIn()=assumeTrue(InstrumentationRegistry.getArguments().getString("liveChecks")=="true")
    @Test fun duloLoadsMoviesAndSeriesBeyondTheFirstPage(){
        optIn()
        ActivityScenario.launch(OnDemandActivity::class.java).use{scenario->
            var result=VodResult(emptyList(),emptyMap());var done=false
            val deadline=System.currentTimeMillis()+210000
            while(System.currentTimeMillis()<deadline){scenario.onActivity{result=it.catalogue;done=!it.isLoading};if(done)break;Thread.sleep(500)}
            fun count(kind:String)=result.titles.count{it.kind==kind&&it.sources.any{s->s.provider==VodProvider.DULO}}
            val report=JSONObject().put("finished",done).put("movies",count("movie")).put("series",count("show")).put("kinds",result.titles.map{it.kind}.distinct())
            File(inst.targetContext.getExternalFilesDir(null),"dulo-expanded.json").writeText(report.toString(2))
            assertTrue(report.toString(),count("movie")>20)
            assertTrue(report.toString(),count("show")>=20)
            val films=count("movie");val shows=count("show")
            scenario.onActivity{OnDemandActivity::class.java.getDeclaredMethod("more").apply{isAccessible=true}.invoke(it)}
            val moreEnd=System.currentTimeMillis()+120000
            while(System.currentTimeMillis()<moreEnd){scenario.onActivity{result=it.catalogue;done=!it.isLoading};if(done)break;Thread.sleep(500)}
            report.put("moreMovies",count("movie")).put("moreSeries",count("show"))
            File(inst.targetContext.getExternalFilesDir(null),"dulo-expanded.json").writeText(report.toString(2))
            assertTrue(report.toString(),count("movie")>films&&count("show")>shows)
        }
    }
    @Test fun movyNativeEpisodeKeepsPositionAcrossQualityAndMenu(){
        optIn()
        val intent=Intent(inst.targetContext,VodPlayerActivity::class.java).putExtra("provider","MOVY").putExtra("url","https://www.movy.sx/tv/299167/1/1?play=true").putExtra("title","Dutton Ranch S1E1")
        ActivityScenario.launch<VodPlayerActivity>(intent).use{scenario->
            var report=JSONObject()
            fun record(){scenario.onActivity{a->val p=a.nativePlayback?.player;report=JSONObject().put("diagnostic",a.nativePlayback?.diagnostic).put("native",p!=null).put("playing",p?.isPlaying==true).put("time",p?.currentPosition?:0).put("frames",p?.videoDecoderCounters?.renderedOutputBufferCount?:0).put("dropped",p?.videoDecoderCounters?.droppedBufferCount?:0).put("height",p?.videoSize?.height?:0)}}
            val deadline=System.currentTimeMillis()+150000
            while(System.currentTimeMillis()<deadline){record();if(report.optInt("frames")>120)break;Thread.sleep(1000)}
            File(inst.targetContext.getExternalFilesDir(null),"movy-native.json").writeText(report.toString(2))
            assertTrue(report.toString(),report.optBoolean("playing")&&report.optInt("frames")>120)
            assertTrue(report.toString(),report.optInt("height")<=720)
            scenario.onActivity{it.nativePlayback!!.player!!.seekTo(150000)}
            Thread.sleep(5000)
            UiDevice.getInstance(inst).executeShellCommand("input keyevent --longpress 82")
            Thread.sleep(1500)
            assertNotNull(UiDevice.getInstance(inst).findObject(By.text("Picture quality")))
            UiDevice.getInstance(inst).findObject(By.text("Picture quality")).click()
            Thread.sleep(500)
            UiDevice.getInstance(inst).findObject(By.text("480p · lower data use")).click()
            val changeEnd=System.currentTimeMillis()+50000
            while(System.currentTimeMillis()<changeEnd){record();if(report.optBoolean("playing")&&report.optInt("height")==480)break;Thread.sleep(1000)}
            File(inst.targetContext.getExternalFilesDir(null),"movy-native-quality.json").writeText(report.toString(2))
            assertEquals(report.toString(),480,report.optInt("height"))
            assertTrue(report.toString(),report.optLong("time")>=147000)
            Thread.sleep(10000);record()
            File(inst.targetContext.getExternalFilesDir(null),"movy-native-quality.json").writeText(report.toString(2))
            UiDevice.getInstance(inst).takeScreenshot(File(inst.targetContext.getExternalFilesDir(null),"movy-native.png"))
            assertTrue(report.toString(),report.optLong("time")>=157000)
        }
    }
}
