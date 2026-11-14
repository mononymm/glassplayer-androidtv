package com.mononymm.glasstv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import android.view.KeyEvent as AK
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.Canvas
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
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

private data class HomeRow(val id: String, val title: String, val items: List<Item>, val wide: Boolean)

/** Set by Home around each row so a tile's long-press menu can offer "Move this row". */
val LocalRowMove = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
fun HomeScreen() {
    val app = LocalApp.current
    var rows by remember { mutableStateOf(app.cached<List<HomeRow>>("home") ?: emptyList()) }
    var hero by remember { mutableStateOf<Item?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstRow = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var moving by remember { mutableStateOf<String?>(null) }
    var armed by remember { mutableStateOf(false) }
    // The user's order first; rows not in it yet keep their natural place after them.
    val shown = rows.sortedBy { r -> app.homeOrder.indexOf(r.id).let { if (it < 0) Int.MAX_VALUE else it } }

    LaunchedEffect(app.refresh) {
        val loaded = runCatching {
            if (app.sections.isEmpty()) app.sections = app.plex.sections()
            coroutineScope {
                val cw = async { runCatching { app.plex.continueWatching() }.getOrDefault(emptyList()) }
                val recents = app.orderedSections.map { s ->
                    async { s to runCatching { app.plex.recentlyAdded(s) }.getOrDefault(emptyList()) }
                }
                buildList {
                    cw.await().takeIf { it.isNotEmpty() }?.let { add(HomeRow("cw", "Continue watching", it, true)) }
                    recents.awaitAll().forEach { (s, list) ->
                        if (list.isNotEmpty()) add(HomeRow("recent:${s.key}", "Recently added in ${s.title}", list, false))
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
            Column(Modifier.padding(EDGE)) {
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
        // Moving a row (from a tile's long-press menu): Up/Down moves it, OK or Back finishes.
        fun move(dir: Int) {
            val id = moving ?: return
            val ids = shown.map { it.id }
            app.moveHomeRow(ids, id, dir)
            val to = (ids.indexOf(id) + dir).coerceIn(0, ids.lastIndex)
            scope.launch { listState.animateScrollToItem(to) }
        }
        LazyColumn(
            Modifier.fillMaxSize().onPreviewKeyEvent { ev ->
                if (moving == null) return@onPreviewKeyEvent false
                val down = ev.type == KeyEventType.KeyDown
                if (down && ev.nativeKeyEvent.repeatCount == 0) armed = true
                when (ev.nativeKeyEvent.keyCode) {
                    AK.KEYCODE_DPAD_UP -> { if (down) move(-1); true }
                    AK.KEYCODE_DPAD_DOWN -> { if (down) move(1); true }
                    AK.KEYCODE_DPAD_LEFT, AK.KEYCODE_DPAD_RIGHT -> true
                    AK.KEYCODE_DPAD_CENTER, AK.KEYCODE_ENTER, AK.KEYCODE_NUMPAD_ENTER, AK.KEYCODE_BACK, AK.KEYCODE_ESCAPE -> {
                        if (!down && armed) moving = null
                        true
                    }
                    else -> false
                }
            },
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 240.dp),
        ) {
            items(shown.size, key = { shown[it].id }) { i ->
                val r = shown[i]
                CompositionLocalProvider(LocalRowMove provides { moving = r.id; armed = false }) {
                    Box(if (i == 0) Modifier.focusRequester(firstRow) else Modifier) {
                        ItemRow(r.title, r.items, r.wide, note = if (moving == r.id) "↑ ↓ to move, OK when done" else null) {
                            hero = it
                            if (moving == null && (listState.firstVisibleItemIndex != i || listState.firstVisibleItemScrollOffset != 0)) {
                                scope.launch { listState.animateScrollToItem(i) }
                            }
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
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun BrowseFrame(
    tab: String,
    hero: Item?,
    heroHeight: Int = 150,
    headerCenter: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val app = LocalApp.current
    var railOpen by remember { mutableStateOf(false) }
    val contentReq = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
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
            Header(center = headerCenter)
            if (heroHeight > 0) HeroInfo(hero, Modifier.height(heroHeight.dp))
            Box(
                Modifier.weight(1f).clipToBounds()
                    // Remembers the focused tile, so closing the side bar returns to it.
                    .focusRequester(contentReq).focusRestorer()
                    // Left with nothing further left opens the side bar.
                    .onKeyEvent { ev ->
                        if (ev.nativeKeyEvent.keyCode != AK.KEYCODE_DPAD_LEFT || railOpen) return@onKeyEvent false
                        if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                        if (!focusManager.moveFocus(FocusDirection.Left)) railOpen = true
                        true
                    },
            ) {
                content()
                // The row below fades into the bottom edge instead of being cut off hard.
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(40.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, G.bg))),
                )
            }
        }
        SideRail(tab, railOpen) {
            railOpen = false
            contentReq.requestFocus()
        }
    }
}

@Composable
fun HeroInfo(item: Item?, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    Column(modifier.padding(start = EDGE, top = 8.dp).width(520.dp), verticalArrangement = Arrangement.Top) {
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

