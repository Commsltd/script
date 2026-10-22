package com.commercialoutcomes.uktelevision

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SourceRecoveryLiveTest {
    @Test fun gbNewsAutomaticallyRecoversFromAnUnavailableFirstSource() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveChecks")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val original=BundledCatalogue.read(context).stations.single { it.family=="GBNews.uk" }
        val all=JSONArray(original.sourcesJson)
        val rows=JSONArray().put(JSONObject().put("id","test-unavailable").put("url","http://127.0.0.1:1/missing.m3u8").put("label","Unavailable test source").put("mime","application/x-mpegURL"))
        for(i in 0 until all.length())rows.put(all.get(i))
        val station=original.copy(sourcesJson=rows.toString())
        assertTrue("All available GB News alternatives must be retained",original.sources().size>=11)
        val attempts=JSONArray();var seen="";var stable=0L;var passed=false;var frames=0;var status=""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var engine:PlaybackEngine
            scenario.onActivity { a ->
                val model=ViewModelProvider(a)[TvModel::class.java];model.setFailover(true)
                engine=MainActivity::class.java.getDeclaredField("engine").apply { isAccessible=true }.get(a) as PlaybackEngine
                val row=GuideRow(station,station.sources().first(),0)
                model.markPlaying(row);engine.play(row,true)
            }
            val end=System.currentTimeMillis()+160000
            while(System.currentTimeMillis()<end) {
                scenario.onActivity {
                    val source=engine.source
                    if(source!=null && source.id!=seen) { seen=source.id;stable=0;attempts.put(JSONObject().put("id",seen).put("label",source.label).put("time",System.currentTimeMillis())) }
                    frames=engine.player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                    status=engine.status
                    if(engine.player?.isPlaying==true && frames>0) {
                        if(stable==0L)stable=System.currentTimeMillis()
                        passed=System.currentTimeMillis()-stable>=20000
                    } else stable=0
                }
                File(context.getExternalFilesDir(null),"gbnews-recovery.json").writeText(JSONObject().put("sourceCount",original.sources().size).put("attempts",attempts).put("playing",passed).put("frames",frames).put("status",status).toString(2))
                if(passed)break
                Thread.sleep(300)
            }
            scenario.onActivity { engine.close() }
        }
        assertTrue("GB News did not recover: $status $attempts",passed)
        assertTrue("A different source must be selected automatically",attempts.length()>=2)
    }
}
