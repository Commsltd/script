package com.commercialoutcomes.uktelevision

import android.os.SystemClock
import android.view.KeyEvent
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
    private fun eventually(timeout: Long = 45000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(250)
        }
        assertTrue("Condition did not become true before timeout", condition())
    }

    private fun longPressSelect(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val method = MainActivity::class.java.getDeclaredMethod(
                "handleRemoteKey",
                KeyEvent::class.java
            ).apply { isAccessible = true }
            val down = SystemClock.uptimeMillis()
            method.invoke(
                activity,
                KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0)
            )
            Thread.sleep(650)
            method.invoke(
                activity,
                KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0)
            )
        }
    }

    @Test
    fun guidePreviewFavouritesSourcesNavigationAndLiveOverlayWork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val directory = instrumentation.targetContext.getExternalFilesDir(null)!!

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(
                "Guide header did not render",
                device.wait(Until.hasObject(By.text("UK TELEVISION")), 30000)
            )

            lateinit var model: TvModel
            eventually {
                var loaded = false
                scenario.onActivity { activity ->
                    model = ViewModelProvider(activity)[TvModel::class.java]
                    loaded =
                        model.stations.size > 100 &&
                        model.schedule.values.sumOf { it.size } > 1000 &&
                        model.snapshot != null
                }
                loaded
            }

            var stationId = ""
            var synopsis = ""
            scenario.onActivity {
                val now = System.currentTimeMillis()
                val current = model.schedule.values.flatten().firstOrNull {
                    it.start <= now && it.stop > now && it.description.isNotBlank() && model.stations.any { s -> s.id == it.channelId }
                } ?: model.schedule.values.flatten().first {
                    it.description.isNotBlank() && model.stations.any { s -> s.id == it.channelId }
                }
                val station = model.stations.first { it.id == current.channelId }
                stationId = station.id
                synopsis = current.description
                model.focus(station, current)
                if (!(current.start <= now && current.stop > now)) model.now()
                model.chooseGuideZone(GuideZone.PROGRAMMES)
            }

            assertTrue(
                "Programme synopsis was not rendered",
                device.wait(Until.hasObject(By.text(synopsis)), 10000)
            )
            device.takeScreenshot(File(directory, "guide.png"))

            // BACK from programme cells must return to the channel column without
            // changing channel or throwing the user backwards through the EPG.
            device.pressBack()
            eventually {
                var ok = false
                scenario.onActivity {
                    ok = model.guideZone == GuideZone.CHANNELS &&
                        model.selected()?.station?.id == stationId
                }
                ok
            }
            device.pressDPadRight()
            eventually {
                var ok = false
                scenario.onActivity { ok = model.guideZone == GuideZone.PROGRAMMES }
                ok
            }

            // OK on a live channel starts persistent preview in the guide.
            scenario.onActivity { model.now() }
            device.pressDPadCenter()
            eventually {
                var ok = false
                scenario.onActivity { ok = model.playingRow?.station?.id == stationId }
                ok
            }
            assertTrue(
                "Live preview did not appear in the guide",
                device.wait(Until.hasObject(By.textContains("LIVE PREVIEW")), 10000)
            )

            // Repeatable on a previously used emulator: a prior run may have
            // already saved this station, changing the menu to Remove favourite.
            runBlocking { model.dao.removeFavourite(stationId) }
            eventually {
                var absent = false
                scenario.onActivity { absent = stationId !in model.favourites }
                absent
            }
            // Favourite is a first-class visible action.
            device.pressMenu()
            assertTrue(
                "Options menu did not open",
                device.wait(Until.hasObject(By.textContains("Add favourite")), 10000)
            )
            device.takeScreenshot(File(directory, "options.png"))
            device.findObject(By.textContains("Add favourite")).click()
            eventually {
                var favourite = false
                scenario.onActivity { favourite = stationId in model.favourites }
                favourite
            }

            // Press-and-hold must open source selection, and closing it must
            // restore remote focus rather than freezing the app.
            longPressSelect(scenario)
            assertTrue(
                "Long press did not open stream sources",
                device.wait(Until.hasObject(By.textContains("sources")), 10000)
            )
            device.pressBack()
            device.waitForIdle()
            var before = ""
            scenario.onActivity { before = model.selectedKey }
            device.pressDPadDown()
            eventually {
                var moved = false
                scenario.onActivity { moved = model.selectedKey != before }
                moved
            }

            // Return to the previewed station and enter full-screen TV.
            scenario.onActivity {
                val station = model.stations.first { it.id == stationId }
                model.focus(station)
                model.now()
            }
            device.pressDPadCenter()
            assertTrue(
                "Full-screen info overlay did not appear",
                device.wait(Until.hasObject(By.textContains("Quick guide")), 10000)
            )
            device.pressDPadUp()
            assertTrue(
                "Quick guide did not overlay live TV",
                device.wait(Until.hasObject(By.text("QUICK GUIDE")), 10000)
            )
            device.takeScreenshot(File(directory, "quick-guide.png"))

            // BACK closes overlay first, then returns to the full guide.
            device.pressBack()
            device.pressBack()
            assertTrue(
                "Back did not return from live TV to the guide",
                device.wait(Until.hasObject(By.text("UK TELEVISION")), 10000)
            )
        }
    }

    @Test
    fun hlsPlaybackFallsBackAfterHttpFailureAndRecordsSuccess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var engine: PlaybackEngine
            lateinit var model: TvModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity)[TvModel::class.java]
                engine = MainActivity::class.java.getDeclaredField("engine")
                    .apply { isAccessible = true }
                    .get(activity) as PlaybackEngine
                model.setProfile("Instrumentation fixture")
                model.setFailover(true)
                val fixtureHost = InstrumentationRegistry.getArguments().getString("fixtureHost") ?: "10.0.2.2"
                val station = Station(
                    "user.fixture-hls", "Playback test fixture", "user.fixture-hls", "TEST", 0, "", "",
                    """[{"id":"fixture-missing","url":"http://$fixtureHost:8765/missing.m3u8","host":"emulator fixture","mime":"application/x-mpegURL","label":"Deliberate HTTP 404"},
                    {"id":"fixture-working","url":"http://$fixtureHost:8765/stream.m3u8","host":"emulator fixture","mime":"application/x-mpegURL","label":"Generated HLS test video"}]""",
                    ""
                )
                model.markPlaying(GuideRow(station, station.sources()[0], 0))
                engine.play(GuideRow(station, station.sources()[0], 0))
            }

            eventually {
                var playing = false
                scenario.onActivity {
                    playing =
                        engine.source?.id == "fixture-working" &&
                        engine.status.startsWith("Playing") &&
                        engine.player?.isPlaying == true
                }
                playing
            }

            device.takeScreenshot(
                File(
                    instrumentation.targetContext.getExternalFilesDir(null),
                    "playback-test.png"
                )
            )

            eventually(20000) {
                runBlocking {
                    model.dao.health("Instrumentation fixture")
                        .any { it.streamId == "fixture-working" && it.successes > 0 }
                }
            }
            // Pausing longer than the stall timeout must not punish a healthy stream.
            scenario.onActivity { engine.pauseOrPlay() }
            Thread.sleep(21000)
            scenario.onActivity {
                assertFalse(engine.failed)
                assertEquals("Paused", engine.status)
                engine.pauseOrPlay()
            }
            Thread.sleep(21000)
            scenario.onActivity {
                assertFalse("Resumed stream incorrectly timed out",engine.failed)
                assertTrue("Playback did not resume",engine.player?.isPlaying == true)
            }
            val health = runBlocking { model.dao.health("Instrumentation fixture") }
            assertTrue(
                "Failed source was not recorded",
                health.any { it.streamId == "fixture-missing" && it.failures > 0 }
            )
            assertTrue(
                "Working source was not recorded",
                health.any { it.streamId == "fixture-working" && it.lastOk > 0 }
            )
        }
    }
    @Test
    fun oneSourceReconnectsAfterTemporaryProviderFailure() {
        val host = InstrumentationRegistry.getArguments().getString("fixtureHost") ?: "10.0.2.2"
        (java.net.URL("http://$host:8765/reset-flaky").openConnection() as java.net.HttpURLConnection).run {
            assertEquals(204,responseCode); disconnect()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var engine: PlaybackEngine
            lateinit var model: TvModel
            scenario.onActivity { a ->
                model = ViewModelProvider(a)[TvModel::class.java]
                engine = MainActivity::class.java.getDeclaredField("engine").apply { isAccessible=true }.get(a) as PlaybackEngine
                model.setProfile("Recovery fixture")
                val station = Station("user.fixture-recovery","Recovery fixture","user.fixture-recovery","TEST",0,"","",
                    """[{"id":"fixture-recovery","url":"http://$host:8765/flaky.m3u8","host":"fixture","mime":"application/x-mpegURL"}]""","")
                val row = GuideRow(station,station.sources()[0],0)
                model.markPlaying(row);engine.play(row)
            }
            eventually(60000) {
                var ready=false
                scenario.onActivity { ready=engine.player?.isPlaying==true && engine.status.startsWith("Playing") }
                ready
            }
            eventually(20000) {
                runBlocking { model.dao.health("Recovery fixture").any { it.streamId=="fixture-recovery" && it.failures>0 && it.successes>0 } }
            }
            val health=runBlocking { model.dao.health("Recovery fixture") }.first { it.streamId=="fixture-recovery" }
            assertEquals("HTTP_503",health.lastError)
            scenario.onActivity { assertEquals("fixture-recovery",engine.source?.id);assertFalse(engine.failed) }
        }
    }

    @Test fun temporaryErrorsSwitchThroughAllRegionsBeforeRetryingTheBrokenFeed() {
        val host = InstrumentationRegistry.getArguments().getString("fixtureHost") ?: "10.0.2.2"
        (java.net.URL("http://$host:8765/reset-flaky").openConnection() as java.net.HttpURLConnection).run {
            assertEquals(204,responseCode); disconnect()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var engine: PlaybackEngine
            lateinit var model: TvModel
            val start = System.currentTimeMillis()
            scenario.onActivity { a ->
                model = ViewModelProvider(a)[TvModel::class.java]
                model.setProfile("Regional fallback fixture"); model.setFailover(true)
                engine = MainActivity::class.java.getDeclaredField("engine").apply { isAccessible=true }.get(a) as PlaybackEngine
                val station = Station("user.fixture-regional","BBC One","BBCOne.uk","TEST",0,"","",
                    """[{"id":"regional-default","url":"http://$host:8765/flaky.m3u8","region":"Default","priority":0},
                    {"id":"regional-missing","url":"http://$host:8765/missing.m3u8","region":"Region A","priority":1},
                    {"id":"regional-working","url":"http://$host:8765/stream.m3u8","region":"Region B","priority":1}]""","")
                val row = GuideRow(station,station.sources()[0],0)
                model.markPlaying(row);engine.play(row,true)
            }
            eventually(12000) {
                var ready=false
                scenario.onActivity { ready=engine.source?.id=="regional-working" && engine.player?.isPlaying==true }
                ready
            }
            assertTrue("Fallback took too long",System.currentTimeMillis()-start < 12000)
            val health=runBlocking { model.dao.health("Regional fallback fixture") }
            assertTrue(health.any { it.streamId=="regional-default" && it.lastError=="HTTP_503" })
            assertTrue(health.any { it.streamId=="regional-missing" && it.lastError=="HTTP_404" })
            scenario.onActivity { assertEquals("Playing · Region B",engine.status) }
        }
    }

}
