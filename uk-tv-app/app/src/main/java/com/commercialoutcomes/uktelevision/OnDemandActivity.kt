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
    private var browse by mutableStateOf(VodBrowse())
    private var saved by mutableStateOf(VodSaved())
    private var shelf by mutableStateOf("browse")
    private val libraryPreferences get()=getSharedPreferences("vod-library",MODE_PRIVATE)
    private val allTitles by derivedStateOf { saved.available(catalogue.titles) }
    val visibleTitles: List<VodTitle> by derivedStateOf {
        val keys=when(shelf) { "browse"->null;"favourites"->saved.favourites;else->saved.lists.firstOrNull { it.id==shelf }?.keys ?: emptySet() }
        browse.apply(allTitles.filter { keys==null || saved.keyFor(it) in keys })
    }
    private fun save(value: VodSaved) { saved=value;libraryPreferences.edit().putString("saved",VodSavedCodec.encode(value)).apply() }
    private fun openShelf(value: String) { shelf=value;browse=VodBrowse(sort=browse.sort) }
    private val shelfLabel get()=when(shelf) { "browse"->"Browse";"favourites"->"Favourites";else->saved.lists.firstOrNull { it.id==shelf }?.name ?: "My list" }
    private var loading by mutableStateOf(false)
    private val expandedGenres=mutableSetOf<String>()
    private val checkedAlternatives=mutableSetOf<String>()
    private val strictPrivacy get()=getSharedPreferences("preferences",MODE_PRIVATE).getString("privacyMode","")==PrivacyMode.STRICT.name
    val isLoading: Boolean get() = loading
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        saved=VodSavedCodec.decode(libraryPreferences.getString("saved","").orEmpty())
        setContent { Library() }
        refresh()
    }
    private fun refresh() {
        if(loading)return
        loading=true
        expandedGenres.clear()
        message="Loading free films and TV series through your current connection…"
        lifecycleScope.launch {
            catalogue=VodCatalogue.load()
            for(provider in listOf(VodProvider.TUBI,VodProvider.DULO))if(catalogue.titles.none { t->t.sources.any { it.provider==provider } }) {
                message="Loading ${provider.label}’s catalogue…"
                runCatching { VodWebCatalogue.fetch(this@OnDemandActivity,provider) }.onSuccess { doc ->
                    val extra=VodCatalogue.parse(provider,doc)
                    catalogue=catalogue.copy(titles=VodCatalogue.merge(catalogue.titles+extra),
                        statuses=catalogue.statuses+(provider to "${extra.size} titles loaded"),
                        nextPages=catalogue.nextPages+(provider to VodCatalogue.collections(provider,doc)))
                }.onFailure { catalogue=catalogue.copy(statuses=catalogue.statuses+(provider to (it.message ?: "Catalogue unavailable"))) }
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
            expandGenre()
        }
    }
    private fun more() {
        if(loading)return
        if(catalogue.nextPages.values.all { it.isEmpty() }) { status();return }
        loading=true;message="Loading more films and series…"
        lifecycleScope.launch { catalogue=VodCatalogue.more(catalogue);loading=false;message="${catalogue.titles.size} titles · Free with adverts" }
    }
    private fun choose(title: String, labels: List<String>, selected: Int = -1, action: (Int)->Unit) {
        AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels.toTypedArray(),selected) { dialog,i -> dialog.dismiss();action(i) }.setNegativeButton("Back",null).show()
    }
    private fun genres() {
        val values=listOf("All genres")+allTitles.flatMap { it.genres }.distinct().sorted()+if(allTitles.any { it.genres.isEmpty() })listOf("Unknown genre") else emptyList()
        choose("Genre",values,values.indexOf(browse.genre.ifBlank { "All genres" })) { i ->
            browse=browse.copy(genre=if(i==0)"" else values[i]);expandGenre()
        }
    }
    private fun expandGenre() {
        if(loading || browse.genre.isBlank() || browse.genre=="Unknown genre" || browse.genre in expandedGenres)return
        val genre=browse.genre
        expandedGenres.add(genre)
        loading=true
        lifecycleScope.launch {
            catalogue=VodCatalogue.forGenre(catalogue,genre)
            loading=false;message="${catalogue.titles.size} titles loaded"
        }
    }
    private fun years() {
        val years=allTitles.mapNotNull { it.year }.distinct().sortedDescending()
        val decades=years.map { it/10*10 }.distinct()
        val labels=listOf("All years","Choose an exact year")+decades.map { "${it}s" }+"Unknown year"
        choose("Release year",labels,labels.indexOf(browse.yearLabel)) { i -> when {
            i==0->browse=browse.copy(fromYear=null,toYear=null,unknownYear=false)
            i==1->choose("Release year",years.map { it.toString() },years.indexOf(browse.fromYear)) { n -> browse=browse.copy(fromYear=years[n],toYear=years[n],unknownYear=false) }
            i==labels.lastIndex->browse=browse.copy(fromYear=null,toYear=null,unknownYear=true)
            else->{val start=decades[i-2];browse=browse.copy(fromYear=start,toYear=start+9,unknownYear=false)}
        } }
    }
    private fun providers() {
        val values=listOf("All providers")+VodProvider.entries.map { it.label }
        choose("Provider",values,browse.provider?.ordinal?.plus(1) ?: 0) { i -> browse=browse.copy(provider=if(i==0)null else VodProvider.entries[i-1]) }
    }
    private fun sorting()=choose("Sort titles",VodSort.entries.map { it.label },browse.sort.ordinal) { browse=browse.copy(sort=VodSort.entries[it]) }
    private fun search() {
        val input=EditText(this).apply { setSingleLine(true);setText(browse.query);hint="Search titles already loaded" }
        AlertDialog.Builder(this).setTitle("Search loaded titles").setView(input)
            .setPositiveButton("Search") { _, _ -> browse=browse.copy(query=input.text.toString().trim()) }
            .setNeutralButton("Clear") { _, _ -> browse=browse.copy(query="") }.setNegativeButton("Cancel",null).show()
    }
    private fun nameList(existing: VodList? = null, titleToAdd: VodTitle? = null) {
        val input=EditText(this).apply { setSingleLine(true);setText(existing?.name.orEmpty());hint="e.g. Weekend films";filters=arrayOf(android.text.InputFilter.LengthFilter(60)) }
        val dialog=AlertDialog.Builder(this).setTitle(if(existing==null)"New list" else "Rename list").setView(input).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {
                var next=if(existing==null)saved.createList(input.text.toString()) else saved.renameList(existing.id,input.text.toString())
                val id=existing?.id ?: next.lists.last().id
                if(titleToAdd!=null)next=next.setLists(titleToAdd,next.lists.filter { next.keyFor(titleToAdd) in it.keys }.map { it.id }.toSet()+id)
                save(next);dialog.dismiss()
            }.onFailure { input.error=it.message }
        } };dialog.show()
    }
    private fun myLists() {
        val values=saved.lists.map { "${it.name} (${it.keys.size})" }+"+ New list"
        choose("My lists",values) { i -> if(i==saved.lists.size)nameList() else openShelf(saved.lists[i].id) }
    }
    private fun addToLists(title: VodTitle) {
        if(saved.lists.isEmpty()) { nameList(titleToAdd=title);return }
        val lists=saved.lists;val selected=lists.map { saved.keyFor(title) in it.keys }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("Save to lists · ${title.title}")
            .setMultiChoiceItems(lists.map { it.name }.toTypedArray(),selected) { _,i,checked -> selected[i]=checked }
            .setPositiveButton("Save") { _,_->save(saved.setLists(title,lists.filterIndexed { i,_->selected[i] }.map { it.id }.toSet())) }
            .setNeutralButton("New list") { _,_->nameList(titleToAdd=title) }.setNegativeButton("Cancel",null).show()
    }
    private fun manageList() {
        val list=saved.lists.firstOrNull { it.id==shelf } ?: return
        choose(list.name,listOf("Rename list","Delete list")) { i -> if(i==0)nameList(list) else
            AlertDialog.Builder(this).setTitle("Delete ${list.name}?").setMessage("Your favourites and other lists will stay saved.")
                .setPositiveButton("Delete") { _,_->save(saved.deleteList(list.id));openShelf("browse") }.setNegativeButton("Cancel",null).show()
        }
    }
    private fun menu() {
        val labels=mutableListOf("Genre","Release year","Provider","Sort titles","Search","Favourites","My lists","New list","Clear filters","More titles","Refresh","Service status")
        if(saved.lists.any { it.id==shelf })labels.add("Manage this list")
        choose("On Demand",labels) { i -> when(labels[i]) {
            "Genre"->genres();"Release year"->years();"Provider"->providers();"Sort titles"->sorting();"Search"->search()
            "Favourites"->openShelf("favourites");"My lists"->myLists();"New list"->nameList();"Clear filters"->{browse=VodBrowse()}
            "More titles"->more();"Refresh"->refresh();"Manage this list"->manageList();else->status()
        } }
    }
    private fun details(title: VodTitle, checked: Boolean = false) {
        if(!checked && title.key !in checkedAlternatives) {
            message="Checking alternative sources for ${title.title}…"
            lifecycleScope.launch {
                val enriched=VodCatalogue.alternatives(title)
                checkedAlternatives.add(title.key)
                catalogue=catalogue.copy(titles=VodCatalogue.merge(catalogue.titles+enriched))
                details(enriched,true)
                message="${catalogue.titles.size} titles · MENU during playback to change stream"
            }
            return
        }
        val sources=title.sources
        val actions=sources.map { "${if(title.kind=="show") "Choose episode" else "Watch"} · ${it.provider.label}" }+
            listOf(if(saved.isFavourite(title))"★ Remove from favourites" else "☆ Add to favourites","Save to lists","About this title")+
            if(saved.lists.any { it.id==shelf })listOf("Remove from this list") else emptyList()
        AlertDialog.Builder(this).setTitle(title.title).setItems(actions.toTypedArray()) { _,i -> when {
            i<sources.size->if(title.kind=="show" && sources[i].provider!=VodProvider.DULO)showEpisodes(title,sources[i]) else play(title,sources[i])
            i==sources.size->save(saved.toggleFavourite(title))
            i==sources.size+1->addToLists(title)
            i==sources.size+2->AlertDialog.Builder(this).setTitle(title.title).setMessage(title.subtitle+"\n"+title.genres.joinToString(" · ").ifBlank { "Genre not supplied" }+"\n\n"+title.description.ifBlank { "Choose a source to see its current availability." }).setPositiveButton("Back",null).show()
            else->save(saved.setLists(title,saved.lists.filter { it.id!=shelf && saved.keyFor(title) in it.keys }.map { it.id }.toSet()))
        } }.setNegativeButton("Back",null).show()
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
                    .putExtra("url",url).putExtra("provider",source.provider.name).putExtra("title",title.title).putExtra("alternativeCount",title.sources.size-1)
                    .putExtra("sourceUrls",title.sources.map { it.url }.toTypedArray()).putExtra("sourceProviders",title.sources.map { it.provider.name }.toTypedArray())
                    .putExtra("sourceIndex",title.sources.indexOf(source)))
            }.onFailure { failure(it.message.orEmpty()) }
            message="${catalogue.titles.size} titles · Free with adverts"
        }
    }
    private fun failure(message: String) { AlertDialog.Builder(this).setTitle("Source unavailable").setMessage(message+"\n\nChoose another source if one is listed for this title.").setPositiveButton("Back",null).show() }
    private fun status() { AlertDialog.Builder(this).setTitle("On Demand services").setMessage(catalogue.statuses.entries.joinToString("\n\n") { "${it.key.label}: ${it.value}" }).setPositiveButton("Back",null).show() }
    override fun onKeyDown(keyCode: Int,event: KeyEvent): Boolean {
        if(keyCode==KeyEvent.KEYCODE_MENU) { menu();return true }
        if(keyCode==KeyEvent.KEYCODE_SEARCH) { search();return true }
        return super.onKeyDown(keyCode,event)
    }
    @Composable private fun LibraryButton(label: String,selected: Boolean = false,modifier: Modifier = Modifier,onClick: ()->Unit) {
        var focused by remember { mutableStateOf(false) }
        val ink=Color(0xFF071019);val mint=Color(0xFF63E6C6)
        Box(modifier.onFocusChanged { focused=it.isFocused }.background(if(focused)mint else Color(0xFF162A3C),RoundedCornerShape(6.dp))
            .border(if(selected)1.dp else 0.dp,if(selected)mint else Color.Transparent,RoundedCornerShape(6.dp))
            .clickable(onClick=onClick).padding(horizontal=12.dp,vertical=8.dp)) {
            Text(label,color=if(focused)ink else if(selected)mint else Color.White,fontSize=13.sp)
        }
    }
    @Composable private fun Library() {
        val ink=Color(0xFF071019);val mint=Color(0xFF63E6C6)
        val rows=visibleTitles
        val initial=remember { FocusRequester() }
        val grid=rememberLazyGridState()
        LaunchedEffect(browse,shelf) { grid.scrollToItem(0) }
        Column(Modifier.fillMaxSize().background(ink).padding(24.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text("UK TELEVISION",color=mint,fontSize=19.sp,fontWeight=FontWeight.Bold)
                Text("ON DEMAND",color=Color.White,fontSize=24.sp,fontWeight=FontWeight.Bold)
                Text("BACK  Live TV",color=Color(0xFF9EADBC),fontSize=13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                LibraryButton("Browse",shelf=="browse",Modifier.focusRequester(initial)) { openShelf("browse") }
                LibraryButton("All",browse.kind.isBlank()) { browse=browse.copy(kind="") }
                LibraryButton("Films",browse.kind=="movie") { browse=browse.copy(kind="movie") }
                LibraryButton("TV series",browse.kind=="show") { browse=browse.copy(kind="show") }
                LibraryButton("★ Favourites",shelf=="favourites") { openShelf("favourites") }
                LibraryButton("My lists",saved.lists.any { it.id==shelf }) { myLists() }
                LibraryButton("Search",browse.query.isNotBlank()) { search() }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                LibraryButton("Genre: ${browse.genre.ifBlank { "All" }}",browse.genre.isNotBlank()) { genres() }
                LibraryButton("Year: ${browse.yearLabel}",browse.fromYear!=null || browse.unknownYear) { years() }
                LibraryButton("Provider: ${browse.provider?.label ?: "All"}",browse.provider!=null) { providers() }
                LibraryButton("Sort: ${browse.sort.label}",browse.sort!=VodSort.FEATURED) { sorting() }
                LibraryButton("Clear filters") { browse=VodBrowse() }
                LibraryButton("More titles") { more() }
                LibraryButton("Refresh") { refresh() }
            }
            val summary="$shelfLabel · ${rows.size} titles"+(if(browse.query.isNotBlank())" · ‘${browse.query}’" else "")+(if(loading)" · Loading more…" else " · From loaded catalogues and saved titles")
            Text(summary,color=Color(0xFF9EADBC),fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(vertical=9.dp))
            if(rows.isEmpty())Text(if(loading)"Loading titles…" else if(shelf=="favourites")"Save a film or series from its details to see it here. Clear filters to see everything saved." else if(shelf!="browse")"This list has no matching titles. Save titles from their details, or clear your filters." else "No matching titles. Try Clear filters or More titles. Unknown genres and years stay available under All.",color=Color.White,fontSize=15.sp)
            LazyVerticalGrid(columns=GridCells.Fixed(6),state=grid,verticalArrangement=Arrangement.spacedBy(15.dp),horizontalArrangement=Arrangement.spacedBy(15.dp),modifier=Modifier.weight(1f)) {
                items(rows,key={it.key}) { title ->
                    var focused by remember { mutableStateOf(false) }
                    Column(Modifier.onFocusChanged { focused=it.isFocused }.border(if(focused)3.dp else 0.dp,if(focused)mint else Color.Transparent,RoundedCornerShape(6.dp))
                        .background(Color(0xFF101F2E),RoundedCornerShape(6.dp)).clickable { details(title) }.padding(5.dp)) {
                        AsyncImage(model=if(strictPrivacy)null else title.poster,contentDescription=null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxWidth().aspectRatio(2f/3f).background(Color(0xFF223A50)))
                        Text((if(saved.isFavourite(title))"★ " else "")+title.title,color=Color.White,fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.height(42.dp).padding(top=5.dp))
                        Text(listOfNotNull(title.year?.toString(),if(title.sources.size>1)"${title.sources.size} sources" else title.sources.first().provider.label).joinToString(" · "),color=mint,fontSize=11.sp)
                        Text(title.genres.take(2).joinToString(" · ").ifBlank { "Genre not supplied" },color=Color(0xFF9EADBC),fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
            Text("↑↓←→ Browse    OK Play / save    MENU Filters & lists",color=Color(0xFF9EADBC),fontSize=12.sp,modifier=Modifier.padding(top=9.dp))
        }
        LaunchedEffect(Unit) { initial.requestFocus() }
    }
}
