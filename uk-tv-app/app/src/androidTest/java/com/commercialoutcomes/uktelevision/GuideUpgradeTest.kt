package com.commercialoutcomes.uktelevision

import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GuideUpgradeTest {
    @Test fun broadcasterPreviewKeepsSessionWhenOpenedFullscreen() {
        val args=InstrumentationRegistry.getArguments();assumeTrue(args.getString("liveChecks")=="true")
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val id=args.getString("station","TTT.tt")
        val prefs=context.getSharedPreferences("preferences",0);val last=prefs.getString("lastChannel",null)
        val app=context.applicationContext as TvApplication
        val staged=File(context.getExternalFilesDir(null),"upgrade-catalogue.json.gz")
        if(staged.exists())runBlocking {
            val c=staged.inputStream().use { CatalogueParser.parse(it) }
            app.database.withTransaction {
                val dao=app.database.dao();dao.clearProgrammes();dao.clearStations();dao.putStations(c.stations)
                c.programmes.chunked(500).forEach { dao.putProgrammes(it) };dao.putSnapshot(c.snapshot)
            }
        }
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model:TvModel;var live:LiveWebPlayer?=null
            scenario.onActivity { a ->model=ViewModelProvider(a)[TvModel::class.java] }
            val end=System.currentTimeMillis()+20000
            var ready=false
            while(System.currentTimeMillis()<end){scenario.onActivity{ready=model.stations.any{it.family==id}};if(ready)break;Thread.sleep(200)}
            fun tune(full:Boolean){scenario.onActivity{a->
                val s=model.stations.single{it.family==id}
                val row=GuideRow(s,s.sources().first{it.kind=="web"},0)
                MainActivity::class.java.getDeclaredMethod("startPlayback",GuideRow::class.java,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(a,row,full,false)
                live=MainActivity::class.java.getDeclaredMethod("getLiveWeb").apply{isAccessible=true}.invoke(a) as? LiveWebPlayer
            }}
            tune(false);assertNotNull(live)
            var data=JSONObject()
            fun state(){val latch=CountDownLatch(1);scenario.onActivity{live!!.web.evaluateJavascript("""(()=>{const v=document.querySelector('video');const f=[...document.querySelectorAll('iframe')].find(f=>f.src.includes('dailymotion.com'));return JSON.stringify({time:v?.currentTime||0,frames:v?.getVideoPlaybackQuality?.().totalVideoFrames||0,width:v?.videoWidth||0,paused:v?.paused??true,iframe:!!f,frameRect:f?.getBoundingClientRect().toJSON(),videoRect:v?.getBoundingClientRect().toJSON(),viewport:[innerWidth,innerHeight],text:document.body.innerText.slice(0,400)})})()"""){raw->runCatching{data=JSONObject(org.json.JSONTokener(raw).nextValue().toString())};latch.countDown()}};assertTrue(latch.await(5,TimeUnit.SECONDS))}
            val deadline=System.currentTimeMillis()+90000
            while(System.currentTimeMillis()<deadline){state();if(data.optInt("frames")>60||data.optBoolean("iframe"))break;Thread.sleep(1000)}
            Thread.sleep(3000);state()
            File(context.getExternalFilesDir(null),"preview-before-$id.json").writeText(data.toString(2))
            UiDevice.getInstance(inst).takeScreenshot(File(context.getExternalFilesDir(null),"preview-$id.png"))
            val initial=live;val before=data.optDouble("time")
            tune(true);assertSame(initial,live)
            Thread.sleep(5000);state()
            UiDevice.getInstance(inst).takeScreenshot(File(context.getExternalFilesDir(null),"fullscreen-$id.png"))
            File(context.getExternalFilesDir(null),"preview-$id.json").writeText(data.toString(2))
            if(id=="CCNTV6.tt"){
                assertTrue(data.toString(),data.optBoolean("iframe"))
                val r=data.getJSONObject("frameRect");assertEquals(0.0,r.getDouble("x"),2.0);assertEquals(0.0,r.getDouble("y"),2.0)
                assertEquals(data.getJSONArray("viewport").getDouble(0),r.getDouble("width"),2.0)
            } else assertTrue(data.toString(),data.optDouble("time")>before+2 && data.optInt("width")>0)
        }}finally{prefs.edit().apply{if(last==null)remove("lastChannel")else putString("lastChannel",last)}.apply()}
    }
}
