package com.commercialoutcomes.uktelevision

import android.content.Intent
import androidx.compose.runtime.State
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

class Crew094Test {
    @Test fun originalShowsPaginationAndPromptReturn() {
        val inst=InstrumentationRegistry.getInstrumentation();val device=UiDevice.getInstance(inst)
        device.wakeUp();device.pressHome()
        val report=JSONObject();val context=inst.targetContext
        val intent=Intent(context,CrewCatalogueActivity::class.java).putExtra("kind","show")
        fun page(a:CrewCatalogueActivity)=((CrewCatalogueActivity::class.java.getDeclaredField("page\$delegate").apply{isAccessible=true}.get(a)) as State<*>).value as CrewCatalogue.Page
        fun busy(a:CrewCatalogueActivity)=((CrewCatalogueActivity::class.java.getDeclaredField("busy\$delegate").apply{isAccessible=true}.get(a)) as State<*>).value as Boolean
        fun await(s:ActivityScenario<CrewCatalogueActivity>):CrewCatalogue.Page {
            val end=System.currentTimeMillis()+95000;Thread.sleep(300)
            while(System.currentTimeMillis()<end){var running=true;var p:CrewCatalogue.Page?=null;s.onActivity{running=busy(it);p=page(it)};if(!running)return p!!;Thread.sleep(300)}
            error("Catalogue timed out")
        }
        fun load(s:ActivityScenario<CrewCatalogueActivity>,body:JSONObject,label:String,append:Boolean=false) {
            s.onActivity{a->CrewCatalogueActivity::class.java.getDeclaredMethod("load",JSONObject::class.java,String::class.java,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(a,body,label,false,append)}
        }
        try {
            ActivityScenario.launch<CrewCatalogueActivity>(intent).use { s ->
                Thread.sleep(5000);report.put("foregroundAt5s",device.currentPackageName)
                val root=await(s);assertEquals(context.packageName,device.currentPackageName);report.put("rootFolders",root.folders.map{it.label})
                assertTrue(root.folders.isNotEmpty())
                for((title,year) in listOf("Porridge" to 1974,"Minder" to 1979)) {
                    val start=System.currentTimeMillis();load(s,JSONObject().put("section","tv").put("query",title),title)
                    val result=await(s);report.put(title,JSONObject().put("elapsedMs",System.currentTimeMillis()-start).put("titles",result.titles.map{it.title+" ("+it.year+")"}))
                    assertTrue("Original $title missing: ${result.titles.map{it.title}}",result.titles.any{it.title.equals(title,true)&&it.year==year})
                }
                val category=root.folders.firstOrNull{it.label.contains("Popular",true)}?:root.folders.first{it.label.contains("Trending",true)}
                load(s,JSONObject().put("section","tv").put("folder",category.id),category.label)
                val first=await(s);report.put("firstPageCount",first.titles.size)
                val next=first.folders.first{it.nextPage}
                load(s,JSONObject().put("section","tv").put("folder",next.id),category.label,true)
                val second=await(s);report.put("appendedCount",second.titles.size)
                assertTrue(second.titles.size>first.titles.size)
                assertTrue(second.titles.map{it.key}.containsAll(first.titles.map{it.key}))
                device.takeScreenshot(File(context.getExternalFilesDir(null),"094-crew-pagination.png"))
            }
            val token=context.getSharedPreferences("crew-test",0).getString("token","").orEmpty()
            assertTrue(CrewBridgeClient.call(token,"/status",JSONObject()).length()>0)
            report.put("engineRetainedAfterBrowser",true)
        } finally {File(context.getExternalFilesDir(null),"094-crew.json").writeText(report.toString(2))}
    }
}
