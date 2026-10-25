package com.commercialoutcomes.uktelevision

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.Configurator
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VodStreamLiveTest {
    @Test fun providerServerCanBeChangedWhileStayingInsideUkTv() {
        val args=InstrumentationRegistry.getArguments();assumeTrue(args.getString("liveChecks")=="true")
        Configurator.getInstance().waitForIdleTimeout=0
        val p=VodProvider.valueOf(args.getString("provider","DULO"));val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=VodSource(p,if(p==VodProvider.DULO)"https://dulo.mov/title/movie/278/" else "https://www.movy.sx/movie/278")
        val url=runBlocking { VodCatalogue.playbackPage(source) }
        ActivityScenario.launch<VodPlayerActivity>(Intent(context,VodPlayerActivity::class.java).putExtra("provider",p.name).putExtra("url",url).putExtra("title","The Shawshank Redemption")).use { scenario ->
            fun snapshot():JSONObject {
                val latch=CountDownLatch(1);var output=JSONObject()
                scenario.onActivity { it.web.evaluateJavascript("""(()=>{const v=[...document.querySelectorAll('video')].find(v=>v.duration>1200);return JSON.stringify({url:location.href,time:v?.currentTime||0,width:v?.videoWidth||0,frames:v?.getVideoPlaybackQuality?.().totalVideoFrames||0,paused:v?.paused??true,text:document.body.innerText.slice(-1200),controls:[...document.querySelectorAll('[aria-label]')].map(e=>({tag:e.tagName,role:e.getAttribute('role'),label:e.getAttribute('aria-label')})).slice(-60)})})()""") { raw->runCatching { output=JSONObject(JSONTokener(raw).nextValue().toString()) };latch.countDown() } }
                latch.await(5,TimeUnit.SECONDS);return output
            }
            fun playing(timeout:Long):JSONObject {
                val end=System.currentTimeMillis()+timeout;var output=JSONObject();var previous=-1.0;var advancing=0
                while(System.currentTimeMillis()<end) {
                    output=snapshot()
                    if(output.optInt("width")>0 && !output.optBoolean("paused",true) && output.optDouble("time")>previous && output.optInt("frames")>20)advancing++ else advancing=0
                    previous=output.optDouble("time")
                    if(advancing>=5)return output
                    Thread.sleep(1000)
                }
                fail("Video did not advance for five consecutive samples: $output")
                return output
            }
            val first=playing(100000);File(context.getExternalFilesDir(null),"stream-$p-before.json").writeText(first.toString(2))
            val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.pressMenu()
            val menu=device.wait(Until.findObject(By.text("Change stream · poor copy or buffering")),5000)
            assertNotNull("MENU must expose Change stream",menu);menu.click()
            val chooser=device.wait(Until.findObject(By.text("Change stream · ${p.label}")),8000)
            assertNotNull("Native stream choices must open",chooser)
            device.takeScreenshot(File(context.getExternalFilesDir(null),"stream-$p-chooser.png"))
            val latch=CountDownLatch(1);lateinit var controls:VodStreamControls;var choices=emptyList<VodStreamControls.Choice>()
            scenario.onActivity { controls=VodStreamControls(it.web,p);controls.list { choices=it;latch.countDown() } }
            assertTrue(latch.await(10,TimeUnit.SECONDS))
            File(context.getExternalFilesDir(null),"stream-$p-controls.json").writeText(snapshot().put("choices",choices.toString()).toString(2))
            assertTrue("Provider exposed ${choices.size} server choices",choices.size>1)
            val choice=choices.firstOrNull { !it.selected && it.label.contains("1080p") } ?: choices.first { !it.selected }
            val button=device.wait(Until.findObject(By.text(choice.label)),5000)
            assertNotNull("The server must be selectable with the remote",button);button.click()
            val second=playing(100000)
            var lastTime=second.optDouble("time");var stalled=0;var longestStall=0
            repeat(45) {
                Thread.sleep(1000)
                val current=snapshot()
                stalled=if(current.optDouble("time")>lastTime && !current.optBoolean("paused",true))0 else stalled+1
                longestStall=maxOf(longestStall,stalled);lastTime=current.optDouble("time")
            }
            second.put("timeAfter45Seconds",lastTime).put("longestStallSeconds",longestStall)
            File(context.getExternalFilesDir(null),"stream-$p-after.json").writeText(second.toString(2))
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(context.getExternalFilesDir(null),"stream-$p-after.png"))
            assertTrue("Replacement video did not play: $second",second.optInt("width")>0 && second.optDouble("time")>3 && !second.optBoolean("paused",true))
            assertTrue("Replacement video stalled: $second",longestStall<8 && lastTime>second.optDouble("time")+30)
            scenario.onActivity { controls.close() }
        }
    }
}
