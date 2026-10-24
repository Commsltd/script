package com.commercialoutcomes.uktelevision

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONTokener

/** Mirrors the visible Plex consent dialog. Only a remote click submits a choice. */
class WebConsentControls(private val activity:Activity, private val web:WebView) {
    private val handler=Handler(Looper.getMainLooper())
    private var dialog:AlertDialog?=null
    private var stopped=false
    private var dismissed=""
    private var generation=0
    companion object {
        // Do not guess consent APIs or alter cookies. Use the buttons actually
        // presented by the provider and revalidate the notice before clicking.
        val inspect="""(()=>{const visible=e=>!!e&&e.getBoundingClientRect().width>0&&e.getBoundingClientRect().height>0&&getComputedStyle(e).visibility!=='hidden';const d=[...document.querySelectorAll('[role="dialog"]')].find(d=>visible(d)&&[...d.querySelectorAll('button')].some(b=>visible(b)&&b.textContent.trim()==='I Do Not Agree'));if(!d)return null;const buttons=[...d.querySelectorAll('button')].filter(visible).map(b=>b.textContent.trim());if(!buttons.includes('I Agree'))return null;return {text:d.innerText,buttons}})()"""
    }
    fun start() {
        stopped=false;dismissed="";val run=++generation;handler.removeCallbacksAndMessages(null)
        val poll=object:Runnable { override fun run() {
            if(stopped||activity.isFinishing||activity.isDestroyed||generation!=run)return
            if(dialog==null)check(false)
            handler.postDelayed(this,1500)
        }};handler.post(poll)
    }
    fun check(force:Boolean=true) {
        if(stopped||dialog!=null||!VodProvider.PLEX.owns(web.url.orEmpty()))return
        web.evaluateJavascript(inspect) { value ->
            if(stopped||activity.isFinishing||activity.isDestroyed||dialog!=null)return@evaluateJavascript
            val info=runCatching{JSONTokener(value).nextValue() as? JSONObject}.getOrNull()?:return@evaluateJavascript
            val notice=info.optString("text")
            if(notice.isBlank()||(!force&&dismissed==notice))return@evaluateJavascript
            val text=TextView(activity).apply { this.text=notice;textSize=17f;setPadding(28,12,28,16) }
            val scroll=ScrollView(activity).apply { addView(text);isFocusable=true }
            val box=AlertDialog.Builder(activity).setTitle("Plex · consent")
                .setView(scroll).setPositiveButton("I Agree"){_,_->choose(notice,"I Agree")}
                .setNegativeButton("I Do Not Agree"){_,_->choose(notice,"I Do Not Agree")}
                .setNeutralButton("Decide later",null).create()
            dialog=box
            box.setOnDismissListener { dismissed=notice;dialog=null;web.requestFocus() }
            box.show()
            box.window?.setLayout((activity.resources.displayMetrics.widthPixels*0.85).toInt(),(activity.resources.displayMetrics.heightPixels*0.85).toInt())
            box.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
        }
    }
    private fun choose(notice:String,label:String) {
        val text=JSONObject.quote(notice);val choice=JSONObject.quote(label)
        web.evaluateJavascript("""(()=>{const d=[...document.querySelectorAll('[role="dialog"]')].find(d=>d.getBoundingClientRect().width>0&&d.innerText===$text);const b=d&&[...d.querySelectorAll('button')].find(b=>b.textContent.trim()===$choice);if(b&&!b.disabled){b.click();return true}return false})()""",null)
    }
    fun close(){stopped=true;generation++;handler.removeCallbacksAndMessages(null);dialog?.dismiss();dialog=null}
}
