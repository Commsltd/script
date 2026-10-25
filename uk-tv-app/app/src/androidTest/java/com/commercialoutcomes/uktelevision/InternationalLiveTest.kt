package com.commercialoutcomes.uktelevision

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class InternationalLiveTest {
    @Test fun broadcasterPlayerRunsInsideUkTv() {
        val args=InstrumentationRegistry.getArguments();assumeTrue(args.getString("liveChecks")=="true")
        val id=args.getString("station","TTT.tt");val context=InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<LiveWebActivity>(Intent(context,LiveWebActivity::class.java).putExtra("station",id).putExtra("title",id)).use { scenario ->
            var output=JSONObject();var previous=-1.0;var advancing=0;val end=System.currentTimeMillis()+90000
            while(System.currentTimeMillis()<end) {
                val latch=CountDownLatch(1)
                scenario.onActivity { it.web.evaluateJavascript("""(()=>{const v=document.querySelector('video');return JSON.stringify({time:v?.currentTime||0,width:v?.videoWidth||0,frames:v?.getVideoPlaybackQuality?.().totalVideoFrames||0,paused:v?.paused??true,text:document.body.innerText.slice(-1400),buttons:[...document.querySelectorAll('button,[role="button"]')].map(b=>({text:b.textContent.trim(),label:b.getAttribute('aria-label'),id:b.id})).slice(-30)})})()""") { raw->runCatching { output=JSONObject(JSONTokener(raw).nextValue().toString()) };latch.countDown() } }
                latch.await(5,TimeUnit.SECONDS)
                if(output.optInt("width")>0 && !output.optBoolean("paused",true) && output.optDouble("time")>previous && output.optInt("frames")>20)advancing++ else advancing=0
                previous=output.optDouble("time")
                scenario.onActivity { output.put("errors",it.pageErrors.toString()) }
                File(context.getExternalFilesDir(null),"live-web-$id.json").writeText(output.put("advancing",advancing).toString(2))
                if(advancing>=10)break
                Thread.sleep(1000)
            }
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(context.getExternalFilesDir(null),"live-web-$id.png"))
            assertTrue("No advancing broadcaster picture: $output",advancing>=10)
        }
    }
}
