package com.commercialoutcomes.uktelevision

import android.view.KeyEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in: internet providers are not a deterministic CI fixture. */
@RunWith(AndroidJUnit4::class)
class LiveUpgradeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun optIn() = assumeTrue(InstrumentationRegistry.getArguments().getString("liveChecks") == "true")
    private fun save(name: String, value: String) = File(instrumentation.targetContext.getExternalFilesDir(null),name).writeText(value)
    @Test fun youtubeLivePlaybackAndRemotePauseResume() {
        optIn()
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var controller: YouTubeController
            scenario.onActivity { a ->
                val model = ViewModelProvider(a)[TvModel::class.java]
                val engine = MainActivity::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(a) as PlaybackEngine
                controller = engine.youtube
                val station = Station("SkyNews.uk@Test", "Sky News YouTube test", "SkyNews.uk", "TEST", 0, "", "", """[{"id":"sky-live-test","kind":"youtube","url":"https://www.youtube.com/@SkyNews/live","livePage":"https://www.youtube.com/@SkyNews/live","label":"Sky News official YouTube","host":"www.youtube.com"}]""", "")
                MainActivity::class.java.getDeclaredMethod("startPlayback",GuideRow::class.java,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                    .invoke(a,GuideRow(station,station.sources()[0],0),true,false)
            }
            fun waitFor(timeout: Long, condition: () -> Boolean): Boolean {
                val end = System.currentTimeMillis()+timeout
                while(System.currentTimeMillis()<end) {
                    var result=false
                    scenario.onActivity { result=condition() }
                    if(result)return true
                    Thread.sleep(250)
                }
                return false
            }
            waitFor(150000) { controller.playing || controller.failed }
            device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null),"youtube-live.png"))
            var status="";var started=false
            scenario.onActivity { status=controller.status;started=controller.playing;save("youtube-live.json",JSONObject().put("status",status).put("playing",started).put("videoId",controller.videoId).toString(2)) }
            scenario.onActivity {
                val web = YouTubeController::class.java.getDeclaredField("view").apply { isAccessible=true }.get(controller) as? android.webkit.WebView
                web?.evaluateJavascript("JSON.stringify({state:window.player&&player.getPlayerState?player.getPlayerState():null,iframe:document.querySelector('iframe')&&document.querySelector('iframe').src,body:document.body.innerText})") { save("youtube-debug.json",it) }
                save("youtube-events.json",controller.events.joinToString("\n"))
            }
            Thread.sleep(1500)
            assertTrue("YouTube did not play: $status", started)
            device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            assertTrue("Remote pause failed",waitFor(10000) { !controller.playing && controller.status.startsWith("Paused") })
            device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            assertTrue("Remote resume failed",waitFor(10000) { controller.playing })
            Thread.sleep(30000)
            device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null),"youtube-sustained.png"))
            scenario.onActivity { assertTrue("YouTube stopped: ${controller.status}",controller.playing);save("youtube-live.json",JSONObject().put("status",controller.status).put("playing",true).put("videoId",controller.videoId).put("remotePauseResume",true).put("sustainedSeconds",30).toString(2)) }
        }
    }
    @Test fun channelReachabilityOnDevice() {
        optIn()
        val results=JSONArray()
        val urls=listOf(
            "http://45.14.84.37/itv1/index.m3u8",
            "https://lb.iptvnetwork.net/ls28/10104/playlist.m3u8",
            "https://lb.iptvnetwork.net/ls28/10105/playlist.m3u8",
            "https://viamotionhsi.netplus.ch/live/eds/itv/browser-HLS8/itv.m3u8",
            "https://viamotionhsi.netplus.ch/live/eds/channel4/browser-HLS8/channel4.m3u8",
            "https://viamotionhsi.netplus.ch/live/eds/channel5/browser-HLS8/channel5.m3u8")
        for(url in urls) {
            val s=StreamSource(url,url,url,"","application/x-mpegURL",emptyMap(),false)
            val result=runBlocking { ChannelDiagnostics.check(s) }
            results.put(JSONObject().put("url",url).put("result",result))
            save("channel-checks.json",results.toString(2))
        }
    }
}
