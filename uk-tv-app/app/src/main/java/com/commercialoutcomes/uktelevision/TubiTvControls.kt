package com.commercialoutcomes.uktelevision

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import android.widget.*
import org.json.JSONObject
import org.json.JSONTokener

/** Native remote controls around Tubi's own player. No stream extraction or ad skipping. */
class TubiTvControls(
    private val activity: Activity,
    private val root: FrameLayout,
    private val web: WebView,
    title: String,
    private val options: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val script = activity.assets.open("tubi-tv.js").bufferedReader().use { it.readText() }
    private val layer = FrameLayout(activity)
    private val loading = TextView(activity).apply {
        text = "$title\n\nOpening Tubi…\n\nMENU Options     BACK Library"
        setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER; setBackgroundColor(Color.BLACK)
    }
    private val panel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(24),dp(12),dp(24),dp(12));setBackgroundColor(0xEE07131D.toInt())
    }
    private val status = TextView(activity).apply { setTextColor(Color.WHITE);textSize=17f }
    private val row = LinearLayout(activity).apply { gravity=Gravity.CENTER }
    private lateinit var play: Button
    private var noticeDialog: AlertDialog? = null
    private var closed=false
    private var suspended=false
    private var generation=0
    private var began=SystemClock.elapsedRealtime()
    private var hideAt=0L
    private var hasVideo=false
    private var lastSeek=0L
    private var lastSeekForward:Boolean?=null
    private var lastControl:View?=null
    private var wasPaused=false
    private var hiddenByUser=false
    private val consumedKeys=mutableSetOf<Int>()
    private var lastState=JSONObject()
    private val poll = object:Runnable {
        override fun run() {
            if(closed||suspended||activity.isFinishing||activity.isDestroyed)return
            val run=generation
            if(!VodProvider.TUBI.owns(web.url.orEmpty())) { handler.postDelayed(this,1000);return }
            web.evaluateJavascript("$script;window.ukTvTubi.snapshot()") { value ->
                if(closed||suspended||run!=generation)return@evaluateJavascript
                val state=runCatching{JSONTokener(value).nextValue() as? JSONObject}.getOrNull()
                if(state!=null)update(state)
                handler.postDelayed(this,1000)
            }
        }
    }
    init {
        layer.addView(loading,FrameLayout.LayoutParams(-1,-1))
        panel.addView(TextView(activity).apply { text=title;setTextColor(0xFF64E7CB.toInt());textSize=20f })
        panel.addView(status)
        panel.addView(row)
        button("Rewind") { seek(false) }
        play=button("Play / Pause") { toggle() }
        button("Forward") { seek(true) }
        button("Subtitles on / off") { captions() }
        button("Options") { options() }
        for(i in 0 until row.childCount) {
            val b=row.getChildAt(i);b.nextFocusUpId=b.id;b.nextFocusDownId=b.id
            b.nextFocusLeftId=row.getChildAt((i-1).coerceAtLeast(0)).id
            b.nextFocusRightId=row.getChildAt((i+1).coerceAtMost(row.childCount-1)).id
        }
        panel.addView(TextView(activity).apply {
            text="OK Play / pause    ← → Seek when controls are hidden    BACK Hide / library"
            setTextColor(0xFFB9CAD8.toInt());textSize=13f
        })
        layer.addView(panel,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        root.addView(layer,FrameLayout.LayoutParams(-1,-1))
        panel.visibility=View.GONE
        handler.post(poll)
    }
    private fun dp(value:Int)=(value*activity.resources.displayMetrics.density).toInt()
    private fun button(label:String,action:()->Unit):Button = Button(activity).apply {
        id=View.generateViewId();text=label;isAllCaps=false;textSize=16f;isFocusable=true
        fun paint(focused:Boolean) {
            setTextColor(if(focused)Color.BLACK else Color.WHITE)
            background=GradientDrawable().apply { cornerRadius=dp(7).toFloat();setColor(if(focused)0xFF64E7CB.toInt() else 0xFF223D50.toInt()) }
        }
        paint(false)
        setOnFocusChangeListener{_,focused->paint(focused);if(focused){lastControl=this;hideAt=SystemClock.elapsedRealtime()+6000}}
        setOnClickListener{hideAt=SystemClock.elapsedRealtime()+6000;action()}
        row.addView(this,LinearLayout.LayoutParams(0,dp(48),1f).apply { setMargins(dp(4),dp(10),dp(4),dp(10)) })
    }
    private fun update(state:JSONObject) {
        lastState=state
        state.optJSONObject("notice")?.let { showNotice(it);return }
        noticeDialog?.dismiss();noticeDialog=null
        val ready=state.optBoolean("ready")
        hasVideo=ready
        loading.visibility=if(ready)View.GONE else View.VISIBLE
        if(!ready) {
            panel.visibility=View.GONE
            if(SystemClock.elapsedRealtime()-began>45000)loading.text="Tubi is taking longer to open\n\n${state.optString("message").take(240).ifBlank { "Press MENU to reload or choose another provider." }}\n\nBACK Library"
            return
        }
        val duration=state.optDouble("duration",0.0).toInt()
        val position=state.optDouble("time",0.0).toInt()
        status.text=if(duration>0)"${clock(position)} / ${clock(duration)} · Tubi" else "Tubi is preparing playback…"
        // Use the provider control's label: its main video may be paused during adverts.
        val paused=state.optString("label").startsWith("Play ")||(state.optString("label").isBlank()&&state.optBoolean("paused")&&!state.optBoolean("advert"))
        play.text=if(paused)"Play" else "Pause"
        if(paused&&!wasPaused&&!hiddenByUser&&panel.visibility!=View.VISIBLE)show()
        wasPaused=paused
        if(hideAt>0&&SystemClock.elapsedRealtime()>hideAt&&!paused)hide()
    }
    private fun clock(seconds:Int):String=if(seconds>=3600)"%d:%02d:%02d".format(seconds/3600,seconds/60%60,seconds%60) else "%d:%02d".format(seconds/60,seconds%60)
    fun show() {
        if(closed||noticeDialog!=null)return
        hiddenByUser=false;panel.visibility=View.VISIBLE;panel.bringToFront();play.requestFocus();hideAt=SystemClock.elapsedRealtime()+6000
    }
    private fun hide(user:Boolean=false){hiddenByUser=user;panel.visibility=View.GONE;hideAt=0}
    private fun toggle() {
        if(!hasVideo)return
        web.evaluateJavascript("window.ukTvTubi?.toggle()",null)
        show()
    }
    private fun sendShortcut(code:Int) {
        if(closed||suspended)return
        val key=when(code){KeyEvent.KEYCODE_K->"k";KeyEvent.KEYCODE_J->"j";KeyEvent.KEYCODE_L->"l";KeyEvent.KEYCODE_C->"c";else->return}
        web.evaluateJavascript("window.ukTvTubi?.shortcut(${JSONObject.quote(key)})",null)
    }
    private fun seek(forward:Boolean) {
        val now=SystemClock.elapsedRealtime()
        if(!hasVideo||(lastSeekForward==forward&&now-lastSeek<300))return
        lastSeek=now;lastSeekForward=forward
        // Tubi's own shortcut enforces its seek/ad rules. Do not change currentTime.
        sendShortcut(if(forward)KeyEvent.KEYCODE_L else KeyEvent.KEYCODE_J)
    }
    private fun captions() {
        sendShortcut(KeyEvent.KEYCODE_C)
        Toast.makeText(activity,"Subtitles toggled where available",Toast.LENGTH_SHORT).show()
    }
    fun key(event:KeyEvent):Boolean {
        if(closed||noticeDialog!=null)return false
        val code=event.keyCode
        val down=event.action==KeyEvent.ACTION_DOWN
        if(!down&&consumedKeys.remove(code))return true
        fun consume():Boolean {if(down)consumedKeys.add(code);return true}
        if(code==KeyEvent.KEYCODE_BACK) {
            if(panel.visibility!=View.VISIBLE)return false
            if(down&&event.repeatCount==0)hide(true)
            return consume()
        }
        if(code in setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE)) {
            if(down&&event.repeatCount==0) {
                val paused=lastState.optString("label").startsWith("Play ")||(lastState.optString("label").isBlank()&&lastState.optBoolean("paused")&&!lastState.optBoolean("advert"))
                if(code==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||(code==KeyEvent.KEYCODE_MEDIA_PLAY&&paused)||(code==KeyEvent.KEYCODE_MEDIA_PAUSE&&!paused))toggle()
            };return consume()
        }
        if(code==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD||code==KeyEvent.KEYCODE_MEDIA_REWIND) {
            if(down)seek(code==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);return consume()
        }
        if(code !in setOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER))return false
        if(panel.visibility==View.VISIBLE) {
            hideAt=SystemClock.elapsedRealtime()+6000
            // WebView can retain accessibility focus after a seek. Route every
            // D-pad action to the selected native control rather than its page.
            if(down) {
                val selected=lastControl ?: play
                when(code) {
                    KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> if(event.repeatCount==0)selected.performClick()
                    KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val index=row.indexOfChild(selected).coerceAtLeast(0)
                        val next=(index+if(code==KeyEvent.KEYCODE_DPAD_RIGHT)1 else -1).coerceIn(0,row.childCount-1)
                        row.getChildAt(next).requestFocus()
                    }
                    else -> selected.requestFocus()
                }
            }
            return consume()
        }
        if(down)when(code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> seek(false)
            KeyEvent.KEYCODE_DPAD_RIGHT -> seek(true)
            KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> if(event.repeatCount==0)toggle()
            else -> if(event.repeatCount==0)show()
        }
        return consume()
    }
    private fun showNotice(notice:JSONObject) {
        if(noticeDialog!=null)return
        val text=notice.optString("text")
        val labels=notice.optJSONArray("buttons")?:return
        if(labels.length()==0)return
        val content=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(12),dp(18),dp(12))}
        content.addView(TextView(activity).apply {this.text=text;textSize=17f;setTextColor(Color.WHITE)})
        val scroll=ScrollView(activity).apply {addView(content)}
        val dialog=AlertDialog.Builder(activity).setTitle("Tubi · player notice").setView(scroll).setNegativeButton("Return to library"){_,_->activity.finish()}.create()
        noticeDialog=dialog
        for(i in 0 until labels.length()) {
            val label=labels.getString(i)
            content.addView(Button(activity).apply {this.text=label;isAllCaps=false;setOnClickListener {
                web.evaluateJavascript("window.ukTvTubi?.chooseNotice(${JSONObject.quote(text)},${JSONObject.quote(label)},$i)") { changed ->
                    if(!closed&&changed=="true"){dialog.dismiss();noticeDialog=null}
                }
            }})
        }
        dialog.setOnCancelListener{activity.finish()}
        dialog.show();dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels*0.85).toInt(),(activity.resources.displayMetrics.heightPixels*0.85).toInt())
        // No preselected consent action. The remote must explicitly select a button.
        scroll.isFocusable=true;scroll.requestFocus()
    }
    fun pause(){suspended=true;generation++;handler.removeCallbacksAndMessages(null)}
    fun resume(){if(closed)return;suspended=false;generation++;handler.removeCallbacksAndMessages(null);handler.post(poll)}
    fun close(){closed=true;generation++;handler.removeCallbacksAndMessages(null);noticeDialog?.dismiss();noticeDialog=null;root.removeView(layer)}
}
