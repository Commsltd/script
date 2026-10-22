package com.commercialoutcomes.uktelevision

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.view.View
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

class OnDemandActivity : ComponentActivity() {
    var catalogue by mutableStateOf(VodResult(emptyList(), emptyMap())); private set
    private var message by mutableStateOf("Loading free films and TV series through your current connection…")
    private var pendingTitle: VodTitle? = null
    private val playerLauncher=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==RESULT_FIRST_USER)pendingTitle?.let { details(it) }
    }
    private var filter by mutableStateOf("All")
    private var query by mutableStateOf("")
    private var loading by mutableStateOf(false)
    private val strictPrivacy get()=getSharedPreferences("preferences",MODE_PRIVATE).getString("privacyMode","")==PrivacyMode.STRICT.name
    val isLoading: Boolean get() = loading
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContent { Library() }
        refresh()
    }
    private fun refresh() {
        if(loading)return
        loading=true
        message="Loading free films and TV series through your current connection…"
        lifecycleScope.launch {
            catalogue=VodCatalogue.load()
            if(catalogue.titles.none { t->t.sources.any { it.provider==VodProvider.TUBI } }) {
                message="Loading Tubi’s catalogue…"
                runCatching { VodWebCatalogue.fetch(this@OnDemandActivity,VodProvider.TUBI) }.onSuccess { doc ->
                    val extra=VodCatalogue.parse(VodProvider.TUBI,doc)
                    catalogue=catalogue.copy(titles=VodCatalogue.merge(catalogue.titles+extra),
                        statuses=catalogue.statuses+(VodProvider.TUBI to "${extra.size} titles loaded"),
                        nextPages=catalogue.nextPages+(VodProvider.TUBI to VodCatalogue.collections(VodProvider.TUBI,doc)))
                }.onFailure { catalogue=catalogue.copy(statuses=catalogue.statuses+(VodProvider.TUBI to (it.message ?: "Catalogue unavailable"))) }
            }
            // Expand beyond provider home-page highlights while keeping the
            // native library visible and usable as each batch arrives.
            repeat(2) {
                if(catalogue.nextPages.values.any { it.isNotEmpty() }) {
                    message="${catalogue.titles.size} titles · Adding more films and series…"
                    catalogue=VodCatalogue.more(catalogue)
                }
            }
            loading=false
            message="${catalogue.titles.size} titles · Free with adverts · Availability follows your connection"
        }
    }
    private fun more() {
        if(loading)return
        if(catalogue.nextPages.values.all { it.isEmpty() }) { status();return }
        loading=true;message="Loading more films and series…"
        lifecycleScope.launch { catalogue=VodCatalogue.more(catalogue);loading=false;message="${catalogue.titles.size} titles · Free with adverts" }
    }
    private fun search() {
        val input=EditText(this).apply { setSingleLine(true);setText(query);hint="Search titles already loaded" }
        AlertDialog.Builder(this).setTitle("Search loaded titles").setView(input)
            .setPositiveButton("Search") { _, _ -> query=input.text.toString().trim() }
            .setNeutralButton("Clear") { _, _ -> query="" }.setNegativeButton("Cancel",null).show()
    }
    private fun details(title: VodTitle) {
        val sources=title.sources
        AlertDialog.Builder(this).setTitle(title.title)
            .setItems((sources.map { "${if(title.kind=="show") "Episodes" else "Watch"} · ${it.provider.label}" }+listOf("About this title")).toTypedArray()) { _, i ->
                if(i==sources.size) AlertDialog.Builder(this).setTitle(title.title).setMessage(title.subtitle+"\n\n"+title.description.ifBlank { "Choose a source to see its current availability." }).setPositiveButton("Back",null).show()
                else if(title.kind=="show") showEpisodes(title,sources[i]) else play(title,sources[i])
            }.setNegativeButton("Back",null).show()
    }
    private fun showEpisodes(title: VodTitle, source: VodSource) {
        message="Loading episodes of ${title.title}…"
        lifecycleScope.launch {
            val episodes=runCatching { VodEpisodes.load(source) }
            episodes.onSuccess { rows ->
                if(rows.isEmpty()) failure("${source.provider.label} did not supply an episode list on this connection.")
                else AlertDialog.Builder(this@OnDemandActivity).setTitle(title.title)
                    .setItems(rows.map { it.title }.toTypedArray()) { _,i -> if(rows[i].kind=="season")showEpisodes(rows[i],rows[i].sources.first()) else play(rows[i],rows[i].sources.first()) }
                    .setNegativeButton("Back",null).show()
            }.onFailure { failure(it.message.orEmpty()) }
            message="${catalogue.titles.size} titles · Free with adverts"
        }
    }
    private fun play(title: VodTitle, source: VodSource) {
        message="Opening ${title.title} · ${source.provider.label}…"
        lifecycleScope.launch {
            runCatching { VodCatalogue.playbackPage(source) }.onSuccess { url ->
                pendingTitle=title
                playerLauncher.launch(Intent(this@OnDemandActivity,VodPlayerActivity::class.java)
                    .putExtra("url",url).putExtra("provider",source.provider.name).putExtra("title",title.title).putExtra("alternativeCount",title.sources.size-1))
            }.onFailure { failure(it.message.orEmpty()) }
            message="${catalogue.titles.size} titles · Free with adverts"
        }
    }
    private fun failure(message: String) { AlertDialog.Builder(this).setTitle("Source unavailable").setMessage(message+"\n\nChoose another source if one is listed for this title.").setPositiveButton("Back",null).show() }
    private fun status() { AlertDialog.Builder(this).setTitle("On Demand services").setMessage(catalogue.statuses.entries.joinToString("\n\n") { "${it.key.label}: ${it.value}" }).setPositiveButton("Back",null).show() }
    override fun onKeyDown(keyCode: Int,event: KeyEvent): Boolean {
        if(keyCode==KeyEvent.KEYCODE_MENU) { status();return true }
        if(keyCode==KeyEvent.KEYCODE_SEARCH) { search();return true }
        return super.onKeyDown(keyCode,event)
    }
    @Composable private fun Library() {
        val ink=Color(0xFF071019);val mint=Color(0xFF63E6C6)
        val rows=catalogue.titles.filter { title ->
            (filter=="All" || filter=="Films" && title.kind=="movie" || filter=="TV series" && title.kind=="show" || title.sources.any { it.provider.label==filter }) &&
                (query.isBlank() || title.title.contains(query,true)) }
        val initial=remember { FocusRequester() }
        Column(Modifier.fillMaxSize().background(ink).padding(24.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text("UK TELEVISION",color=mint,fontSize=19.sp,fontWeight=FontWeight.Bold)
                Text("ON DEMAND",color=Color.White,fontSize=24.sp,fontWeight=FontWeight.Bold)
                Text("BACK  Live TV",color=Color(0xFF9EADBC),fontSize=13.sp)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                for((i,label) in (listOf("All","Films","TV series")+VodProvider.entries.map { it.label }+listOf("Search","More titles","Refresh")).withIndex()) {
                    var focused by remember { mutableStateOf(false) }
                    Box(Modifier.then(if(i==0)Modifier.focusRequester(initial) else Modifier)
                        .onFocusChanged { focused=it.isFocused }.background(if(focused)mint else Color(0xFF162A3C),RoundedCornerShape(6.dp))
                        .clickable { when(label) { "Search"->search();"Refresh"->refresh();"More titles"->more();else->filter=label } }.padding(horizontal=13.dp,vertical=9.dp)) {
                        Text(label,color=if(focused)ink else if(label==filter)mint else Color.White,fontSize=13.sp)
                    }
                }
            }
            Text(if(query.isBlank())message else "${rows.size} results for ‘$query’",color=Color(0xFF9EADBC),fontSize=12.sp,modifier=Modifier.padding(vertical=10.dp))
            if(!loading && rows.isEmpty())Text("No titles to show. MENU shows service availability; Refresh retries on your current connection.",color=Color.White)
            LazyVerticalGrid(columns=GridCells.Fixed(6),verticalArrangement=Arrangement.spacedBy(15.dp),horizontalArrangement=Arrangement.spacedBy(15.dp),modifier=Modifier.weight(1f)) {
                items(rows,key={it.key}) { title ->
                    var focused by remember { mutableStateOf(false) }
                    Column(Modifier.onFocusChanged { focused=it.isFocused }.border(if(focused)3.dp else 0.dp,if(focused)mint else Color.Transparent,RoundedCornerShape(6.dp))
                        .background(Color(0xFF101F2E),RoundedCornerShape(6.dp)).clickable { details(title) }.padding(5.dp)) {
                        AsyncImage(model=if(strictPrivacy)null else title.poster,contentDescription=null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxWidth().aspectRatio(2f/3f).background(Color(0xFF223A50)))
                        Text(title.title,color=Color.White,fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.height(42.dp).padding(top=5.dp))
                        Text(listOfNotNull(title.year?.toString(),if(title.sources.size>1)"${title.sources.size} sources" else title.sources.first().provider.label).joinToString(" · "),color=mint,fontSize=11.sp)
                    }
                }
            }
            Text("↑↓←→ Browse    OK Details & sources    MENU Service status",color=Color(0xFF9EADBC),fontSize=12.sp,modifier=Modifier.padding(top=10.dp))
        }
        LaunchedEffect(Unit) { initial.requestFocus() }
    }
}
