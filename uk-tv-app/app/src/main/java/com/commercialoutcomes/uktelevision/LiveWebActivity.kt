package com.commercialoutcomes.uktelevision

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView

/** Compatibility host; normal channel playback now uses the guide's own player. */
class LiveWebActivity:Activity(){
    companion object {
        val pages=mapOf(
            "TTT.tt" to "https://player.tegotv.com/player.php?channel=1&about=0&chat=0&donation=0&epg=0&autoplay=1",
            "CCNTV6.tt" to "https://www.tv6tnt.com/watch_live/",
            "TrinityTV.tt" to "https://player.tegotv.com/player.php?channel=6&about=0&chat=0&donation=0&epg=0&autoplay=1"
        )
    }
    private lateinit var player:LiveWebPlayer
    val web:WebView get()=player.web
    val pageErrors get()=player.pageErrors
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val id=intent.getStringExtra("station").orEmpty();if(id !in pages){finish();return}
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        player=LiveWebPlayer(this,id);setContentView(player)
    }
    override fun onKeyDown(code:Int,event:KeyEvent):Boolean{
        if(code==KeyEvent.KEYCODE_MENU){AlertDialog.Builder(this).setItems(arrayOf("Broadcaster controls","Reload channel","Return to TV guide")){_,i->when(i){0->player.showControls();1->player.reload();else->finish()}}.show();return true}
        return super.onKeyDown(code,event)
    }
    override fun onDestroy(){if(::player.isInitialized)player.close();super.onDestroy()}
}
