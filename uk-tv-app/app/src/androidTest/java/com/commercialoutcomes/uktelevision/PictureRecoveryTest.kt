package com.commercialoutcomes.uktelevision

import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PictureRecoveryTest {
    @Test fun missingPictureAdvancesToBackupAndPauseDoesNot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val host = InstrumentationRegistry.getArguments().getString("fixtureHost") ?: "10.0.2.2"
        val station = Station("PictureFixture.local", "Picture recovery test", "PictureFixture.local", "TEST", 0, "", "",
            """[{"id":"picture-primary","url":"http://$host:8765/stream.m3u8"},{"id":"picture-backup","url":"http://$host:8765/stream.m3u8"}]""", "")
        val prefs = context.getSharedPreferences("preferences", 0)
        val originalLast = prefs.getString("lastChannel", null)
        val originalFallback = prefs.getBoolean("autoFallback", true)
        val hadFallback = prefs.contains("autoFallback")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var engine: PlaybackEngine
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[TvModel::class.java]
                    model.setFailover(true)
                    engine = MainActivity::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(activity) as PlaybackEngine
                    MainActivity::class.java.getDeclaredMethod("startPlayback", GuideRow::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(activity, GuideRow(station, station.sources().first(), 0), true, true)
                }
                fun waitFor(timeout: Long, condition: () -> Boolean): Boolean {
                    val end = System.currentTimeMillis() + timeout
                    while (System.currentTimeMillis() < end) {
                        var ok = false
                        scenario.onActivity { ok = condition() }
                        if (ok) return true
                        Thread.sleep(200)
                    }
                    return false
                }
                assertTrue("Fixture video did not start", waitFor(20000) { (engine.player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 25 })
                scenario.onActivity { engine.pauseOrPlay() }
                Thread.sleep(8000)
                scenario.onActivity { assertEquals("picture-primary", engine.source?.id); engine.pauseOrPlay() }
                assertTrue(waitFor(10000) { engine.player?.isPlaying == true })
                // Disable the video renderer while leaving audio running, reproducing
                // sound without a picture without relying on a broken internet feed.
                scenario.onActivity { engine.player!!.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build() } }
                val start = System.currentTimeMillis()
                assertTrue("Audio-only source was not replaced", waitFor(18000) {
                    engine.source?.id == "picture-backup" && (engine.player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 25
                })
                val recoveredIn = System.currentTimeMillis() - start
                var before = 0
                scenario.onActivity { before = engine.player!!.videoDecoderCounters!!.renderedOutputBufferCount }
                Thread.sleep(5000)
                scenario.onActivity {
                    assertTrue("Replacement picture did not keep advancing", engine.player!!.videoDecoderCounters!!.renderedOutputBufferCount > before + 25)
                    File(context.getExternalFilesDir(null), "picture-recovery.json").writeText(JSONObject().put("recoveredInMs", recoveredIn).put("pausePreservedSource", true).put("videoFrames", engine.player!!.videoDecoderCounters!!.renderedOutputBufferCount).put("source", engine.source!!.id).toString())
                }
                UiDevice.getInstance(instrumentation).takeScreenshot(File(context.getExternalFilesDir(null), "picture-recovery.png"))
                scenario.onActivity { engine.close() }
            }
        } finally {
            prefs.edit().apply {
                if (originalLast == null) remove("lastChannel") else putString("lastChannel", originalLast)
                if (hadFallback) putBoolean("autoFallback", originalFallback) else remove("autoFallback")
            }.commit()
            (context.applicationContext as TvApplication).database.openHelper.writableDatabase.execSQL("DELETE FROM stream_health WHERE streamId IN ('picture-primary','picture-backup')")
        }
    }
}
