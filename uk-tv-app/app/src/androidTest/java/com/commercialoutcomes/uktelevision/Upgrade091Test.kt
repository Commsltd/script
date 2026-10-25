package com.commercialoutcomes.uktelevision

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Upgrade091Test {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val device=UiDevice.getInstance(inst)
    private fun optIn(){assumeTrue(InstrumentationRegistry.getArguments().getString("liveChecks")=="true");device.wakeUp();Configurator.getInstance().waitForIdleTimeout=500}
    private fun report(name:String,data:Any){File(inst.targetContext.getExternalFilesDir(null),"091-$name.json").writeText(data.toString())}
    private fun shot(name:String){device.takeScreenshot(File(inst.targetContext.getExternalFilesDir(null),"091-$name.png"))}

    @Test fun plutoPreviewAndFullscreen(){
        optIn();val results=JSONArray()
        val prefs=inst.targetContext.getSharedPreferences("preferences",0);val last=prefs.getString("lastChannel",null)
        try{ActivityScenario.launch(MainActivity::class.java).use{scenario->
            lateinit var model:TvModel;lateinit var engine:PlaybackEngine
            scenario.onActivity{a->model=ViewModelProvider(a)[TvModel::class.java];engine=MainActivity::class.java.getDeclaredField("engine").apply{isAccessible=true}.get(a) as PlaybackEngine}
            repeat(30){var ready=false;scenario.onActivity{ready=model.stations.isNotEmpty()};if(!ready)Thread.sleep(300)}
            for(id in listOf("PlutoTVMovies.de","PlutoTVAnimation.de")){
                scenario.onActivity{a->val s=model.stations.first{it.family==id};MainActivity::class.java.getDeclaredMethod("startPlayback",GuideRow::class.java,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(a,GuideRow(s,s.sources().first(),0),false,false)}
                val end=System.currentTimeMillis()+30000;var frames=0;var status="";var playing=false
                while(System.currentTimeMillis()<end){scenario.onActivity{frames=engine.player?.videoDecoderCounters?.renderedOutputBufferCount?:0;status=engine.status;playing=engine.player?.isPlaying==true};if(frames>=100&&playing)break;Thread.sleep(300)}
                shot("preview-$id")
                val row=JSONObject().put("channel",id).put("previewFrames",frames).put("previewPlaying",playing).put("status",status)
                if(frames>=100&&playing){val before=frames;scenario.onActivity{a->MainActivity::class.java.getDeclaredField("surface\$delegate").apply{isAccessible=true}.get(a).let{(it as androidx.compose.runtime.MutableState<TvSurface>).value=TvSurface.LIVE}};Thread.sleep(4000);scenario.onActivity{frames=engine.player?.videoDecoderCounters?.renderedOutputBufferCount?:0};row.put("fullscreenAdditionalFrames",frames-before);shot("full-$id")}
                results.put(row);report("pluto",results)
            }
            scenario.onActivity{engine.close()}
        }}finally{prefs.edit().apply{if(last==null)remove("lastChannel")else putString("lastChannel",last)}.commit()}
        assertTrue(results.toString(),(0 until results.length()).all{results.getJSONObject(it).optInt("previewFrames")>=100&&results.getJSONObject(it).optInt("fullscreenAdditionalFrames")>30})
    }

    @Test fun vodSynopsisAndProviderSelection(){
        optIn();val prefs=inst.targetContext.getSharedPreferences("vod-library",0);val selection=prefs.getString("providers",null);val saved=prefs.getString("saved",null)
        try{ActivityScenario.launch(OnDemandActivity::class.java).use{scenario->
            scenario.onActivity{a->OnDemandActivity::class.java.getDeclaredMethod("setProviders",Set::class.java).apply{isAccessible=true}.invoke(a,setOf(VodProvider.MOVY,VodProvider.DULO));a.searchOnline("Porridge")}
            var rows=emptyList<VodTitle>();val end=System.currentTimeMillis()+25000
            while(System.currentTimeMillis()<end){var loading=true;scenario.onActivity{rows=it.visibleTitles;loading=it.isLoading};if(!loading&&rows.isNotEmpty())break;Thread.sleep(300)}
            assertTrue(rows.any{it.title=="Porridge"&&it.year==1974&&it.description.isNotBlank()})
            repeat(3){device.pressDPadDown();Thread.sleep(400)}
            val descriptions=rows.map{it.description}.filter{it.isNotBlank()}
            val visible=descriptions.any{device.hasObject(By.text(it))}
            shot("vod-synopsis");report("vod",JSONObject().put("titles",rows.map{it.title+" ("+it.year+")"}).put("synopsisVisible",visible))
            assertTrue("Focused title synopsis must appear",visible)
            scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.text("Providers: 2 selected")),8000))
            assertFalse(device.hasObject(By.text("Browse The Crew")))
            assertEquals(saved,prefs.getString("saved",null))
        }}finally{prefs.edit().apply{if(selection==null)remove("providers")else putString("providers",selection)}.commit()}
    }

    @Test fun crewOriginalPorridgeCatalogue(){
        optIn()
        val intent=Intent(inst.targetContext,CrewCatalogueActivity::class.java).putExtra("kind","show").putExtra("query","Porridge")
        ActivityScenario.launch<CrewCatalogueActivity>(intent).use{scenario->
            var titles=emptyList<VodTitle>();var message="";val end=System.currentTimeMillis()+100000
            while(System.currentTimeMillis()<end){var busy=true;scenario.onActivity{a->val page=CrewCatalogueActivity::class.java.getDeclaredField("page\$delegate").apply{isAccessible=true}.get(a) as androidx.compose.runtime.State<*>;titles=(page.value as CrewCatalogue.Page).titles;busy=(CrewCatalogueActivity::class.java.getDeclaredField("busy\$delegate").apply{isAccessible=true}.get(a) as androidx.compose.runtime.State<*>).value as Boolean;message=(CrewCatalogueActivity::class.java.getDeclaredField("message\$delegate").apply{isAccessible=true}.get(a) as androidx.compose.runtime.State<*>).value as String};if(!busy)break;Thread.sleep(700)}
            report("crew",JSONObject().put("titles",titles.map{it.title+" ("+it.year+")"}).put("message",message));shot("crew-catalogue")
            assertTrue(message,titles.any{it.title=="Porridge"&&it.year==1974&&it.sources.any{s->s.provider==VodProvider.CREW}})
        }
    }
}
