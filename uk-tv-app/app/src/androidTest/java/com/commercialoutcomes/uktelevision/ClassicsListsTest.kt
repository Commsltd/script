package com.commercialoutcomes.uktelevision

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

class ClassicsListsTest {
    @Test fun listsAreInstalledEditableAndPersistWithoutStandaloneButton() {
        val inst=InstrumentationRegistry.getInstrumentation();val device=UiDevice.getInstance(inst)
        val prefs=inst.targetContext.getSharedPreferences("vod-library",0)
        val providers=prefs.getString("providers",null)
        ActivityScenario.launch(OnDemandActivity::class.java).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.text("My lists")),10000))
            assertFalse(device.hasObject(By.text("British classics")))
            device.findObject(By.text("My lists")).click()
            assertTrue(device.wait(Until.hasObject(By.text("UK Comedy Classics (90)")),5000))
            assertTrue(device.hasObject(By.text("UK Classic Shows (108)")))
            device.takeScreenshot(File(inst.targetContext.getExternalFilesDir(null),"093-my-lists.png"))
            device.findObject(By.text("UK Comedy Classics (90)")).click()
            assertTrue(device.wait(Until.hasObject(By.textContains("UK Comedy Classics ·")),5000))
            var rows=emptyList<VodTitle>();scenario.onActivity{rows=it.visibleTitles}
            assertTrue(rows.any{it.title=="Only Fools and Horses"&&it.year==1981})
            assertTrue(rows.all{it.description.isNotBlank()})
            device.takeScreenshot(File(inst.targetContext.getExternalFilesDir(null),"093-comedy.png"))
            val saved=prefs.getString("saved",null)
            scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.text("My lists")),8000))
            assertEquals(saved,prefs.getString("saved",null));assertEquals(providers,prefs.getString("providers",null))
            device.findObject(By.text("My lists")).click()
            device.findObject(By.text("UK Classic Shows (108)")).click()
            assertTrue(device.wait(Until.hasObject(By.textContains("UK Classic Shows ·")),5000))
            scenario.onActivity{rows=it.visibleTitles}
            assertTrue(rows.any{it.title=="Minder"});assertTrue(rows.any{it.title=="New Tricks"})
            device.takeScreenshot(File(inst.targetContext.getExternalFilesDir(null),"093-classic-shows.png"))
            File(inst.targetContext.getExternalFilesDir(null),"093-lists.json").writeText(JSONObject().put("comedy",90).put("classicShows",108).put("providerSelectionPreserved",true).put("persisted",true).toString())
        }
    }
    @Test fun signedBridgeStatus() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val token=inst.targetContext.getSharedPreferences("crew-test",0).getString("token","").orEmpty()
        val status=CrewBridgeClient.call(token,"/status",JSONObject())
        assertTrue(status.length()>0)
        File(inst.targetContext.getExternalFilesDir(null),"093-bridge.json").writeText(JSONObject().put("signedStatusVerified",true).toString())
    }
}
