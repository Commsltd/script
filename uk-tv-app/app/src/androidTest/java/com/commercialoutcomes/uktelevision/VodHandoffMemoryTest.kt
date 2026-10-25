package com.commercialoutcomes.uktelevision

import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VodHandoffMemoryTest {
    @Test fun nativePlaybackUnloadsProviderAndRestoresControlsOnDemand() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("handoffLive") == "true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val page="https://www.movy.sx/"
        ActivityScenario.launch<VodPlayerActivity>(Intent(context,VodPlayerActivity::class.java)
            .putExtra("provider","MOVY").putExtra("url",page)).use { scenario ->
            val restored=AtomicBoolean(false)
            scenario.onActivity { activity ->
                activity.web.stopLoading()
                activity.web.webViewClient=object:WebViewClient() {
                    override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                        if(request.url.toString()!=page)return null
                        val html="""<html><body><video></video><button>Stream 1</button><script>
                        const v=document.querySelector('video');Object.defineProperty(v,'readyState',{get:()=>4});
                        Object.defineProperty(v,'duration',{get:()=>3600});v.play=()=>Promise.resolve();
                        </script></body></html>"""
                        return WebResourceResponse("text/html","UTF-8",html.byteInputStream())
                    }
                }
                activity.web.loadUrl(page)
                activity.nativePlayback!!.begin("https://a-cdn.klowdtv.com/live3/entrepreneurtv_720p/playlist.m3u8",page,activity.web.settings.userAgentString,0)
            }
            val deadline=System.currentTimeMillis()+30000
            var unloaded=false
            while(System.currentTimeMillis()<deadline&&!unloaded){
                scenario.onActivity { unloaded=it.web.url=="about:blank" && (it.nativePlayback?.player?.videoDecoderCounters?.renderedOutputBufferCount?:0)>0 }
                Thread.sleep(200)
            }
            assertTrue("Provider document remains loaded beside native decoder",unloaded)
            scenario.onActivity { it.nativePlayback!!.returnToWeb(ready={restored.set(true)}) }
            val restoreDeadline=System.currentTimeMillis()+10000
            while(System.currentTimeMillis()<restoreDeadline&&!restored.get())Thread.sleep(200)
            assertTrue("Provider controls did not restore",restored.get())
            scenario.onActivity { assertEquals(page,it.web.url);assertNull(it.nativePlayback!!.player) }
        }
    }
}
