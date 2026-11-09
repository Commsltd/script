package com.commercialoutcomes.uktelevision

import android.os.Debug
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real native TV layout, all seven modes, rapid changes and background cleanup. */
@RunWith(AndroidJUnit4::class)
class PersonalBroadcastSmokeTest {
    @Test fun sevenBroadcastDomainsAndRepeatedSwitching() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation)
        val directory=context.getExternalFilesDir(null)!!
        val names=listOf("business","world","spain","space","classics","practical","technology")
        val feedEvidence=org.json.JSONObject()
        val samples=StringBuilder("domain,totalPssKb,dalvikPssKb,nativePssKb\n")
        context.getSharedPreferences("my-channel",0).edit().putBoolean("auto",false).putInt("preset",0).remove("source.world").commit()
        ActivityScenario.launch(MyChannelActivity::class.java).use { scenario ->
            fun select(index:Int)=scenario.onActivity {a->MyChannelActivity::class.java.getDeclaredMethod("select",Int::class.javaPrimitiveType).apply{isAccessible=true}.invoke(a,index)}
            names.forEachIndexed { i,name->
                select(i)
                // Observe real public feeds and actual decoded pictures, with a bounded wait.
                var ready=false;val until=System.currentTimeMillis()+35000
                while(!ready && System.currentTimeMillis()<until){
                    Thread.sleep(500)
                    scenario.onActivity {a->
                        val status=MyChannelActivity::class.java.getDeclaredField("pictureStatus").apply{isAccessible=true}.get(a) as TextView
                        @Suppress("UNCHECKED_CAST")
                        val data=MyChannelActivity::class.java.getDeclaredField("panelData").apply{isAccessible=true}.get(a) as Map<String,PersonalPanel>
                        val repository=MyChannelActivity::class.java.getDeclaredField("panels").apply{isAccessible=true}.get(a) as PersonalPanelRepository
                        val weather=MyChannelActivity::class.java.getDeclaredMethod("cameraWeather").apply{isAccessible=true}.invoke(a) as String
                        val expected=repository.groups(name,weather).flatten().distinct().filter{it in repository.specs}
                        ready=status.visibility==android.view.View.GONE && expected.all{data[it]?.available==true}
                    }
                }
                // Capture the unobstructed broadcast after the normal controls timeout.
                Thread.sleep(8500)
                scenario.onActivity {a->
                    val screen=MyChannelActivity::class.java.getDeclaredField("broadcast").apply{isAccessible=true}.get(a) as PersonalBroadcastScreen
                    assertEquals(name,screen.domain)
                    assertTrue("Picture collapsed",screen.picture.height>300)
                    assertTrue("Editorial rail collapsed",screen.middle.width>200)
                    assertTrue("Numerical rail collapsed",screen.numerical.width>150)
                    assertTrue("Rundown collapsed",screen.rundown.height>100)
                    val clock=MyChannelActivity::class.java.getDeclaredField("clock").apply{isAccessible=true}.get(a) as TextView
                    assertTrue("Clock missing",clock.text.contains("LOCAL"))
                    @Suppress("UNCHECKED_CAST")
                    val data=MyChannelActivity::class.java.getDeclaredField("panelData").apply{isAccessible=true}.get(a) as Map<String,PersonalPanel>
                    val status=MyChannelActivity::class.java.getDeclaredField("pictureStatus").apply{isAccessible=true}.get(a) as TextView
                    val feeds=org.json.JSONObject();data.forEach{(key,p)->feeds.put(key,org.json.JSONObject().put("available",p.available).put("cached",p.stale).put("items",p.items.size).put("source",p.source))}
                    feedEvidence.put(name,org.json.JSONObject().put("pictureRendered",status.visibility==android.view.View.GONE).put("feeds",feeds))
                    val info=Debug.MemoryInfo();Debug.getMemoryInfo(info);samples.append("$name,${info.totalPss},${info.dalvikPss},${info.nativePss}\n")
                }
                device.takeScreenshot(File(directory,"my-channel-$name.png"))
            }
            // Exercise cancellation and resource replacement, not seven retained decoders.
            repeat(3){cycle->names.indices.forEach {select(it);Thread.sleep(300)}
                scenario.onActivity {val info=Debug.MemoryInfo();Debug.getMemoryInfo(info);samples.append("switch-cycle-$cycle,${info.totalPss},${info.dalvikPss},${info.nativePss}\n")}
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity {a->
                assertNull("Player retained in background",MyChannelActivity::class.java.getDeclaredField("player").apply{isAccessible=true}.get(a))
                assertFalse("Background flag still active",MyChannelActivity::class.java.getDeclaredField("active").apply{isAccessible=true}.getBoolean(a))
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            device.pressBack()
            Thread.sleep(400)
        }
        File(directory,"my-channel-feeds.json").writeText(feedEvidence.toString(2))
        File(directory,"my-channel-memory.log").writeText(samples.toString())
    }
}
