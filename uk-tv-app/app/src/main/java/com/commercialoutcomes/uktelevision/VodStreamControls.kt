package com.commercialoutcomes.uktelevision

import android.webkit.WebView
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONTokener

/** Operate the provider's own server buttons, leaving its player/session intact.
 * These are server choices, not claims that HD means a clean, non-camera copy. */
class VodStreamControls(private val web: WebView, private val provider: VodProvider) {
    data class Choice(val index: Int, val label: String, val selected: Boolean)
    private val handler=Handler(Looper.getMainLooper())
    private var cancelled=false
    fun close() { cancelled=true;handler.removeCallbacksAndMessages(null) }
    private val prelude get()=when(provider) {
        VodProvider.MOVY -> """const buttons=[...document.querySelectorAll('button')];const choices=buttons.filter(b=>b.querySelector('img[alt]')&&b.querySelector('[aria-label="Favorite server"]'));"""
        VodProvider.DULO -> """const buttons=[...document.querySelectorAll('button')];const region=document.querySelector('[aria-label="Playback sources"]');const choices=region?[...region.querySelectorAll('button')]:[...document.querySelectorAll('button.source-row[aria-pressed]')];"""
        else -> "const buttons=[];const choices=[];"
    }
    fun list(done: (List<Choice>)->Unit) {
        var attempts=0
        fun poll() {
            if(cancelled)return
            val script="""(()=>{$prelude
                if(!choices.length){
                    const b=buttons.find(b=>${if(provider==VodProvider.DULO)"/^Sources?\\s/.test(b.textContent.trim())" else "false"})
                        || buttons.find(b=>${if(provider==VodProvider.DULO)"b.getAttribute('aria-label')==='Playback settings' || b.textContent.trim()==='Playback settings'" else "/^Quality/.test(b.textContent.trim())"});
                    if(b)b.click();else if(location.pathname.startsWith('/tv/'))document.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowDown',code:'ArrowDown',keyCode:40,bubbles:true}));return '[]';
                }
                return JSON.stringify(choices.map((b,i)=>({index:i,label:${if(provider==VodProvider.MOVY)"b.querySelector('img[alt]').alt" else "b.innerText.split(/\\n/).map(t=>t.trim()).filter(t=>t.length>1).slice(0,2).join(' · ')"},selected:b.getAttribute('aria-checked')==='true'||b.getAttribute('aria-pressed')==='true'||!!b.querySelector('[aria-label="Selected"]')||b.className.includes('bg-white/[0.14]')})));
            })()"""
            web.evaluateJavascript(script) { raw ->
                if(!cancelled) {
                    val rows=runCatching { val a=JSONArray(JSONTokener(raw).nextValue().toString());(0 until a.length()).map { i->
                        val o=a.getJSONObject(i);Choice(o.getInt("index"),o.getString("label").take(160),o.optBoolean("selected"))
                    } }.getOrDefault(emptyList())
                    if(rows.isNotEmpty() || attempts++>=10)done(rows)
                    else handler.postDelayed({poll()},350)
                }
            }
        }
        poll()
    }
    fun choose(choice: Choice, done: (Boolean)->Unit) {
        val label=org.json.JSONObject.quote(choice.label)
        // Re-check the label so a rerender cannot switch to a different row.
        web.evaluateJavascript("""(()=>{$prelude const b=choices[${choice.index}];if(!b)return false;
            const label=${if(provider==VodProvider.MOVY)"b.querySelector('img[alt]').alt" else "b.innerText.split(/\\n/).map(t=>t.trim()).filter(t=>t.length>1).slice(0,2).join(' · ')"};
            if(label!==$label)return false;b.click();return true;})()""") { raw ->
            if(!cancelled) {
                // Close only the provider's settings panel; do not touch media or ads.
                web.evaluateJavascript("""(()=>{const b=[...document.querySelectorAll('button')].find(b=>b.getAttribute('aria-label')==='Dismiss settings');if(b)b.click()})()""",null)
                done(raw=="true")
            }
        }
    }
}
