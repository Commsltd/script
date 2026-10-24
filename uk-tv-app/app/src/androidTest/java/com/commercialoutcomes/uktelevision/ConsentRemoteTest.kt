package com.commercialoutcomes.uktelevision

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConsentRemoteTest {
    @Test fun consentRequiresExplicitRemoteChoiceAndDeclineIsReachable() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation)
        // A local fixture exercises the real activity without changing the
        // user's Plex consent or submitting anything to a provider.
        val intent=Intent(context,VodPlayerActivity::class.java).putExtra("provider","PLEX").putExtra("url","https://watch.plex.tv/")
        ActivityScenario.launch<VodPlayerActivity>(intent).use { scenario ->
            scenario.onActivity { a ->
                a.web.stopLoading()
                a.web.loadDataWithBaseURL("https://watch.plex.tv/","""<html><body style="background:black;color:white"><div role="dialog"><h1>Consent</h1><p>Local remote-control test. No provider privacy settings are changed.</p><button onclick="window.choice='agree';this.parentElement.remove()">I Agree</button><button onclick="window.choice='decline';this.parentElement.remove()">I Do Not Agree</button></div><script>window.choice='unset'</script></body></html>""","text/html","UTF-8","https://watch.plex.tv/")
            }
            assertTrue(device.wait(Until.hasObject(By.text("Plex · consent")),15000))
            fun choice():String {
                val latch=CountDownLatch(1);var result=""
                scenario.onActivity{it.web.evaluateJavascript("window.choice"){v->result=v;latch.countDown()}}
                assertTrue(latch.await(5,TimeUnit.SECONDS));return result
            }
            assertEquals("\"unset\"",choice())
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"plex-remote-consent.png"))
            device.pressDPadCenter()
            val end=System.currentTimeMillis()+5000
            while(choice()=="\"unset\""&&System.currentTimeMillis()<end)Thread.sleep(100)
            assertEquals("\"decline\"",choice())
        }
    }
}
