package com.mononymm.glasstv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mononymm.glasstv.plex.Item
import com.mononymm.glasstv.plex.Section
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private data class HomeRow(val title: String, val items: List<Item>, val wide: Boolean)

@Composable
fun HomeScreen() {
    val app = LocalApp.current
    var rows by remember { mutableStateOf(app.cached<List<HomeRow>>("home") ?: emptyList()) }
    var hero by remember { mutableStateOf<Item?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstRow = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(app.refresh) {
        val loaded = runCatching {
            if (app.sections.isEmpty()) app.sections = app.plex.sections()
            coroutineScope {
                val cw = async { runCatching { app.plex.continueWatching() }.getOrDefault(emptyList()) }
                val recents = app.sections.map { s ->
                    async { s to runCatching { app.plex.recentlyAdded(s) }.getOrDefault(emptyList()) }
                }
                buildList {
                    cw.await().takeIf { it.isNotEmpty() }?.let { add(HomeRow("Continue watching", it, true)) }
                    recents.awaitAll().forEach { (s, list) ->
                        if (list.isNotEmpty()) add(HomeRow("Recently added in ${s.title}", list, false))
                    }
                }
            }
        }
        loaded.onSuccess {
            rows = it
            app.cache["home"] = it
            error = if (it.isEmpty()) "Nothing here yet." else null
        }.onFailure {
            if (rows.isEmpty()) error = "Couldn't reach ${app.prefs.serverName ?: "your server"}."
        }
    }
    LaunchedEffect(rows.isNotEmpty()) { if (rows.isNotEmpty()) firstRow.focusSoon() }

    BrowseFrame(tab = "home", hero = hero) {
        if (error != null && rows.isEmpty()) {
            Column(Modifier.padding(48.dp)) {
                Text(error ?: "", style = G.body)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Try again", { app.changed() }, primary = true)
                    ActionButton("Choose server", { app.go(Screen.Servers) })
                }
            }
        }
        // The focused row snaps to the top of the list so its titles are never cut off,
        // and the row above is scrolled fully out of view.
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 240.dp)) {
            items(rows.size, key = { rows[it].title }) { i ->
                val r = rows[i]
                Box(if (i == 0) Modifier.focusRequester(firstRow) else Modifier) {
                    ItemRow(r.title, r.items, r.wide) {
                        hero = it
                        if (listState.firstVisibleItemIndex != i || listState.firstVisibleItemScrollOffset != 0) {
                            scope.launch { listState.animateScrollToItem(i) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shared layout for browse screens: tab bar on top, the focused title's backdrop
 * and info in the upper area, and [content] below.
 */
@Composable
fun BrowseFrame(tab: String, hero: Item?, heroHeight: Int = 170, content: @Composable () -> Unit) {
    val app = LocalApp.current
    Box(Modifier.fillMaxSize()) {
        Crossfade(hero?.let { it.backdrop ?: it.thumb }, animationSpec = tween(400), label = "backdrop") { art ->
            Box(Modifier.fillMaxSize()) {
                if (art != null) {
                    NetImage(
                        app.plex.image(art, 1280, 720),
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                    )
                }
            }
        }
        Box(Modifier.fillMaxSize().background(scrimLeft))
        Box(Modifier.fillMaxSize().background(scrimBottom))
        Column(Modifier.fillMaxSize()) {
            TabBar(tab)
            if (heroHeight > 0) HeroInfo(hero, Modifier.height(heroHeight.dp))
            Box(Modifier.weight(1f).clipToBounds()) {
                content()
                // The row below fades into the bottom edge instead of being cut off hard.
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, G.bg))),
                )
            }
        }
    }
}

@Composable
fun HeroInfo(item: Item?, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    Column(modifier.padding(start = 48.dp, top = 8.dp).width(520.dp), verticalArrangement = Arrangement.Top) {
        if (item == null) return@Column
        val logo = item.logo
        if (logo != null) {
            NetImage(app.plex.image(logo, 600, 200), Modifier.height(64.dp).width(300.dp), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
        } else {
            Text(item.showTitle?.takeIf { item.isEpisode } ?: item.title, style = G.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        if (item.isEpisode) {
            Text(listOfNotNull(item.episodeTag, item.title).joinToString("  ·  "), style = G.label, maxLines = 1)
            Spacer(Modifier.height(4.dp))
        }
        MetaLine(item)
        Spacer(Modifier.height(8.dp))
        Text(item.summary ?: "", style = G.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun TabBar(selected: String) {
    val app = LocalApp.current
    LaunchedEffect(Unit) {
        if (app.sections.isEmpty()) runCatching { app.sections = app.plex.sections() }
    }
    // Logo at the left inset, tabs in one capsule centred on screen, Search and Settings as
    // round icons at the right inset. Nothing scrolls, so nothing slides under anything.
    Box(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 6.dp).height(44.dp)) {
        Logo(Modifier.align(Alignment.CenterStart).size(34.dp))
        Row(
            Modifier.align(Alignment.Center)
                .background(Color(0x1AFFFFFF), G.pillShape)
                .border(1.dp, Color(0x28FFFFFF), G.pillShape)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabPill("Home", selected == "home") { if (selected != "home") app.replaceAll(Screen.Home) }
            app.sections.forEach { s: Section ->
                TabPill(s.title, selected == s.key) {
                    if (selected != s.key) {
                        app.replaceAll(Screen.Home)
                        app.go(Screen.Library(s))
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundIcon(selected == "search", { if (selected != "search") app.go(Screen.Search) }) { c -> SearchIcon(c) }
            RoundIcon(selected == "settings", { if (selected != "settings") app.go(Screen.Settings) }) { c -> GearIcon(c) }
        }
    }
}

@Composable
private fun TabPill(text: String, selected: Boolean, onClick: () -> Unit) {
    FocusBox(
        onClick = onClick,
        shape = G.pillShape,
        focusScale = 1.06f,
        idleColor = if (selected) Color(0x40FFFFFF) else Color.Transparent,
        focusColor = Color(0xF2F4F4F6),
        idleRim = Color.Transparent,
        glow = false,
    ) { f ->
        Text(
            text,
            style = G.label.copy(color = if (f) Color(0xFF0B0B0F) else if (selected) G.text else G.dim),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun RoundIcon(selected: Boolean, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    FocusBox(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        focusScale = 1.1f,
        idleColor = if (selected) Color(0x40FFFFFF) else Color(0x1AFFFFFF),
        focusColor = Color(0x40FFFFFF),
        idleRim = Color(0x28FFFFFF),
    ) { f ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { icon(if (f || selected) G.text else G.dim) }
    }
}

@Composable
private fun SearchIcon(c: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val sw = w * 0.11f
        drawCircle(c, radius = w * 0.32f, center = Offset(w * 0.42f, w * 0.42f), style = Stroke(sw))
        drawLine(c, Offset(w * 0.66f, w * 0.66f), Offset(w * 0.92f, w * 0.92f), sw * 1.2f, StrokeCap.Round)
    }
}

@Composable
private fun GearIcon(c: Color) {
    Canvas(Modifier.size(19.dp)) {
        val w = size.width
        val ctr = Offset(w / 2, w / 2)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val dx = kotlin.math.cos(a).toFloat()
            val dy = kotlin.math.sin(a).toFloat()
            drawLine(c, Offset(ctr.x + dx * w * 0.30f, ctr.y + dy * w * 0.30f), Offset(ctr.x + dx * w * 0.46f, ctr.y + dy * w * 0.46f), w * 0.15f, StrokeCap.Butt)
        }
        drawCircle(c, radius = w * 0.31f, center = ctr, style = Stroke(w * 0.13f))
    }
}
