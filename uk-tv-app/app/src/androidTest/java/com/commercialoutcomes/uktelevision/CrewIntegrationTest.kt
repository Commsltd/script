package com.commercialoutcomes.uktelevision
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
class CrewIntegrationTest {
    @Test fun localEngineReturnsToUkTvAndReportsSources(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveChecks")=="true")
        val inst=InstrumentationRegistry.getInstrumentation();val device=UiDevice.getInstance(inst)
        val intent=Intent(inst.targetContext,CrewTestActivity::class.java).putExtra("type","movie").putExtra("tmdb","278").putExtra("title","The Shawshank Redemption")
        ActivityScenario.launch<CrewTestActivity>(intent).use {
            val started=System.currentTimeMillis()
            assertTrue(device.wait(Until.hasObject(By.text("Check sources")),10000))
            device.findObject(By.text("Check sources")).click()
            val finished=device.wait(Until.hasObject(By.textContains("The Crew returned no free sources")),120000)
            val failed=device.hasObject(By.textContains("Crew test could not complete"))
            device.takeScreenshot(File(inst.targetContext.getExternalFilesDir(null),"crew-test.png"))
            File(inst.targetContext.getExternalFilesDir(null),"crew-integration.json").writeText(JSONObject().put("completed",finished).put("failed",failed).put("elapsedMs",System.currentTimeMillis()-started).toString(2))
            assertTrue("Expected a completed source check on the Firestick",finished)
            assertEquals(inst.targetContext.packageName,device.currentPackageName)
        }
    }
}
