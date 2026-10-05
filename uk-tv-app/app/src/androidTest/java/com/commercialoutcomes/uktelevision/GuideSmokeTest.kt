package com.commercialoutcomes.uktelevision

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GuideSmokeTest {
    @Test fun guideLoadsAndRemoteMenuWorks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("Guide header did not render",device.wait(Until.hasObject(By.text("UK TELEVISION")),30000))
            assertTrue("Guide grid did not render",device.wait(Until.hasObject(By.text("CHANNEL")),60000))
            Thread.sleep(8000)
            device.pressDPadDown(); device.pressDPadRight(); device.waitForIdle()
            val directory = instrumentation.targetContext.getExternalFilesDir(null)!!
            device.takeScreenshot(File(directory,"guide.png"))
            device.pressMenu()
            assertTrue("Remote menu did not open",device.wait(Until.hasObject(By.text("Television options")),10000))
            assertTrue(device.hasObject(By.text("Search")))
            device.takeScreenshot(File(directory,"options.png"))
            device.pressBack()
        }
    }
}
