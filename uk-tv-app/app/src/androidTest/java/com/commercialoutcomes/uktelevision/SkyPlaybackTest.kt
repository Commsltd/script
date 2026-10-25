package com.commercialoutcomes.uktelevision

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SkyPlaybackTest {
    @Test fun officialSkyFeedsRenderAndKeepPlaying() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveChecks") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val urls = listOf(
            "https://linear901-oo-hls0-prd-gtm.delivery.skycdp.com/v1/master/6404a5d732e04991ed59ac7790b61cc065c9aabd/prod-gb-lin-skynews-hls-25-web/master.m3u8?ads.cdn=https://linear901-oo-hls0-prd-gtm.delivery.skycdp.com&ads.csid=sitesection:SkyNews:Web&manifest.mthost=7a38d30e7dd84cd0872ab4f691c38f58&manifest.region=mediatailor.eu-west-1.amazonaws.com",
            "https://linear901-oo-hls0-prd-gtm.delivery.skycdp.com/17501/sde-fast-skynews/master.m3u8")
        val results = JSONArray()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for ((i,url) in urls.withIndex()) {
                lateinit var engine: PlaybackEngine
                val s = StreamSource("sky-official-$i",url,"Sky News official $i","skycdp.com","application/x-mpegURL",emptyMap(),false)
                val station = Station("SkyNews.uk@Test","Sky News","SkyNews.uk","TEST",0,"","","[]","")
                scenario.onActivity { a ->
                    engine=MainActivity::class.java.getDeclaredField("engine").apply { isAccessible=true }.get(a) as PlaybackEngine
                    val model=ViewModelProvider(a)[TvModel::class.java]
                    val row=GuideRow(station.copy(sourcesJson=JSONArray().put(JSONObject().put("id",s.id).put("url",url).put("label",s.label)).toString()),s,0)
                    model.markPlaying(row); engine.play(row,true)
                }
                var playing=false; var stable=0L; var status=""; var frames=0
                val end=System.currentTimeMillis()+60000
                while(System.currentTimeMillis()<end) {
                    scenario.onActivity {
                        status=engine.status
                        frames=engine.player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                        if(engine.player?.isPlaying==true && status.startsWith("Playing") && frames>0) {
                            if(stable==0L)stable=System.currentTimeMillis()
                            playing=System.currentTimeMillis()-stable>=20000
                        } else stable=0
                    }
                    if(playing)break
                    Thread.sleep(250)
                }
                results.put(JSONObject().put("source",i).put("playing",playing).put("status",status).put("renderedFrames",frames))
                File(context.getExternalFilesDir(null),"sky-playback.json").writeText(results.toString(2))
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(context.getExternalFilesDir(null),"sky-$i.png"))
                scenario.onActivity { engine.close() }
            }
        }
        assertTrue(results.toString(),(0 until results.length()).all { results.getJSONObject(it).getBoolean("playing") })
    }
}
