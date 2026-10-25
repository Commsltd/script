package com.commercialoutcomes.uktelevision

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.MutableState
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SurfaceTransitionTest {
    @Test fun switchingChannelThenEnteringFullscreenKeepsRendering() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val host = InstrumentationRegistry.getArguments().getString("fixtureHost") ?: "10.0.2.2"
        val prefs = context.getSharedPreferences("preferences", 0)
        val last = prefs.getString("lastChannel", null)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var engine: PlaybackEngine
                fun start(index: Int, full: Boolean) {
                    scenario.onActivity { activity ->
                        engine = MainActivity::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(activity) as PlaybackEngine
                        val station = Station("Surface$index.local", "Surface test $index", "Surface.local", "TEST", 0, "", "",
                            """[{"id":"surface-$index","url":"http://$host:8765/stream.m3u8"}]""", "")
                        MainActivity::class.java.getDeclaredMethod("startPlayback", GuideRow::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                            .apply { isAccessible = true }.invoke(activity, GuideRow(station, station.sources().single(), 0), full, true)
                    }
                }
                fun surface(value: TvSurface) {
                    scenario.onActivity { activity ->
                        @Suppress("UNCHECKED_CAST")
                        val state = MainActivity::class.java.getDeclaredField("surface\$delegate").apply { isAccessible = true }.get(activity) as MutableState<TvSurface>
                        state.value = value
                    }
                }
                fun advancing(label: String) {
                    val end = System.currentTimeMillis() + 15000
                    var frames = 0
                    while (System.currentTimeMillis() < end && frames < 30) {
                        scenario.onActivity { frames = engine.player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0 }
                        Thread.sleep(200)
                    }
                    assertTrue("$label never rendered video: $frames", frames >= 30)
                    Thread.sleep(3500)
                    scenario.onActivity { assertTrue("$label lost its video surface", (engine.player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > frames + 30) }
                }
                start(1, false); advancing("Initial preview")
                surface(TvSurface.LIVE); advancing("Initial full screen")
                surface(TvSurface.GUIDE)
                Thread.sleep(300)
                start(2, false)
                Thread.sleep(150)
                surface(TvSurface.LIVE)
                advancing("Changed channel full screen")
                surface(TvSurface.GUIDE); advancing("Return to preview")
                scenario.onActivity { engine.close() }
            }
        } finally {
            prefs.edit().apply { if (last == null) remove("lastChannel") else putString("lastChannel", last) }.commit()
            (context.applicationContext as TvApplication).database.openHelper.writableDatabase.execSQL("DELETE FROM stream_health WHERE streamId IN ('surface-1','surface-2')")
        }
    }
}
