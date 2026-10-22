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
import kotlinx.coroutines.*

class OnDemandActivity : ComponentActivity() {
    var catalogue by mutableStateOf(VodResult(emptyList(), emptyMap())); private set
    private var message by mutableStateOf("Loading free films and TV series through your current connection…")
    private var pendingTitle: VodTitle? = null
    private val playerLauncher=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==RESULT_FIRST_USER)pendingTitle?.let { details(it) }
    }
    private val crewLauncher=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val cached=crewTitles()
        shelf="browse";browse=browse.copy(query="")
        catalogue=catalogue.copy(titles=VodCatalogue.merge(catalogue.titles+cached),statuses=catalogue.statuses+(VodProvider.CREW to "${cached.size} cached titles · Browse The Crew for its live catalogue"))
        cacheCatalogue()
        if(result.resultCode==RESULT_OK)VodSavedCodec.decode(result.data?.getStringExtra("title").orEmpty()).titles.firstOrNull()?.let { details(it) }
    }
    private fun crewTitles()=VodSavedCodec.decode(libraryPreferences.getString("crew-catalogue-cache","").orEmpty()).titles
    private fun openCrew(query:String="") {
        if(VodProvider.CREW !in browse.providers)return
        cancelWork()
        crewLauncher.launch(Intent(this,CrewCatalogueActivity::class.java).putExtra("query",query).putExtra("kind",browse.kind))
    }
    private var browse by mutableStateOf(VodBrowse())
    private var onlineRows by mutableStateOf<List<VodTitle>>(emptyList())
    private var searchPage=0
    private var searchPages=0
    private var work:Job?=null
    private var workGeneration=0
    private fun cancelWork(){workGeneration++;work?.cancel();work=null;loading=false}
    private fun runWork(block:suspend ()->Unit){
        cancelWork();val generation=workGeneration;loading=true
        work=lifecycleScope.launch {
            try{block()}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message ?: "Could not load titles. Try again."}
            finally{if(generation==workGeneration)loading=false}
        }
    }
    private var saved by mutableStateOf(VodSaved())
    private var shelf by mutableStateOf("browse")
    private val libraryPreferences get()=getSharedPreferences("vod-library",MODE_PRIVATE)
    private val allTitles by derivedStateOf { saved.available(catalogue.titles) }
    val visibleTitles: List<VodTitle> by derivedStateOf {
        val keys=when(shelf) { "browse"->null;"favourites"->saved.favourites;else->saved.lists.firstOrNull { it.id==shelf }?.keys ?: emptySet() }
        val rows=if(shelf=="browse"&&browse.query.isNotBlank())onlineRows else allTitles
        (if(shelf=="browse"&&browse.query.isNotBlank())browse.copy(query="") else browse).apply(rows.filter { keys==null || saved.keyFor(it) in keys })
    }
    private fun save(value: VodSaved) { saved=value;libraryPreferences.edit().putString("saved",VodSavedCodec.encode(value)).apply() }
    private fun openShelf(value: String) { cancelWork();shelf=value;browse=VodBrowse(sort=browse.sort,providers=browse.providers) }
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
        browse=browse.copy(providers=VodProviderSelection.decode(libraryPreferences.getString("providers",null)))
        setContent { Library() }
        catalogue=VodResult(VodCatalogue.merge(VodSavedCodec.decode(libraryPreferences.getString("catalogue-cache","").orEmpty()).titles+crewTitles()),mapOf(VodProvider.CREW to "Browse The Crew for its live catalogue"))
        if(catalogue.titles.isEmpty() || System.currentTimeMillis()-libraryPreferences.getLong("catalogue-time",0)>15*60*1000)refresh()
        else message="Browse cached titles or search online for any film or series"
    }
    private fun cacheCatalogue(){
        val balanced=VodProvider.entries.flatMap { provider -> listOf("movie","show").flatMap { kind ->
            catalogue.titles.filter{it.kind==kind&&it.sources.any{s->s.provider==provider}}.take(30)
        } }
        val snapshot=VodSaved(titles=VodCatalogue.merge(balanced+catalogue.titles).take(300))
        lifecycleScope.launch(Dispatchers.IO){libraryPreferences.edit().putString("catalogue-cache",VodSavedCodec.encode(snapshot)).putLong("catalogue-time",System.currentTimeMillis()).apply()}
    }
    private fun refresh() {
        if(browse.query.isNotBlank()){searchOnline(browse.query);return}
        expandedGenres.clear()
        message="Refreshing titles… Search is available immediately."
        runWork {
            val selected=browse.providers
            val highlights=if(selected.none { it in setOf(VodProvider.MOVY,VodProvider.DULO) })emptyList() else coroutineScope {
                suspend fun popular(kind:String)=try{VodDiscovery.popular(kind).titles}catch(e:CancellationException){throw e}catch(_:Exception){emptyList<VodTitle>()}
                val movies=async { popular("movie") };val shows=async { popular("tv") }
                movies.await()+shows.await()
            }
            currentCoroutineContext().ensureActive()
            catalogue=catalogue.copy(titles=VodCatalogue.merge(highlights+catalogue.titles).take(500))
            message="Browse titles or search online for any film or series"
            val providers=VodCatalogue.load(selected)
            currentCoroutineContext().ensureActive()
            catalogue=providers.copy(titles=VodCatalogue.merge(providers.titles+highlights+crewTitles()),statuses=providers.statuses+(VodProvider.CREW to "Browse The Crew for its live catalogue"))
            cacheCatalogue()
            message="Browse highlights · Search checks Movy / Dulo online"
        }
    }
    fun searchOnline(query:String,append:Boolean=false) {
        val q=query.trim()
        if(q.length>=2&&browse.providers==setOf(VodProvider.CREW)){openCrew(q);return}
        if(q.length<2){cancelWork();browse=browse.copy(query="");message="Enter at least two characters to search online";return}
        val page=if(append)searchPage+1 else 1
        if(append&&page>searchPages){message="All results for this search are shown";return}
        shelf="browse"
        if(!append){browse=browse.copy(query=q);onlineRows=emptyList();searchPage=0;searchPages=0}
        message=if(browse.providers.any { it in setOf(VodProvider.MOVY,VodProvider.DULO) })"Searching online for ‘$q’…" else "Searching cached titles for ‘$q’…"
        runWork {
            val result=if(browse.providers.any { it in setOf(VodProvider.MOVY,VodProvider.DULO) })VodDiscovery.search(q,page) else VodDiscovery.Page(emptyList(),1,1)
            currentCoroutineContext().ensureActive()
            val knownOtherProviders=VodBrowse(query=q).apply(catalogue.titles.mapNotNull { title ->
                val sources=title.sources.filter{it.provider !in setOf(VodProvider.MOVY,VodProvider.DULO)}
                if(sources.isEmpty())null else title.copy(sources=sources)
            })
            onlineRows=VodCatalogue.merge((if(append)onlineRows else emptyList())+result.titles+knownOtherProviders)
            searchPage=result.page;searchPages=result.pages
            catalogue=catalogue.copy(titles=VodCatalogue.merge(catalogue.titles+result.titles))
            message="${visibleTitles.size} matches · "+(if(browse.providers.any { it in setOf(VodProvider.MOVY,VodProvider.DULO) })"Movy / Dulo online; other providers use cached titles" else "Cached titles")+(if(VodProvider.CREW in browse.providers)" · Browse The Crew for live Crew search" else "")+" · Page $searchPage of ${searchPages.coerceAtLeast(1)}"
        }
    }
    private fun more() {
        if(browse.query.isNotBlank()){if(!loading)searchOnline(browse.query,true);return}
        if(loading)return
        if(browse.providers==setOf(VodProvider.CREW)){openCrew();return}
        message="Loading more browse titles…"
        runWork {
            val extra=VodCatalogue.more(catalogue,browse.providers)
            currentCoroutineContext().ensureActive();catalogue=extra;cacheCatalogue()
            message="${catalogue.titles.size} browse titles · Search online to find a specific title"
        }
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
        if(browse.query.isNotBlank() || loading || browse.genre.isBlank() || browse.genre=="Unknown genre" || browse.genre in expandedGenres)return
        val genre=browse.genre
        runWork {
            val result=VodCatalogue.forGenre(catalogue,genre,browse.providers)
            currentCoroutineContext().ensureActive()
            catalogue=result;expandedGenres.add(genre);cacheCatalogue()
            message="${catalogue.titles.size} titles loaded"
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
    private fun setProviders(values:Set<VodProvider>) {
        cancelWork();checkedAlternatives.clear();browse=browse.copy(providers=values)
        libraryPreferences.edit().putString("providers",VodProviderSelection.encode(values)).apply()
        if(browse.query.isNotBlank())searchOnline(browse.query)
    }
    private fun clearFilters(){cancelWork();browse=VodBrowse();libraryPreferences.edit().remove("providers").apply()}
    private fun providers() {
        val values=VodProvider.entries
        val checked=values.map { it in browse.providers }.toBooleanArray()
        val dialog=AlertDialog.Builder(this).setTitle("Providers · choose any combination")
            .setMultiChoiceItems(values.map { it.label }.toTypedArray(),checked){_,i,value->checked[i]=value}
            .setPositiveButton("Apply"){_,_->setProviders(values.filterIndexed { i,_->checked[i] }.toSet())}
            .setNeutralButton("Select all",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener{
            values.indices.forEach { checked[it]=true;dialog.listView.setItemChecked(it,true) }
        }};dialog.show()
    }
    private fun sorting()=choose("Sort titles",VodSort.entries.map { it.label },browse.sort.ordinal) { browse=browse.copy(sort=VodSort.entries[it]) }
    private fun search() {
        val input=EditText(this).apply { setSingleLine(true);setText(browse.query);hint="Search any film or TV series";filters=arrayOf(android.text.InputFilter.LengthFilter(120)) }
        AlertDialog.Builder(this).setTitle("Search online").setView(input)
            .setPositiveButton("Search") { _, _ -> searchOnline(input.text.toString()) }
            .setNeutralButton("Clear") { _, _ -> cancelWork();browse=browse.copy(query="") }.setNegativeButton("Cancel",null).show()
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
        val labels=mutableListOf("Genre","Release year","Providers","Sort titles","Search","Favourites","My lists","New list","Clear filters","More titles","Refresh","Service status")
        if(VodProvider.CREW in browse.providers)labels.add("Browse The Crew")
        if(saved.lists.any { it.id==shelf })labels.add("Manage this list")
        choose("On Demand",labels) { i -> when(labels[i]) {
            "Genre"->genres();"Release year"->years();"Providers"->providers();"Sort titles"->sorting();"Search"->search()
            "Favourites"->openShelf("favourites");"My lists"->myLists();"New list"->nameList();"Clear filters"->clearFilters()
            "Browse The Crew"->openCrew();"More titles"->more();"Refresh"->refresh();"Manage this list"->manageList();else->status()
        } }
    }
    private fun details(title: VodTitle, checked: Boolean = false) {
        cancelWork()
        if(!checked && title.key !in checkedAlternatives) {
            message="Checking alternative sources for ${title.title}…"
            runWork {
                val enriched=VodCatalogue.alternatives(title,browse.providers)
                currentCoroutineContext().ensureActive()
                checkedAlternatives.add(title.key)
                catalogue=catalogue.copy(titles=VodCatalogue.merge(catalogue.titles+enriched))
                details(enriched,true)
                message="${catalogue.titles.size} titles · MENU during playback to change stream"
            }
            return
        }
        val sources=title.sources.filter { it.provider in browse.providers }
        val actions=sources.map { "${if(title.kind=="show") "Choose episode" else "Watch"} · ${it.provider.label}" }+
            listOf(if(saved.isFavourite(title))"★ Remove from favourites" else "☆ Add to favourites","Save to lists","About this title")+
            (if(saved.lists.any { it.id==shelf })listOf("Remove from this list") else emptyList())
        AlertDialog.Builder(this).setTitle(title.title).setItems(actions.toTypedArray()) { _,i -> when {
            i<sources.size->if(sources[i].provider==VodProvider.CREW) {
                val uri=java.net.URI(sources[i].url)
                startActivity(Intent(this,CrewTestActivity::class.java).putExtra("tmdb",uri.path.trim('/')).putExtra("type",uri.host).putExtra("title",title.title))
            } else if(title.kind=="show")showEpisodes(title,sources[i]) else play(title,sources[i])
            i==sources.size->save(saved.toggleFavourite(title))
            i==sources.size+1->addToLists(title)
            i==sources.size+2->AlertDialog.Builder(this).setTitle(title.title).setMessage(title.subtitle+"\n"+title.genres.joinToString(" · ").ifBlank { "Genre not supplied" }+"\n\n"+title.description.ifBlank { "Choose a source to see its current availability." }).setPositiveButton("Back",null).show()
            else->save(saved.setLists(title,saved.lists.filter { it.id!=shelf && saved.keyFor(title) in it.keys }.map { it.id }.toSet()))
        } }.setNegativeButton("Back",null).show()
    }
    private fun showEpisodes(title: VodTitle, source: VodSource) {
        message="Loading episodes of ${title.title}…"
        runWork {
            val episodes=runCatching { VodEpisodes.load(source) }
            currentCoroutineContext().ensureActive()
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
        runWork {
            val target=runCatching { VodCatalogue.playbackPage(source) }
            currentCoroutineContext().ensureActive()
            target.onSuccess { url ->
                val alternatives=title.sources.filter { it.provider in browse.providers && it.provider!=VodProvider.CREW }
                pendingTitle=title
                playerLauncher.launch(Intent(this@OnDemandActivity,VodPlayerActivity::class.java)
                    .putExtra("url",url).putExtra("provider",source.provider.name).putExtra("title",title.title).putExtra("alternativeCount",alternatives.size-1)
                    .putExtra("sourceUrls",alternatives.map { it.url }.toTypedArray()).putExtra("sourceProviders",alternatives.map { it.provider.name }.toTypedArray())
                    .putExtra("sourceIndex",alternatives.indexOf(source)).putExtra("seriesUrl",if(source.provider==VodProvider.DULO && title.kind=="episode") "https://dulo.mov/title/tv/"+java.net.URL(source.url).path.substringAfterLast('/')+"/" else ""))
            }.onFailure { failure(it.message.orEmpty()) }
            message="${catalogue.titles.size} titles · Free with adverts"
        }
    }
    override fun onStop(){cancelWork();super.onStop()}
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
                LibraryButton("Providers: ${browse.providerLabel}",browse.providers.size!=VodProvider.entries.size) { providers() }
                LibraryButton("Sort: ${browse.sort.label}",browse.sort!=VodSort.FEATURED) { sorting() }
                LibraryButton("Clear filters") { clearFilters() }
                if(VodProvider.CREW in browse.providers)LibraryButton("Browse The Crew") { openCrew() }
                LibraryButton(if(browse.query.isNotBlank())"More results" else "More titles") { more() }
                LibraryButton("Refresh") { refresh() }
            }
            val summary="$shelfLabel · ${rows.size} titles"+(if(browse.query.isNotBlank())" · ‘${browse.query}’" else "")+(if(loading)" · Loading…" else if(browse.query.isNotBlank())" · Online search" else " · Browse highlights")
            Text(summary,color=Color(0xFF9EADBC),fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(vertical=9.dp))
            if(rows.isEmpty())Text(if(loading)"Loading titles…" else if(browse.providers.isEmpty())"No providers selected. Open Providers to choose what to include." else if(browse.providers==setOf(VodProvider.CREW))"Open Browse The Crew for films, series, categories and live search." else if(shelf=="favourites")"Save a film or series from its details to see it here. Clear filters to see everything saved." else if(shelf!="browse")"This list has no matching titles. Save titles from their details, or clear your filters." else if(browse.query.isNotBlank())"No matching results. Try a different title or clear the filters." else "Search online for any film or series, or refresh the browse highlights.",color=Color.White,fontSize=15.sp)
            LazyVerticalGrid(columns=GridCells.Fixed(6),state=grid,verticalArrangement=Arrangement.spacedBy(15.dp),horizontalArrangement=Arrangement.spacedBy(15.dp),modifier=Modifier.weight(1f)) {
                items(rows,key={it.key}) { title ->
                    val availableSources=title.sources.filter { it.provider in browse.providers }
                    var focused by remember { mutableStateOf(false) }
                    Column(Modifier.onFocusChanged { focused=it.isFocused }.border(if(focused)3.dp else 0.dp,if(focused)mint else Color.Transparent,RoundedCornerShape(6.dp))
                        .background(Color(0xFF101F2E),RoundedCornerShape(6.dp)).clickable { details(title) }.padding(5.dp)) {
                        AsyncImage(model=if(strictPrivacy)null else title.poster,contentDescription=null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxWidth().aspectRatio(2f/3f).background(Color(0xFF223A50)))
                        Text((if(saved.isFavourite(title))"★ " else "")+title.title,color=Color.White,fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.height(42.dp).padding(top=5.dp))
                        Text(listOfNotNull(title.year?.toString(),if(availableSources.size>1)"${availableSources.size} sources" else availableSources.firstOrNull()?.provider?.label).joinToString(" · "),color=mint,fontSize=11.sp)
                        Text(title.genres.take(2).joinToString(" · ").ifBlank { "Genre not supplied" },color=Color(0xFF9EADBC),fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
            Text(message,color=Color(0xFF9EADBC),fontSize=11.sp,maxLines=2)
            Text("↑↓←→ Browse    OK Play / save    MENU Filters & lists",color=Color(0xFF9EADBC),fontSize=12.sp,modifier=Modifier.padding(top=9.dp))
        }
        LaunchedEffect(Unit) { initial.requestFocus() }
    }
}
