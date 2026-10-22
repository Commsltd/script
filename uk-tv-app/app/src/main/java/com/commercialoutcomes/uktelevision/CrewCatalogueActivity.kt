package com.commercialoutcomes.uktelevision

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Native browse UI; the separately installed Crew add-on supplies the directory. */
class CrewCatalogueActivity:ComponentActivity() {
    private var page by mutableStateOf(CrewCatalogue.Page(emptyList(),emptyList(),0))
    private var heading by mutableStateOf("The Crew · Films")
    private var message by mutableStateOf("Opening The Crew catalogue…")
    private var busy by mutableStateOf(false)
    private var section="movie"
    private val history=mutableListOf<Pair<String,CrewCatalogue.Page>>()
    private var job:Job?=null
    private val prefs get()=getSharedPreferences("vod-library",MODE_PRIVATE)
    private val token get()=getSharedPreferences("crew-test",MODE_PRIVATE).getString("token","").orEmpty()
    private val strictPrivacy get()=getSharedPreferences("preferences",MODE_PRIVATE).getString("privacyMode","")==PrivacyMode.STRICT.name
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        onBackPressedDispatcher.addCallback(this){up()}
        setContent{Browser()}
        val query=intent.getStringExtra("query").orEmpty()
        section=if(intent.getStringExtra("kind")=="show")"tv" else "movie"
        load(JSONObject().put("section",section).put("query",query),if(query.isBlank())"The Crew · ${if(section=="tv")"TV series" else "Films"}" else "The Crew · ‘$query’",false)
    }
    private suspend fun call(path:String,body:JSONObject=JSONObject()):JSONObject=withContext(Dispatchers.IO) {
        require(token.length>=32){"The Crew is not configured on this Firestick."}
        val c=URL("http://127.0.0.1:18765$path").openConnection() as HttpURLConnection
        c.connectTimeout=1500;c.readTimeout=4000;c.requestMethod="POST";c.doOutput=true
        c.setRequestProperty("Authorization","Bearer $token");c.setRequestProperty("Content-Type","application/json")
        try {
            c.outputStream.use{it.write(body.toString().toByteArray())}
            val code=c.responseCode
            val raw=(if(code<400)c.inputStream else c.errorStream).use { input ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=2*1024*1024){"Catalogue page too large"};out.write(buffer,0,n)}
                out.toString("UTF-8")
            }
            val result=JSONObject(raw);check(code<400){result.optString("error","The Crew is unavailable")};result
        }finally{c.disconnect()}
    }
    private suspend fun engine():JSONObject {
        val running=try{call("/status")}catch(e:CancellationException){throw e}catch(_:Exception){null}
        if(running!=null)return running
        val launch=packageManager.getLaunchIntentForPackage("org.xbmc.kodi")?:error("Kodi is not installed on this Firestick.")
        require(token.length>=32){"The Crew is not configured on this Firestick."}
        message="Opening The Crew on this Firestick…"
        startActivity(launch)
        try {
            repeat(35){delay(1000);val state=try{call("/status")}catch(e:CancellationException){throw e}catch(_:Exception){null};if(state!=null)return state}
            error("The local Crew engine did not start. Return to On Demand and try again.")
        }finally {
            if(currentCoroutineContext().isActive&&!isFinishing)startActivity(Intent(this@CrewCatalogueActivity,CrewCatalogueActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }
    private fun load(body:JSONObject,label:String,remember:Boolean=true) {
        if(busy)return
        job=lifecycleScope.launch {
            busy=true;message="Loading $label…"
            try {
                val state=engine()
                check(state.optJSONArray("capabilities")?.toString()?.contains("catalogue-v1")==true){"The Crew catalogue needs the matching UK TV bridge update. The older source-only bridge cannot browse titles."}
                call("/catalogue",body)
                val data=withTimeout(90000){
                    var result:JSONObject
                    do{delay(700);result=call("/status")}while(result.optString("status")=="cataloguing")
                    check(result.optString("status")=="catalogue-ready"){result.optString("error","Catalogue unavailable")}
                    result.getJSONObject("catalogue")
                }
                val next=CrewCatalogue.parse(data)
                if(remember)history.add(heading to page)
                if(history.size>20)history.removeAt(0)
                heading=label;page=next
                val old=VodSavedCodec.decode(prefs.getString("crew-catalogue-cache","").orEmpty()).titles
                // A bounded cache assists mixed-provider browsing; never limits the
                // live directories or their Next page entries.
                val cached=VodCatalogue.merge(next.titles+old).take(300)
                withContext(Dispatchers.IO){prefs.edit().putString("crew-catalogue-cache",VodSavedCodec.encode(VodSaved(titles=cached))).commit()}
                message=if(next.titles.isEmpty()&&next.folders.isEmpty())"No titles returned. Try another category or search." else "${next.titles.size} titles · ${next.folders.size} categories / pages · Sources checked when you choose to watch"
            }catch(e:TimeoutCancellationException){message="The Crew catalogue timed out. Choose Films or TV series to try again.";withContext(NonCancellable){runCatching{call("/quit")}}}
            catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"The Crew catalogue is unavailable"}
            finally{busy=false}
        }
    }
    private fun home(kind:String) {if(busy)return;section=kind;history.clear();load(JSONObject().put("section",kind),"The Crew · ${if(kind=="tv")"TV series" else "Films"}",false)}
    private fun up(){if(busy){job?.cancel();finish();return};if(history.isEmpty())finish() else {val previous=history.removeAt(history.lastIndex);heading=previous.first;page=previous.second;message="Choose a title, category or next page"}}
    private fun search() {
        if(busy)return
        val input=EditText(this).apply{setSingleLine(true);hint="Search ${if(section=="tv")"TV series" else "films"}";filters=arrayOf(android.text.InputFilter.LengthFilter(120))}
        val dialog=AlertDialog.Builder(this).setTitle("Search The Crew").setView(input).setPositiveButton("Search",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{val q=input.text.toString().trim();if(q.length<2)input.error="Enter at least two characters" else{dialog.dismiss();load(JSONObject().put("section",section).put("query",q),"The Crew · ‘$q’")}}};dialog.show()
    }
    private fun select(title:VodTitle){setResult(RESULT_OK,Intent().putExtra("title",VodSavedCodec.encode(VodSaved(titles=listOf(title)))));finish()}
    @Composable private fun Action(label:String,action:()->Unit){var focused by remember{mutableStateOf(false)};Text(label,color=if(focused)Color.Black else Color.White,fontSize=16.sp,modifier=Modifier.onFocusChanged{focused=it.isFocused}.background(if(focused)Color(0xFF64E7CB) else Color(0xFF223D50)).clickable(onClick=action).padding(12.dp))}
    @Composable private fun Browser(){
        val grid=rememberLazyGridState()
        LaunchedEffect(page){grid.scrollToItem(0)}
        Column(Modifier.fillMaxSize().background(Color(0xFF07131D)).padding(24.dp)){
            Text(heading,color=Color.White,fontSize=24.sp)
            Row(Modifier.padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Action("Films"){home("movie")};Action("TV series"){home("tv")};Action("Search The Crew"){search()};Action("Up"){up()};Action("On Demand"){finish()}
            }
            Text(message,color=Color(0xFFB9CAD8),fontSize=14.sp,modifier=Modifier.padding(bottom=12.dp))
            if(!busy)LazyVerticalGrid(columns=GridCells.Fixed(5),state=grid,verticalArrangement=Arrangement.spacedBy(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.weight(1f)){
                items(page.folders,key={"folder:"+it.id}){folder->Action("▸ ${folder.label}"){load(JSONObject().put("folder",folder.id).put("section",section),folder.label)}}
                items(page.titles,key={it.key}){title->var focused by remember{mutableStateOf(false)}
                    Column(Modifier.onFocusChanged{focused=it.isFocused}.border(if(focused)3.dp else 0.dp,Color(0xFF64E7CB)).background(Color(0xFF132637)).clickable{select(title)}.padding(6.dp)){
                        AsyncImage(model=if(strictPrivacy)null else title.poster,contentDescription=null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxWidth().aspectRatio(2f/3f))
                        Text(title.title,color=Color.White,fontSize=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(listOfNotNull(title.year?.toString(),if(title.kind=="show")"TV series" else "Film").joinToString(" · "),color=Color(0xFF64E7CB),fontSize=12.sp)
                    }
                }
            }
        }
    }
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {if(keyCode==KeyEvent.KEYCODE_MENU){if(event.repeatCount==0)search();return true};return super.onKeyDown(keyCode,event)}
    override fun onDestroy(){job?.cancel();CoroutineScope(Dispatchers.IO).launch{runCatching{call("/quit")}};super.onDestroy()}
}
