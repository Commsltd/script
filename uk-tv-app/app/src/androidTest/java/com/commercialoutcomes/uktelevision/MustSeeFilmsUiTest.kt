package com.commercialoutcomes.uktelevision
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MustSeeFilmsUiTest {
    @Test fun allFilmsUnderMyListsPreserveOrderAndNotes() {
        val inst=InstrumentationRegistry.getInstrumentation();val device=UiDevice.getInstance(inst)
        device.wakeUp();device.pressHome()
        ActivityScenario.launch(OnDemandActivity::class.java).use { s ->
            assertTrue(device.wait(Until.hasObject(By.text("My lists")),10000))
            device.findObject(By.text("My lists")).click()
            assertTrue(device.wait(Until.hasObject(By.text("100 Must See Films (100)")),5000))
            device.takeScreenshot(File(inst.targetContext.getExternalFilesDir(null),"095-my-lists.png"))
            device.findObject(By.text("100 Must See Films (100)")).click()
            assertTrue(device.wait(Until.hasObject(By.textContains("100 Must See Films ·")),5000))
            var rows=emptyList<VodTitle>();s.onActivity{rows=it.visibleTitles}
            assertEquals(100,rows.size);assertEquals("Citizen Kane",rows.first().title);assertEquals("The Substance",rows.last().title)
            assertTrue(rows.first().description.startsWith("Power, wealth, media and ambition."))
            assertEquals(1942,rows[1].year)
            val prefs=inst.targetContext.getSharedPreferences("vod-library",0);val saved=prefs.getString("saved",null)
            device.takeScreenshot(File(inst.targetContext.getExternalFilesDir(null),"095-films.png"))
            s.recreate();assertTrue(device.wait(Until.hasObject(By.text("My lists")),8000));assertEquals(saved,prefs.getString("saved",null))
        }
    }
}
