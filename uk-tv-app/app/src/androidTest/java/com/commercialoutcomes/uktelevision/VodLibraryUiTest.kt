package com.commercialoutcomes.uktelevision

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File

class VodLibraryUiTest {
    @Test fun remoteFiltersAndSavedListsSurviveReopening() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveChecks")=="true")
        val context=instrumentation.targetContext
        assumeTrue("Saved-list UI test runs only in the separate test app",context.packageName.endsWith(".candidate"))
        val prefs=context.getSharedPreferences("vod-library",0);val original=prefs.getString("saved",null)
        prefs.edit().remove("saved").commit()
        val device=UiDevice.getInstance(instrumentation)
        device.wakeUp()
        Configurator.getInstance().waitForIdleTimeout=500
        fun click(label:String) {
            var item=device.wait(Until.findObject(By.text(label)),3000)
            // TV dialogs scroll with the D-pad; off-screen choices are absent
            // from the accessibility tree until they are brought into view.
            var steps=0
            while(item==null && steps++<30) { device.pressDPadDown();Thread.sleep(150);item=device.findObject(By.text(label)) }
            if(item==null)device.takeScreenshot(File(context.getExternalFilesDir(null),"vod-ui-failure.png"))
            assertNotNull("Missing $label",item);Thread.sleep(350);item!!.click();Thread.sleep(250);instrumentation.waitForIdleSync()
        }
        try {
            ActivityScenario.launch(OnDemandActivity::class.java).use { scenario ->
                val deadline=System.currentTimeMillis()+150000
                var titles=emptyList<VodTitle>();var ready=false
                while(System.currentTimeMillis()<deadline) {
                    scenario.onActivity { titles=it.visibleTitles;ready=!it.isLoading && it.hasWindowFocus() }
                    if(ready && titles.any { "Drama" in it.genres })break
                    Thread.sleep(500)
                }
                assertTrue("A real genre-labelled catalogue must load",titles.any { "Drama" in it.genres })
                click("Genre: All");click("Drama")
                click("Sort: Featured");click("Newest first")
                assertNotNull(device.wait(Until.findObject(By.text("Sort: Newest first")),10000))
                scenario.onActivity { titles=it.visibleTitles }
                assertTrue(titles.isNotEmpty());assertTrue(titles.all { "Drama" in it.genres })
                val known=titles.mapNotNull { it.year };assertEquals(known.sortedDescending(),known)
                val selectedYear=known.first()
                click("Year: All years");click("Choose an exact year");click(selectedYear.toString())
                assertNotNull(device.wait(Until.findObject(By.text("Year: $selectedYear")),10000))
                scenario.onActivity { titles=it.visibleTitles }
                assertTrue(titles.isNotEmpty());assertTrue(titles.all { it.year==selectedYear && "Drama" in it.genres })
                device.takeScreenshot(File(context.getExternalFilesDir(null),"vod-filtered-library.png"))
                click("Clear filters")
                scenario.onActivity { titles=it.visibleTitles }
                val title=titles.first()
                click(title.title);click("☆ Add to favourites")
                click("★ ${title.title}");click("Save to lists")
                val input=device.wait(Until.findObject(By.clazz("android.widget.EditText")),10000)
                assertNotNull(input);input.text="Weekend check";click("Save")
                val saved=VodSavedCodec.decode(prefs.getString("saved","").orEmpty())
                assertTrue(saved.isFavourite(title));assertEquals(1,saved.lists.size);assertTrue(saved.keyFor(title) in saved.lists.single().keys)
                scenario.recreate()
                click("★ Favourites")
                scenario.onActivity { titles=it.visibleTitles }
                assertEquals(listOf(title.key),titles.map { it.key })
                device.takeScreenshot(File(context.getExternalFilesDir(null),"vod-favourites.png"))
                click("My lists");click("Weekend check (1)")
                scenario.onActivity { titles=it.visibleTitles }
                assertEquals(listOf(title.key),titles.map { it.key })
                device.takeScreenshot(File(context.getExternalFilesDir(null),"vod-saved-list.png"))
                File(context.getExternalFilesDir(null),"vod-library-check.json").writeText(JSONObject().put("genre","Drama").put("exactYear",selectedYear).put("newestOrderVerified",true).put("favouriteSurvivedRecreation",true).put("namedListSurvivedRecreation",true).put("savedTitle",title.title).toString(2))
            }
        } finally {
            if(original==null)prefs.edit().remove("saved").commit() else prefs.edit().putString("saved",original).commit()
        }
    }
}
