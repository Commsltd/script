package com.commercialoutcomes.uktelevision

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF0D1421)
private val Panel = Color(0xFF162234)
private val Soft = Color(0xFF223349)
private val Mint = Color(0xFF58DFC2)
private val White = Color(0xFFF3F7FB)
private val Muted = Color(0xFFA5B3C6)
private val Gold = Color(0xFFFFD483)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun Television(model: TvModel, engine: PlaybackEngine, hud: Boolean, resizeMode: Int,
    onWatch: (GuideRow) -> Unit, onOptions: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { while (true) { model.clock = System.currentTimeMillis(); delay(30000) } }
    MaterialTheme {
        if (model.isPlayer) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { context -> PlayerView(context).apply { useController = false; keepScreenOn = true } },
                    modifier = Modifier.fillMaxSize(), update = { it.player = engine.player; it.resizeMode = resizeMode })
                val row = model.playingRow
                val p = row?.let { GuideRules.at(model.schedule[it.station.id].orEmpty(), model.clock) }
                val showHud = hud || engine.failed || !engine.status.startsWith("Playing")
                if (showHud) {
                    Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha=.97f))))
                        .padding(horizontal=32.dp, vertical=22.dp)) {
                        Text(row?.station?.name.orEmpty(), color=Mint, fontSize=15.sp, fontWeight=FontWeight.SemiBold)
                        Text(p?.title ?: "Live television", color=White, fontSize=26.sp, maxLines=1, overflow=TextOverflow.Ellipsis)
                        Text(p?.description?.ifBlank { "No synopsis supplied." } ?: "No programme listings supplied.", color=White, fontSize=14.sp, maxLines=2, overflow=TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Text(engine.status, color=if(engine.failed) Gold else Muted, fontSize=12.sp)
                        Text("↑↓ Channel   •   OK Pause / retry   •   Hold OK Sources   •   MENU Options   •   Back Guide", color=Muted, fontSize=11.sp)
                    }
                }
                if (engine.failed) {
                    Column(Modifier.align(Alignment.Center).widthIn(max=580.dp).background(Panel,RoundedCornerShape(14.dp)).padding(28.dp)) {
                        Text("This source is not playing",color=White,fontSize=25.sp,fontWeight=FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        Text(engine.status,color=Muted,fontSize=16.sp)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                            Text("SOURCES / OPTIONS",Modifier.clickable(onClick=onOptions).padding(8.dp),color=Mint,fontSize=14.sp)
                            Text("BACK TO GUIDE",Modifier.clickable(onClick=onBack).padding(8.dp),color=White,fontSize=14.sp)
                        }
                    }
                }
            }
        } else {
            GuideScreen(model,onWatch,onOptions)
        }
    }
}

@Composable
private fun GuideScreen(model: TvModel, onWatch: (GuideRow)->Unit, onOptions:()->Unit) {
    val rows = model.rows()
    val selected = model.selected()
    val programme = model.highlighted()
    val categoryState = rememberLazyListState()
    val groups = model.groups()
    LaunchedEffect(model.group,groups.size) {
        categoryState.animateScrollToItem((groups.indexOf(model.group)-2).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize().background(Ink).padding(horizontal=26.dp,vertical=15.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(Mint,RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(10.dp))
            Text("UK TELEVISION",color=White,fontSize=19.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp)
            Spacer(Modifier.weight(1f))
            Text("${model.profile} profile  ·  LOCAL TIME  ",color=Muted,fontSize=11.sp)
            Text(SimpleDateFormat("EEE d MMM  HH:mm",Locale.UK).format(Date(model.clock)),color=White,fontSize=13.sp)
            Spacer(Modifier.width(18.dp))
            Text("MENU",Modifier.clickable(onClick=onOptions).border(1.dp,Soft,RoundedCornerShape(5.dp)).padding(horizontal=10.dp,vertical=5.dp),color=Mint,fontSize=11.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(164.dp).background(Brush.horizontalGradient(listOf(Panel,Ink)),RoundedCornerShape(12.dp)).padding(16.dp),verticalAlignment=Alignment.Top) {
            Logo(selected?.station?.logo.orEmpty(),selected?.station?.name.orEmpty(),Modifier.size(78.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                val time = programme?.let { "${SimpleDateFormat("EEE d MMM",Locale.UK).format(Date(it.start))}  ${formatTime(it.start)}–${formatTime(it.stop)}" }.orEmpty()
                Text("${selected?.station?.name ?: "Your television"}  $time",color=Mint,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(programme?.title ?: if (rows.isEmpty()) "Loading / choose a category" else "No listings supplied",color=White,fontSize=25.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                if (!programme?.subtitle.isNullOrBlank()) Text(programme!!.subtitle,color=Gold,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(programme?.description?.ifBlank { "No synopsis supplied for this programme. Hold OK for stream choices; MENU for full details." }
                    ?: if(model.group=="FAVOURITES") "Add channels to favourites with MENU. Your choices stay on this Firestick."
                    else "The channel may still play. Programme data and the video stream are separate.",
                    color=White,fontSize=13.sp,lineHeight=18.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
                if (!programme?.details.isNullOrBlank()) Text(programme!!.details,color=Muted,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            if(!programme?.artwork.isNullOrBlank()) {
                Spacer(Modifier.width(16.dp))
                AsyncImage(model=programme!!.artwork,contentDescription=null,modifier=Modifier.width(164.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp)),contentScale=ContentScale.Crop)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.weight(1f).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            LazyColumn(Modifier.width(145.dp).fillMaxHeight(),state=categoryState,verticalArrangement=Arrangement.spacedBy(3.dp)) {
                items(groups,key={it}) { group ->
                    val active = group == model.group
                    val label = group.replace(Regex("^\\d+\\s+"),"")
                    Text(label,Modifier.fillMaxWidth().background(if(active) (if(model.railSelected) Mint else Soft) else Color.Transparent,RoundedCornerShape(6.dp))
                        .clickable { model.selectGroup(group); model.railSelected=false }
                        .padding(horizontal=10.dp,vertical=8.dp),
                        color=if(active && model.railSelected) Ink else if(active) White else Muted,fontSize=11.sp,
                        fontWeight=if(active) FontWeight.Bold else FontWeight.Normal,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
            }
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().height(25.dp)) {
                    Text("CHANNEL",Modifier.width(175.dp),color=Muted,fontSize=10.sp,letterSpacing=1.sp)
                    repeat(5) { i ->
                        Text(formatTime(model.windowStart+i*HALF_HOUR),Modifier.weight(1f),color=Muted,fontSize=11.sp)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val visible = (maxHeight.value/45f).toInt().coerceIn(3,12)
                    val rowHeight = maxHeight/visible
                    val index = rows.indexOfFirst { it.key == selected?.key }.coerceAtLeast(0)
                    val first = (index-2).coerceIn(0,(rows.size-visible).coerceAtLeast(0))
                    Column(Modifier.fillMaxSize()) {
                        if(rows.isEmpty()) Text(if(model.refreshing) model.message else "No channels in this category",Modifier.padding(20.dp),color=Muted,fontSize=17.sp)
                        rows.drop(first).take(visible).forEach { row ->
                            ChannelLine(row,model.schedule[row.station.id].orEmpty(),row.key==selected?.key && !model.railSelected,
                                model.windowStart,model.cursor,model.clock,Modifier.fillMaxWidth().height(rowHeight),
                                favourite=row.station.id in model.favourites,
                                onSelect={model.selectedKey=row.key;model.railSelected=false},
                                onProgramme={p -> model.selectedKey=row.key;model.railSelected=false;model.cursor=maxOf(p.start,model.windowStart)},
                                onWatch={onWatch(row)})
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("↑↓ Channels   ←→ Programmes   OK Watch   Hold OK Sources   MENU Options   ⏩ +6h",color=Muted,fontSize=10.sp,modifier=Modifier.weight(1f))
            Text("${rows.size} rows · ${model.snapshot?.descriptions ?: 0} synopses",color=Mint,fontSize=10.sp)
        }
        val stale = model.snapshot?.let { it.guideEnd < model.clock } ?: false
        Text(if(stale) "Guide is out of date — use MENU → Refresh. Cached channels remain available." else model.message,
            color=if(stale || model.message.startsWith("Refresh failed")) Gold else Muted,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@Composable
private fun ChannelLine(row: GuideRow, programmes: List<Programme>, selected: Boolean,
    window: Long, cursor: Long, now: Long, modifier: Modifier, favourite: Boolean,
    onSelect:()->Unit,onProgramme:(Programme)->Unit,onWatch:()->Unit) {
    Row(modifier.padding(bottom=3.dp)) {
        Row(Modifier.width(175.dp).fillMaxHeight().background(if(selected) Soft else Panel,RoundedCornerShape(topStart=5.dp,bottomStart=5.dp))
            .clickable { if(selected) onWatch() else onSelect() }.padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Logo(row.station.logo,row.station.name,Modifier.size(32.dp))
            Spacer(Modifier.width(8.dp))
            Text((if(favourite) "★ " else "")+row.label,color=if(selected) White else Muted,fontSize=11.sp,maxLines=2,overflow=TextOverflow.Ellipsis,lineHeight=14.sp)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clipToBounds().background(Panel.copy(alpha=.65f))) {
            val available = GuideRules.inWindow(programmes,window,window+WINDOW)
            if(available.isEmpty()) Text("No programme information",Modifier.align(Alignment.CenterStart).padding(start=12.dp).clickable(onClick=onSelect),color=Muted,fontSize=12.sp)
            available.forEach { p ->
                val left = ((p.start-window).toDouble()/WINDOW).coerceIn(0.0,1.0).toFloat()
                val right = ((p.stop-window).toDouble()/WINDOW).coerceIn(0.0,1.0).toFloat()
                val active = selected && p.start <= cursor && p.stop > cursor
                val live = p.start<=now && p.stop>now
                Box(Modifier.offset(x=maxWidth*left).width((maxWidth*(right-left)-2.dp).coerceAtLeast(1.dp)).fillMaxHeight()
                    .background(if(active) Mint else if(live) Soft else Panel,RoundedCornerShape(4.dp))
                    .clickable { onProgramme(p) }.padding(horizontal=9.dp,vertical=5.dp)) {
                    Text(p.title,color=if(active) Ink else White,fontSize=12.sp,fontWeight=if(active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines=2,overflow=TextOverflow.Ellipsis,lineHeight=16.sp,modifier=Modifier.align(Alignment.CenterStart))
                }
            }
            if(now in window..(window+WINDOW)) {
                Box(Modifier.offset(x=maxWidth*((now-window).toFloat()/WINDOW)).width(1.dp).fillMaxHeight().background(Gold.copy(alpha=.65f)))
            }
        }
    }
}

@Composable
private fun Logo(url:String,name:String,modifier:Modifier) {
    Box(modifier.background(White.copy(alpha=.05f),RoundedCornerShape(6.dp)).padding(4.dp),contentAlignment=Alignment.Center) {
        Text(name.take(2).uppercase(Locale.UK),color=Muted.copy(alpha=.35f),fontSize=14.sp,fontWeight=FontWeight.Bold)
        if(url.isNotBlank()) AsyncImage(model=url,contentDescription="$name logo",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
    }
}
