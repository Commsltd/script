package com.commercialoutcomes.uktelevision

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Device/VPN checks are opt-in; no internet result is a deterministic CI test. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BbcRecoveryTest {
    @Test fun defaultAndRegionalFeedsPlayInsideTheApp() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveChecks") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val catalogue = BundledCatalogue.read(context)
        val results = JSONArray()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var engine: PlaybackEngine
            lateinit var model: TvModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity)[TvModel::class.java]
                model.setFailover(true); model.setInline(false)
                engine = MainActivity::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(activity) as PlaybackEngine
            }
            for ((family, region) in listOf("BBCOne.uk" to "", "BBCTwo.uk" to "", "BBCOne.uk" to "Scotland")) {
                val station = catalogue.stations.single { it.family == family }
                val sources = station.sources()
                val target = if (region.isBlank()) sources.first() else sources.first { it.region == region && it.url.contains(".m3u8") }
                val output = JSONObject().put("channel",station.name).put("requestedRegion",region).put("sourceCount",sources.size)
                val attempts = JSONArray()
                var seen = ""; var stableSince = 0L; var played = false
                scenario.onActivity {
                    val row = GuideRow(station, target, sources.indexOf(target))
                    model.markPlaying(row); engine.play(row, true)
                }
                val deadline = System.currentTimeMillis() + 240000
                while (System.currentTimeMillis() < deadline) {
                    var stop = false
                    scenario.onActivity {
                        val source = engine.source
                        if (source != null && seen != source.id) {
                            seen = source.id; stableSince = 0
                            attempts.put(JSONObject().put("label",source.label).put("id",source.id).put("time",System.currentTimeMillis()))
                        }
                        if (engine.player?.isPlaying == true && engine.status.startsWith("Playing")) {
                            if (stableSince == 0L) stableSince = System.currentTimeMillis()
                            played = System.currentTimeMillis()-stableSince >= 20000
                        } else stableSince = 0
                        stop = played || engine.failed
                        output.put("playing",played).put("status",engine.status).put("attempts",attempts)
                            .put("actualRegion",source?.region.orEmpty())
                    }
                    if (stop) break
                    Thread.sleep(300)
                }
                results.put(output)
                File(context.getExternalFilesDir(null),"bbc-playback.json").writeText(results.toString(2))
                UiDevice.getInstance(instrumentation).takeScreenshot(File(context.getExternalFilesDir(null),"$family$region.png"))
                scenario.onActivity { engine.close() }
            }
        }
        assertTrue("BBC playback results: $results",(0 until results.length()).all {
            val row = results.getJSONObject(it)
            row.optBoolean("playing") && (row.optString("requestedRegion").isBlank() || row.optString("requestedRegion") == row.optString("actualRegion"))
        })
    }
}
