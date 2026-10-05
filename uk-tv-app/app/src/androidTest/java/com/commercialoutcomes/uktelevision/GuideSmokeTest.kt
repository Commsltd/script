package com.commercialoutcomes.uktelevision

import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class GuideSmokeTest {
    private fun eventually(timeout:Long=45000, condition:()->Boolean) {
        val deadline = System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<deadline) {
            if(condition()) return
            Thread.sleep(250)
        }
        assertTrue("Condition did not become true before timeout",condition())
    }
    @Test fun realGuideDescriptionsAndRemoteMenusWork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue("Guide header did not render",device.wait(Until.hasObject(By.text("UK TELEVISION")),30000))
            var loaded=false
            lateinit var model: TvModel
            eventually {
                scenario.onActivity { activity ->
                    model = ViewModelProvider(activity)[TvModel::class.java]
                    loaded = model.stations.size>100 && model.schedule.values.sumOf { it.size }>1000 && model.snapshot!=null
                }
                loaded
            }
            var synopsis=""
            scenario.onActivity {
                val described = model.schedule.values.flatten().first { it.description.isNotBlank() }
                val station = model.stations.first { it.id==described.channelId }
                model.focus(station,described)
                synopsis=described.description
            }
            assertTrue("The synopsis was not rendered",device.wait(Until.hasObject(By.text(synopsis)),10000))
            val directory = instrumentation.targetContext.getExternalFilesDir(null)!!
            device.takeScreenshot(File(directory,"guide.png"))
            device.pressDPadDown(); device.pressDPadRight(); device.waitForIdle()
            device.pressMenu()
            assertTrue("Remote menu did not open",device.wait(Until.hasObject(By.text("Television options")),10000))
            assertTrue(device.hasObject(By.text("Search")))
            device.takeScreenshot(File(directory,"options.png"))
            device.pressBack()
            scenario.onActivity { model.railSelected=true; model.selectGroup(model.groups().last()) }
            device.pressDPadUp(); device.waitForIdle()
        }
    }
    @Test fun hlsPlaybackFallsBackAfterHttpFailureAndRecordsSuccess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var engine: PlaybackEngine
            lateinit var model: TvModel
            scenario.onActivity { activity ->
                model=ViewModelProvider(activity)[TvModel::class.java]
                engine=MainActivity::class.java.getDeclaredField("engine").apply { isAccessible=true }.get(activity) as PlaybackEngine
                model.setProfile("Instrumentation fixture")
                model.setFailover(true)
                val station=Station("TestHLS.local","Playback test fixture","TestHLS.local","TEST",0,"","",
                    """[{"id":"fixture-missing","url":"http://10.0.2.2:8765/missing.m3u8","host":"emulator fixture","mime":"application/x-mpegURL","label":"Deliberate HTTP 404"},
                    {"id":"fixture-working","url":"http://10.0.2.2:8765/stream.m3u8","host":"emulator fixture","mime":"application/x-mpegURL","label":"Generated HLS test video"}]""","")
                engine.play(GuideRow(station,station.sources()[0],0))
            }
            var playing=false
            eventually {
                scenario.onActivity { playing=engine.source?.id=="fixture-working" && engine.status.startsWith("Playing") && engine.player?.isPlaying==true }
                playing
            }
            device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null),"playback-test.png"))
            eventually(20000) {
                runBlocking { model.dao.health("Instrumentation fixture").any { it.streamId=="fixture-working" && it.successes>0 } }
            }
            val health=runBlocking { model.dao.health("Instrumentation fixture") }
            assertTrue("Failed source was not recorded",health.any { it.streamId=="fixture-missing" && it.failures>0 })
            assertTrue("Working source was not recorded",health.any { it.streamId=="fixture-working" && it.lastOk>0 })
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("UK TELEVISION")),10000))
        }
    }
}
