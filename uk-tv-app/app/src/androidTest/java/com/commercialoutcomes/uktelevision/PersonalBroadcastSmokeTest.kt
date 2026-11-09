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
        val samples=StringBuilder("domain,totalPssKb,dalvikPssKb,nativePssKb\n")
        context.getSharedPreferences("my-channel",0).edit().putBoolean("auto",false).putInt("preset",0).commit()
        ActivityScenario.launch(MyChannelActivity::class.java).use { scenario ->
            fun select(index:Int)=scenario.onActivity {a->MyChannelActivity::class.java.getDeclaredMethod("select",Int::class.javaPrimitiveType).apply{isAccessible=true}.invoke(a,index)}
            names.forEachIndexed { i,name->
                select(i)
                Thread.sleep(12000) // Live data and picture use the same public sources as the normal build.
                scenario.onActivity {a->
                    @Suppress("UNCHECKED_CAST")
                    val cards=MyChannelActivity::class.java.getDeclaredField("panelViews").apply{isAccessible=true}.get(a) as List<PersonalCard>
                    assertEquals("Five panels required in $name",5,cards.size)
                    for(card in cards){assertTrue("$name panel collapsed",card.height>80);assertTrue("$name panel empty",card.value.text.isNotEmpty());assertTrue("$name heading missing",card.heading.text.isNotEmpty())}
                    val clock=MyChannelActivity::class.java.getDeclaredField("clock").apply{isAccessible=true}.get(a) as TextView
                    assertTrue("Clock missing",clock.text.contains("LOCAL"))
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
        File(directory,"my-channel-memory.log").writeText(samples.toString())
    }
}
