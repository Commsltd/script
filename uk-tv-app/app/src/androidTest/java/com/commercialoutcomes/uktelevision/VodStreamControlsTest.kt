package com.commercialoutcomes.uktelevision

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VodStreamControlsTest {
    @Test fun serverChoicesSwitchOnlyTheRequestedProviderButton() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        for(provider in listOf(VodProvider.MOVY,VodProvider.DULO)) {
            val intent=Intent(context,VodPlayerActivity::class.java).putExtra("provider",provider.name).putExtra("url",provider.home)
            ActivityScenario.launch<VodPlayerActivity>(intent).use { scenario ->
                val ready=CountDownLatch(1)
                scenario.onActivity { a ->
                    val buttons=if(provider==VodProvider.MOVY)"""<button onclick="document.body.dataset.chosen='first'"><img alt="Miami"><span aria-label="Favorite server"></span></button><button onclick="document.body.dataset.chosen='second'"><img alt="Boise"><span aria-label="Favorite server"></span></button>""" else """<div aria-label="Playback sources"><button role="checkbox" aria-checked="true" onclick="document.body.dataset.chosen='first'">First 1080p</button><button role="checkbox" aria-checked="false" onclick="document.body.dataset.chosen='second'">Second 720p</button></div>"""
                    a.web.webViewClient=object:android.webkit.WebViewClient(){override fun onPageFinished(v:android.webkit.WebView,u:String){ready.countDown()}}
                    a.web.loadDataWithBaseURL(provider.home,"<html><body data-chosen='none'>$buttons</body></html>","text/html","UTF-8",null)
                }
                assertTrue(ready.await(10,TimeUnit.SECONDS))
                val listed=CountDownLatch(1);var choices=emptyList<VodStreamControls.Choice>();lateinit var controls:VodStreamControls
                scenario.onActivity { controls=VodStreamControls(it.web,provider);controls.list { rows->choices=rows;listed.countDown() } }
                assertTrue(listed.await(8,TimeUnit.SECONDS));assertEquals(2,choices.size)
                val selected=CountDownLatch(1);var changed=false
                scenario.onActivity { controls.choose(choices[1]){changed=it;selected.countDown()} }
                assertTrue(selected.await(5,TimeUnit.SECONDS));assertTrue(changed)
                val checked=CountDownLatch(1);var value=""
                scenario.onActivity { it.web.evaluateJavascript("document.body.dataset.chosen"){v->value=v;checked.countDown()};controls.close() }
                assertTrue(checked.await(5,TimeUnit.SECONDS));assertEquals("\"second\"",value)
            }
        }
    }
}
