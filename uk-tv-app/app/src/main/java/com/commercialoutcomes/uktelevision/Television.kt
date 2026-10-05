package com.commercialoutcomes.uktelevision

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
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

private val Ink = Color(0xFF071019)
private val Deep = Color(0xFF0B1724)
private val Panel = Color(0xFF101F2E)
private val Panel2 = Color(0xFF162A3C)
private val Soft = Color(0xFF223A50)
private val Mint = Color(0xFF63E6C6)
private val Sky = Color(0xFF6BB8FF)
private val White = Color(0xFFF7FAFD)
private val Muted = Color(0xFF9EADBC)
private val Dim = Color(0xFF617183)
private val Gold = Color(0xFFFFD27A)
private val LiveRed = Color(0xFFFF5C68)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun Television(
    model: TvModel,
    engine: PlaybackEngine,
    youtubeSource: StreamSource?,
    surface: TvSurface,
    liveOverlay: LiveOverlay,
    quickGuideKey: String,
    resizeMode: Int,
    onOptions: () -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) {
        while (true) {
            model.clock = System.currentTimeMillis()
            delay(30000)
        }
    }

    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Ink)) {
            if (surface == TvSurface.LIVE) {
                LiveScreen(
                    model, engine, youtubeSource, liveOverlay,
                    quickGuideKey, resizeMode
                )
            } else {
                GuideScreen(model, engine, youtubeSource, resizeMode)
            }
        }
    }
}

@Composable
private fun MediaSurface(
    model: TvModel,
    engine: PlaybackEngine,
    youtubeSource: StreamSource?,
    modifier: Modifier,
    resizeMode: Int,
    interactiveYoutube: Boolean
) {
    Box(modifier.background(Color.Black)) {
        if (youtubeSource != null) {
            EmbeddedYouTubePlayer(
                source = youtubeSource,
                strict = model.privacyMode == PrivacyMode.STRICT,
                modifier = Modifier.fillMaxSize(),
                interactive = interactiveYoutube
            )
        } else {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        keepScreenOn = true
                        isFocusable = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = {
                    it.player = engine.player
                    it.resizeMode = resizeMode
                }
            )
        }
    }
}

@Composable
private fun GuideScreen(
    model: TvModel,
    engine: PlaybackEngine,
    youtubeSource: StreamSource?,
    resizeMode: Int
) {
    val rows = model.rows()
    val selected = model.selected()
    val programme = model.highlighted()
    val playing = model.playingRow
    val context = LocalContext.current

    Column(
        Modifier.fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF071019), Color(0xFF091521), Color(0xFF06101A))
                )
            )
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        TopBar(model)
        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.fillMaxWidth().height(196.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                Modifier.weight(1f).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Panel2, Deep)), RoundedCornerShape(12.dp))
                    .padding(18.dp)
            ) {
                Logo(
                    selected?.station?.logo.orEmpty(),
                    selected?.station?.name.orEmpty(),
                    Modifier.size(76.dp),
                    model.privacyMode
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    val time = programme?.let {
                        "${SimpleDateFormat("EEE d MMM", Locale.UK).format(Date(it.start))}  " +
                            "${formatTime(it.start)}–${formatTime(it.stop)}"
                    }.orEmpty()
                    Text(
                        listOfNotNull(
                            selected?.station?.name,
                            time.takeIf { it.isNotBlank() }
                        ).joinToString("   "),
                        color = Mint,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        programme?.title ?: "No programme information",
                        color = White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!programme?.subtitle.isNullOrBlank()) {
                        Text(
                            programme!!.subtitle,
                            color = Gold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        programme?.description?.ifBlank { "No synopsis supplied for this programme." }
                            ?: "Select a channel or programme to see its details.",
                        color = White.copy(alpha = .92f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!programme?.details.isNullOrBlank()) {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            programme!!.details,
                            color = Muted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                val art = remember(programme?.artwork, model.privacyMode) {
                    ArtworkStore.model(context, programme?.artwork.orEmpty(), model.privacyMode)
                }
                if (art != null) {
                    Spacer(Modifier.width(12.dp))
                    AsyncImage(
                        model = art,
                        contentDescription = null,
                        modifier = Modifier.width(148.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Box(
                Modifier.width(348.dp).fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, if (playing != null) Mint.copy(alpha = .6f) else Soft, RoundedCornerShape(12.dp))
            ) {
                if (playing != null) {
                    MediaSurface(
                        model, engine, youtubeSource,
                        Modifier.fillMaxSize(),
                        resizeMode,
                        interactiveYoutube = false
                    )
                    Box(
                        Modifier.align(Alignment.TopStart)
                            .padding(10.dp)
                            .background(Ink.copy(alpha = .78f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "LIVE PREVIEW  ·  ${playing.station.name}",
                            color = White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (engine.failed && youtubeSource == null) {
                        Box(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                .background(Ink.copy(alpha = .88f))
                                .padding(10.dp)
                        ) {
                            Text(engine.status, color = Gold, fontSize = 11.sp)
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize().background(Deep), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Logo(
                                selected?.station?.logo.orEmpty(),
                                selected?.station?.name.orEmpty(),
                                Modifier.size(78.dp),
                                model.privacyMode
                            )
                            Spacer(Modifier.height(10.dp))
                            Text("OK previews live TV here", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CategoryRail(model, Modifier.width(158.dp).fillMaxHeight())
            GuideGrid(model, rows, Modifier.weight(1f).fillMaxHeight())
        }

        Spacer(Modifier.height(7.dp))
        GuideFooter(model, rows.size)
    }
}

@Composable
private fun TopBar(model: TvModel) {
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(Mint, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(9.dp))
        Text(
            "UK TELEVISION",
            color = White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.7.sp
        )
        Spacer(Modifier.width(14.dp))
        Text(
            when (model.guideZone) {
                GuideZone.CATEGORIES -> "CATEGORIES"
                GuideZone.CHANNELS -> "CHANNELS"
                GuideZone.PROGRAMMES -> "PROGRAMMES"
            },
            color = Mint,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.weight(1f))
        Text(
            "${model.profile}  ·  ${model.privacyMode.label}",
            color = Muted,
            fontSize = 10.sp
        )
        Spacer(Modifier.width(16.dp))
        Text(
            SimpleDateFormat("EEE d MMM  HH:mm", Locale.UK).format(Date(model.clock)),
            color = White,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun CategoryRail(model: TvModel, modifier: Modifier) {
    val groups = model.groups()
    val state = rememberLazyListState()
    val index = groups.indexOf(model.group).coerceAtLeast(0)

    LaunchedEffect(model.group, groups.size) {
        if (groups.isNotEmpty()) state.scrollToItem(index.coerceIn(0, groups.lastIndex))
    }

    Column(
        modifier.background(Deep.copy(alpha = .8f), RoundedCornerShape(10.dp)).padding(6.dp)
    ) {
        Text(
            "CHANNELS",
            color = Dim,
            fontSize = 9.sp,
            letterSpacing = 1.3.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(groups, key = { it }) { group ->
                val active = group == model.group
                val focused = active && model.guideZone == GuideZone.CATEGORIES
                val label = group.replace(Regex("^\\d+\\s+"), "")
                Row(
                    Modifier.fillMaxWidth()
                        .background(
                            when {
                                focused -> Mint
                                active -> Soft
                                else -> Color.Transparent
                            },
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 9.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (group == "FAVOURITES") {
                        Text("★", color = if (focused) Ink else Gold, fontSize = 11.sp)
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        label,
                        color = if (focused) Ink else if (active) White else Muted,
                        fontSize = 10.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideGrid(model: TvModel, rows: List<GuideRow>, modifier: Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(27.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "CHANNEL",
                Modifier.width(215.dp).padding(start = 10.dp),
                color = Dim,
                fontSize = 9.sp,
                letterSpacing = 1.2.sp
            )
            repeat(6) { i ->
                Text(
                    formatTime(model.windowStart + i * HALF_HOUR),
                    Modifier.weight(1f),
                    color = Muted,
                    fontSize = 10.sp
                )
            }
        }

        val state = rememberLazyListState()
        val selectedIndex = rows.indexOfFirst { it.key == model.selectedKey }.coerceAtLeast(0)
        LaunchedEffect(model.selectedKey, rows.size) {
            if (rows.isNotEmpty()) {
                state.scrollToItem((selectedIndex - 2).coerceAtLeast(0))
            }
        }

        if (rows.isEmpty()) {
            Box(
                Modifier.fillMaxSize().background(Deep, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (model.refreshing) model.message else "No channels in this category",
                    color = Muted,
                    fontSize = 15.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                state = state,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                itemsIndexed(rows, key = { _, it -> it.key }) { _, row ->
                    ChannelLine(
                        row = row,
                        programmes = model.schedule[row.station.id].orEmpty(),
                        selected = row.key == model.selectedKey,
                        selectedProgrammeKey = model.selectedProgrammeKey,
                        guideZone = model.guideZone,
                        window = model.windowStart,
                        cursor = model.cursor,
                        now = model.clock,
                        favourite = row.station.id in model.favourites,
                        privacyMode = model.privacyMode,
                        modifier = Modifier.fillMaxWidth().height(49.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelLine(
    row: GuideRow,
    programmes: List<Programme>,
    selected: Boolean,
    selectedProgrammeKey: String,
    guideZone: GuideZone,
    window: Long,
    cursor: Long,
    now: Long,
    favourite: Boolean,
    privacyMode: PrivacyMode,
    modifier: Modifier
) {
    Row(modifier) {
        val channelFocused = selected && guideZone == GuideZone.CHANNELS
        Row(
            Modifier.width(215.dp).fillMaxHeight()
                .background(
                    when {
                        channelFocused -> Mint
                        selected -> Panel2
                        else -> Panel
                    },
                    RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
                )
                .then(
                    if (selected && guideZone == GuideZone.PROGRAMMES)
                        Modifier.border(1.dp, Sky.copy(alpha = .6f), RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    else Modifier
                )
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Logo(row.station.logo, row.station.name, Modifier.size(31.dp), privacyMode)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (if (favourite) "★  " else "") + row.label,
                    color = if (channelFocused) Ink else White,
                    fontSize = 10.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (row.sourceIndex > 0 || row.source.kind == "youtube") {
                    Text(
                        if (row.source.kind == "youtube") "official YouTube" else row.source.host,
                        color = if (channelFocused) Ink.copy(alpha = .65f) else Dim,
                        fontSize = 8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        BoxWithConstraints(
            Modifier.weight(1f).fillMaxHeight()
                .clipToBounds()
                .background(Deep)
        ) {
            val end = window + WINDOW
            val available = GuideRules.inWindow(programmes, window, end)
            if (available.isEmpty()) {
                Box(
                    Modifier.fillMaxSize()
                        .background(if (selected && guideZone == GuideZone.PROGRAMMES) Soft else Panel)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text("No programme information", color = Muted, fontSize = 10.5.sp)
                }
            }

            available.forEach { p ->
                val left = ((p.start - window).toDouble() / WINDOW).coerceIn(0.0, 1.0).toFloat()
                val right = ((p.stop - window).toDouble() / WINDOW).coerceIn(0.0, 1.0).toFloat()
                val active = selected && guideZone == GuideZone.PROGRAMMES &&
                    (p.key == selectedProgrammeKey || (selectedProgrammeKey.isBlank() && p.start <= cursor && p.stop > cursor))
                val live = p.start <= now && p.stop > now

                Box(
                    Modifier.offset(x = maxWidth * left)
                        .width((maxWidth * (right - left) - 2.dp).coerceAtLeast(2.dp))
                        .fillMaxHeight()
                        .background(
                            when {
                                active -> Mint
                                live -> Soft
                                selected -> Panel2
                                else -> Panel
                            },
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Column(Modifier.align(Alignment.CenterStart)) {
                        Text(
                            p.title,
                            color = if (active) Ink else White,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (live) {
                            val progress = ((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1L))
                                .coerceIn(0f, 1f)
                            Box(
                                Modifier.fillMaxWidth().height(2.dp)
                                    .background(if (active) Ink.copy(alpha = .2f) else Dim.copy(alpha = .4f))
                            ) {
                                Box(
                                    Modifier.fillMaxWidth(progress).fillMaxHeight()
                                        .background(if (active) Ink else Mint)
                                )
                            }
                        }
                    }
                }
            }

            if (now in window..end) {
                Box(
                    Modifier.offset(x = maxWidth * ((now - window).toFloat() / WINDOW))
                        .width(1.dp).fillMaxHeight()
                        .background(LiveRed.copy(alpha = .9f))
                )
            }
        }
    }
}

@Composable
private fun GuideFooter(model: TvModel, rowCount: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val hint = when (model.guideZone) {
            GuideZone.CATEGORIES -> "↑↓ Category   → Channels   BACK Live/Exit"
            GuideZone.CHANNELS -> "↑↓ Channel   ← Categories   → Programmes   OK Preview   HOLD OK Sources"
            GuideZone.PROGRAMMES -> "↑↓ Channel   ←→ Programme   OK Preview/Full screen   BACK Channel list   HOLD OK Sources"
        }
        Text(hint, color = Muted, fontSize = 9.5.sp, modifier = Modifier.weight(1f))
        Text(
            "MENU  ★ Favourite / options",
            color = Gold,
            fontSize = 9.5.sp
        )
        Spacer(Modifier.width(16.dp))
        Text(
            "$rowCount rows  ·  ${model.snapshot?.descriptions ?: 0} synopses",
            color = Mint,
            fontSize = 9.5.sp
        )
    }
    val stale = model.snapshot?.let { it.guideEnd < model.clock } ?: false
    Text(
        if (stale) "Guide is out of date — MENU → Refresh. Cached channels remain available."
        else model.message,
        color = if (stale || model.message.startsWith("Refresh failed")) Gold else Dim,
        fontSize = 9.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun LiveScreen(
    model: TvModel,
    engine: PlaybackEngine,
    youtubeSource: StreamSource?,
    overlay: LiveOverlay,
    quickGuideKey: String,
    resizeMode: Int
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        MediaSurface(
            model, engine, youtubeSource,
            Modifier.fillMaxSize(),
            resizeMode,
            interactiveYoutube = false
        )

        when (overlay) {
            LiveOverlay.NONE -> Unit
            LiveOverlay.INFO -> LiveInfoOverlay(model, engine, youtubeSource)
            LiveOverlay.QUICK_GUIDE -> QuickGuideOverlay(model, quickGuideKey)
        }

        if (engine.failed && youtubeSource == null) {
            Column(
                Modifier.align(Alignment.Center)
                    .widthIn(max = 620.dp)
                    .background(Ink.copy(alpha = .95f), RoundedCornerShape(14.dp))
                    .border(1.dp, Gold.copy(alpha = .45f), RoundedCornerShape(14.dp))
                    .padding(28.dp)
            ) {
                Text("This source is not playing", color = White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(engine.status, color = Muted, fontSize = 14.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(14.dp))
                Text("HOLD OK  Sources     BACK  Guide", color = Mint, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun BoxScope.LiveInfoOverlay(
    model: TvModel,
    engine: PlaybackEngine,
    youtubeSource: StreamSource?
) {
    val row = model.playingRow ?: return
    val current = GuideRules.at(model.schedule[row.station.id].orEmpty(), model.clock)
    val next = model.schedule[row.station.id].orEmpty().firstOrNull {
        current != null && it.start >= current.stop
    }

    Column(
        Modifier.fillMaxWidth().align(Alignment.BottomCenter)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Ink.copy(alpha = .84f), Ink.copy(alpha = .98f))
                )
            )
            .padding(start = 34.dp, end = 34.dp, top = 64.dp, bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(row.station.logo, row.station.name, Modifier.size(54.dp), model.privacyMode)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(row.station.name, color = Mint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    current?.title ?: "Live television",
                    color = White,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!current?.subtitle.isNullOrBlank()) {
                    Text(current!!.subtitle, color = Gold, fontSize = 12.sp)
                }
            }
            Text(
                SimpleDateFormat("HH:mm", Locale.UK).format(Date(model.clock)),
                color = White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
        }

        if (current != null) {
            Spacer(Modifier.height(8.dp))
            val progress = ((model.clock - current.start).toFloat() /
                (current.stop - current.start).coerceAtLeast(1L)).coerceIn(0f, 1f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(current.start), color = Muted, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f).height(3.dp).background(White.copy(alpha = .18f))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Mint))
                }
                Spacer(Modifier.width(8.dp))
                Text(formatTime(current.stop), color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                current.description.ifBlank { "No synopsis supplied." },
                color = White.copy(alpha = .9f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (next != null) {
            Spacer(Modifier.height(7.dp))
            Text(
                "NEXT  ${formatTime(next.start)}  ${next.title}",
                color = Muted,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(8.dp))
        val source = if (youtubeSource != null) "Official YouTube" else engine.source?.host.orEmpty()
        Text(
            "↑↓ Quick guide   INFO/OK Hide   HOLD OK Sources   BACK Full guide" +
                if (source.isNotBlank()) "   ·   $source" else "",
            color = Dim,
            fontSize = 9.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BoxScope.QuickGuideOverlay(model: TvModel, quickGuideKey: String) {
    val rows = model.channelRows()
    if (rows.isEmpty()) return
    val index = rows.indexOfFirst { it.station.id == quickGuideKey }.coerceAtLeast(0)
    val first = (index - 3).coerceIn(0, (rows.size - 7).coerceAtLeast(0))
    val visible = rows.drop(first).take(7)

    Row(
        Modifier.fillMaxWidth().align(Alignment.BottomCenter)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Ink.copy(alpha = .88f), Ink.copy(alpha = .98f))
                )
            )
            .padding(start = 28.dp, end = 28.dp, top = 74.dp, bottom = 22.dp)
    ) {
        Column(
            Modifier.width(620.dp)
                .background(Deep.copy(alpha = .94f), RoundedCornerShape(12.dp))
                .border(1.dp, Soft, RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("QUICK GUIDE", color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("↑↓ Browse   OK Tune   BACK Close", color = Muted, fontSize = 9.sp)
            }
            Spacer(Modifier.height(7.dp))
            visible.forEach { row ->
                val selected = row.station.id == quickGuideKey
                val current = GuideRules.at(model.schedule[row.station.id].orEmpty(), model.clock)
                val next = current?.let { c ->
                    model.schedule[row.station.id].orEmpty().firstOrNull { it.start >= c.stop }
                }

                Row(
                    Modifier.fillMaxWidth().height(47.dp)
                        .background(if (selected) Mint else Panel, RoundedCornerShape(6.dp))
                        .padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Logo(row.station.logo, row.station.name, Modifier.size(29.dp), model.privacyMode)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        (if (row.station.id in model.favourites) "★  " else "") + row.station.name,
                        color = if (selected) Ink else White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(155.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            current?.title ?: "No programme information",
                            color = if (selected) Ink else White,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (next != null) {
                            Text(
                                "Next  ${formatTime(next.start)}  ${next.title}",
                                color = if (selected) Ink.copy(alpha = .65f) else Dim,
                                fontSize = 8.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
            }
        }

        Spacer(Modifier.width(18.dp))
        val highlighted = visible.firstOrNull { it.station.id == quickGuideKey }
        val p = highlighted?.let { GuideRules.at(model.schedule[it.station.id].orEmpty(), model.clock) }
        Column(
            Modifier.weight(1f).heightIn(min = 180.dp)
                .background(Ink.copy(alpha = .82f), RoundedCornerShape(12.dp))
                .padding(18.dp)
        ) {
            Text(highlighted?.station?.name.orEmpty(), color = Mint, fontSize = 11.sp)
            Text(
                p?.title ?: "Live television",
                color = White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(7.dp))
            Text(
                p?.description?.ifBlank { "No synopsis supplied." } ?: "",
                color = White.copy(alpha = .86f),
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun Logo(
    url: String,
    name: String,
    modifier: Modifier,
    privacyMode: PrivacyMode
) {
    val context = LocalContext.current
    val image = remember(url, privacyMode) {
        ArtworkStore.model(context, url, privacyMode)
    }
    Box(
        modifier.background(White.copy(alpha = .06f), RoundedCornerShape(7.dp)).padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.take(2).uppercase(Locale.UK),
            color = Muted.copy(alpha = .34f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = "$name logo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}
