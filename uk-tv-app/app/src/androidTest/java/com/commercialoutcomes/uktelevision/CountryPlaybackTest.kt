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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CountryPlaybackTest {
    @Test fun countryShelvesLoadAndRepresentativeChannelsRenderVideo() {
        val args=InstrumentationRegistry.getArguments();assumeTrue(args.getString("liveChecks")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("preferences",0);val last=prefs.getString("lastChannel",null)
        val app=context.applicationContext as TvApplication
        assertNull(runBlocking { app.repository.refresh() })
        val report=JSONArray()
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model:TvModel;lateinit var engine:PlaybackEngine
            scenario.onActivity { a -> model=ViewModelProvider(a)[TvModel::class.java];engine=MainActivity::class.java.getDeclaredField("engine").apply { isAccessible=true }.get(a) as PlaybackEngine }
            val ready=System.currentTimeMillis()+15000
            var count=0
            while(System.currentTimeMillis()<ready) { scenario.onActivity { count=model.stations.size };if(count>=320)break;Thread.sleep(200) }
            scenario.onActivity {
                assertTrue(model.stations.size>=320)
                assertTrue(model.groups().containsAll(CountryGroups.labels.values))
                assertTrue(model.stations.single { it.family=="GBNews.uk" }.sources().size>=11)
                assertTrue(model.stations.single { it.family=="CBCTelevision.ca" }.sources().size>=14)
                assertTrue(model.stations.single { it.family=="ABCTV.au" }.sources().size>=8)
            }
            for(id in listOf("BizzTV.tt","TG4.ie","CityNewsToronto.ca","ABCNews.au","WhakaataMaori.nz")) {
                scenario.onActivity { a ->
                    val station=model.stations.single { it.family==id }
                    MainActivity::class.java.getDeclaredMethod("startPlayback",GuideRow::class.java,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)
                        .apply { isAccessible=true }.invoke(a,GuideRow(station,station.sources().first(),0),true,false)
                }
                val end=System.currentTimeMillis()+35000;var frames=0;var playing=false;var status=""
                while(System.currentTimeMillis()<end) {
                    scenario.onActivity { frames=engine.player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0;playing=engine.player?.isPlaying==true && frames>=100;status=engine.status }
                    if(playing)break;Thread.sleep(300)
                }
                report.put(JSONObject().put("channel",id).put("playing",playing).put("frames",frames).put("status",status))
                File(context.getExternalFilesDir(null),"country-playback.json").writeText(report.toString(2))
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(context.getExternalFilesDir(null),"country-$id.png"))
            }
            scenario.onActivity { engine.close() }
        } } finally { prefs.edit().apply { if(last==null)remove("lastChannel")else putString("lastChannel",last) }.apply() }
        assertTrue("Representative country playback: $report",(0 until report.length()).all { report.getJSONObject(it).getBoolean("playing") })
    }
}
