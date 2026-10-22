package com.commercialoutcomes.uktelevision

import android.content.Intent
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TubiRemoteTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val device get()=UiDevice.getInstance(instrumentation)
    private fun launch():ActivityScenario<VodPlayerActivity> {
        androidx.test.uiautomator.Configurator.getInstance().waitForIdleTimeout=500
        device.wakeUp();device.pressHome()
        // Fire OS defers app switches briefly after Home; do not race that window.
        Thread.sleep(5500)
        return ActivityScenario.launch<VodPlayerActivity>(Intent(context,VodPlayerActivity::class.java)
            .putExtra("provider","TUBI").putExtra("url","https://tubitv.com/movies/467588/the-man-from-earth").putExtra("title","The Man from Earth"))
    }
    private fun js(s:ActivityScenario<VodPlayerActivity>,expression:String):String {
        val latch=CountDownLatch(1);var result=""
        s.onActivity{it.web.evaluateJavascript(expression){v->result=v;latch.countDown()}}
        assertTrue(latch.await(8,TimeUnit.SECONDS));return result
    }
    private fun state(s:ActivityScenario<VodPlayerActivity>)=runCatching{JSONTokener(js(s,"window.ukTvTubi?.snapshot()")).nextValue() as JSONObject}.getOrDefault(JSONObject())
    private fun await(timeout:Long=12000,check:()->Boolean) {val end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){if(check())return;Thread.sleep(200)};assertTrue("Condition was not reached",check())}
    private fun leavePlayer(s:ActivityScenario<VodPlayerActivity>) {
        device.pressMenu();assertTrue(device.wait(Until.hasObject(By.text("UK TV · Tubi")),5000))
        device.pressBack();assertTrue(device.wait(Until.gone(By.text("UK TV · Tubi")),5000))
        // Wait for each actual UI transition; a burst of Back events can all
        // reach the outgoing dialog during its close animation on Fire OS.
        if(device.hasObject(By.text("Subtitles on / off"))) {
            device.pressBack();assertTrue(device.wait(Until.gone(By.text("Subtitles on / off")),5000))
        }
        device.pressBack()
        await(15000){s.state==androidx.lifecycle.Lifecycle.State.DESTROYED}
    }
    @Test fun remoteFitsPlayerAndDoesNotSubmitConsentAutomatically() {
        launch().use { s ->
            s.onActivity { a->a.web.stopLoading();a.web.loadDataWithBaseURL("https://tubitv.com/","""
                <html><body><header>Website header</header><article style="overflow:hidden;width:50%"><section><div><video data-id="videoPlayerComponent"></video><div data-id="adComponent"></div></div><div id="overlay"></div><button aria-label="Play keyboard shortcut k" id="play">Play</button></section><aside>Recommendations</aside></article>
                <div role="dialog"><h1>Privacy fixture</h1><p>No real consent settings are changed.</p><button onclick="window.choice='accept';this.parentElement.remove()">Allow All</button><button onclick="window.choice='decline';this.parentElement.remove()">No Thanks</button></div>
                <script>window.choice='unset';window.keys=[];window.toggles=0;window.seeks=0;window.p=true;let v=document.querySelector('video'),b=document.querySelector('#play');Object.defineProperties(v,{paused:{get:()=>p},duration:{get:()=>600},readyState:{get:()=>2},videoWidth:{get:()=>1280}});function toggle(){p=!p;toggles++;b.setAttribute('aria-label',(p?'Play':'Pause')+' keyboard shortcut k');}b.__reactProps_fixture={};b.onclick=toggle;const overlay=document.querySelector('#overlay');overlay.__reactProps_fixture={onClick:()=>{},onMouseMove:()=>{}};overlay.onmousemove=()=>{if(!b.isConnected)overlay.appendChild(b)};window.addEventListener('keydown',e=>{window.keys.push([e.key,e.keyCode]);if(e.keyCode===75)toggle();if(e.keyCode===76)seeks++;if(e.keyCode===74)seeks--;});</script></body></html>
            ""","text/html","UTF-8","https://tubitv.com/") }
            assertTrue(device.wait(Until.hasObject(By.text("Tubi · player notice")),30000))
            assertEquals("\"unset\"",js(s,"window.choice"));assertEquals("0",js(s,"window.toggles"))
            // Reach the decline action solely with the remote, without a pointer.
            var steps=0
            while(device.findObject(By.text("No Thanks"))?.isFocused!=true&&steps++<12)device.pressDPadDown()
            assertTrue(device.findObject(By.text("No Thanks"))?.isFocused==true)
            device.pressDPadCenter()
            await{js(s,"window.choice")=="\"decline\""}
            await{state(s).optBoolean("ready")&&js(s,"window.toggles")=="1"}
            assertTrue(device.wait(Until.gone(By.textContains("Opening Tubi")),5000))
            val geometry=JSONObject(JSONTokener(js(s,"(()=>{let v=document.querySelector('video'),r=v.getBoundingClientRect();return {w:r.width,h:r.height,vw:innerWidth,vh:innerHeight,header:getComputedStyle(document.querySelector('header')).opacity}})()")).nextValue().toString())
            assertEquals(geometry.getDouble("vw"),geometry.getDouble("w"),2.0)
            assertEquals(geometry.getDouble("vh"),geometry.getDouble("h"),2.0)
            assertEquals("0",geometry.getString("header"))
            // A held media button must toggle once, and key-up must not click again.
            s.onActivity { a->a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));a.dispatchKeyEvent(KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,3));a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) }
            try { await{js(s,"window.toggles")=="2"} } catch(e:AssertionError){throw AssertionError(js(s,"({toggles:window.toggles,keys:window.keys,state:window.ukTvTubi.snapshot()})"),e)}
            device.pressBack();Thread.sleep(300)
            s.onActivity{assertFalse("Back hides controls before leaving the player",it.isFinishing)}
            // Remove web buttons entirely, as Tubi does when its controls fade out.
            js(s,"document.querySelector('#play').remove()")
            device.pressDPadRight();await{js(s,"window.seeks")=="1"}
            device.pressDPadUp();device.pressDPadCenter();await{js(s,"window.toggles")=="3"}
            leavePlayer(s)
        }
    }
    @Test fun providerAutoplayDoesNotOverrideALaterUserPause() {
        launch().use { s ->
            s.onActivity { a->a.web.stopLoading();a.web.loadDataWithBaseURL("https://tubitv.com/","""
                <html><body><section><video data-id="videoPlayerComponent"></video><button id="play" aria-label="Pause keyboard shortcut k">Pause</button></section>
                <script>window.p=false;window.toggles=0;const v=document.querySelector('video'),b=document.querySelector('#play');Object.defineProperties(v,{paused:{get:()=>p},readyState:{get:()=>2},duration:{get:()=>600},videoWidth:{get:()=>1280}});b.__reactProps_fixture={};b.onclick=()=>{p=!p;toggles++;b.setAttribute('aria-label',(p?'Play':'Pause')+' keyboard shortcut k')};</script></body></html>
            ""","text/html","UTF-8","https://tubitv.com/") }
            assertTrue(device.wait(Until.gone(By.textContains("Opening Tubi")),10000))
            await{state(s).optBoolean("playing")}
            device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE)
            await{js(s,"window.toggles")=="1"&&state(s).optBoolean("paused")}
            Thread.sleep(2500)
            assertEquals("User pause must not be undone by the startup poll","1",js(s,"window.toggles"))
            assertTrue(state(s).getBoolean("paused"))
        }
    }
    @Test fun realTubiMovieSupportsRemotePlaybackWithoutTheWebsite() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveChecks")=="true")
        launch().use { s ->
            await(150000){val p=state(s);p.optBoolean("playing")&&p.optInt("width")>0&&p.optDouble("time")>10}
            val first=state(s).getDouble("time")
            await(25000){state(s).optDouble("time")>first+10}
            device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE)
            await{state(s).optBoolean("paused")}
            val paused=state(s).getDouble("time")
            Thread.sleep(1500);assertEquals(paused,state(s).getDouble("time"),0.5)
            device.takeScreenshot(File(context.getExternalFilesDir(null),"tubi-native-controls.png"))
            device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            await{state(s).optDouble("time")>paused+4}
            val forward=state(s).getDouble("time")
            device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_REWIND)
            await{state(s).optDouble("time")<forward-4}
            // Exercise the remote's centre button on the native Play control.
            val focusBefore=device.findObject(By.focused(true))?.let{it.text+" / "+it.className}.orEmpty()
            js(s,"window.tvKeys=[];document.addEventListener('keydown',e=>window.tvKeys.push([e.key,e.keyCode]),true)")
            device.pressDPadCenter()
            try { await{state(s).optBoolean("playing")} } catch(e:AssertionError) {
                device.takeScreenshot(File(context.getExternalFilesDir(null),"tubi-resume-failure.png"))
                throw AssertionError("Focus before: $focusBefore; after: ${device.findObject(By.focused(true))?.text}; state: ${state(s)} keys: ${js(s,"window.tvKeys")}",e)
            }
            val resumed=state(s).getDouble("time")
            Thread.sleep(7500)
            assertTrue("Playback must stay resumed",state(s).optBoolean("playing")&&state(s).getDouble("time")>resumed+5)
            val geometry=JSONObject(JSONTokener(js(s,"(()=>{let v=document.querySelector('video'),r=v.getBoundingClientRect();return {w:r.width,h:r.height,vw:innerWidth,vh:innerHeight,fit:getComputedStyle(v).objectFit,muted:v.muted,volume:v.volume}})()")).nextValue().toString())
            assertEquals(geometry.getDouble("vw"),geometry.getDouble("w"),2.0)
            assertEquals(geometry.getDouble("vh"),geometry.getDouble("h"),2.0)
            assertEquals("contain",geometry.getString("fit"));assertFalse(geometry.getBoolean("muted"));assertTrue(geometry.getDouble("volume")>0)
            device.takeScreenshot(File(context.getExternalFilesDir(null),"tubi-full-screen.png"))
            File(context.getExternalFilesDir(null),"tubi-remote-result.json").writeText(JSONObject().put("playback",state(s)).put("geometry",geometry).put("pauseSeekResumePassed",true).toString(2))
            leavePlayer(s)
        }
    }
}
