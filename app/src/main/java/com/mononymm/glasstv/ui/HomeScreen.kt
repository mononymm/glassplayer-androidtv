package com.mononymm.glasstv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp)) {
            items(rows.size, key = { rows[it].title }) { i ->
                val r = rows[i]
                Box(if (i == 0) Modifier.focusRequester(firstRow) else Modifier) {
                    ItemRow(r.title, r.items, r.wide) { hero = it }
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
fun BrowseFrame(tab: String, hero: Item?, heroHeight: Int = 210, content: @Composable () -> Unit) {
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
            Box(Modifier.weight(1f)) { content() }
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
        Text(item.summary ?: "", style = G.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun TabBar(selected: String) {
    val app = LocalApp.current
    LaunchedEffect(Unit) {
        if (app.sections.isEmpty()) runCatching { app.sections = app.plex.sections() }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 20.dp, bottom = 6.dp).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(Modifier.padding(start = 8.dp, end = 18.dp).size(34.dp))
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "home") {
                Pill("Home", { if (selected != "home") app.replaceAll(Screen.Home) }, selected = selected == "home")
            }
            items(app.sections, key = { it.key }) { s: Section ->
                Pill(s.title, {
                    if (selected != s.key) {
                        app.replaceAll(Screen.Home)
                        app.go(Screen.Library(s))
                    }
                }, selected = selected == s.key)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Search", { if (selected != "search") app.go(Screen.Search) }, selected = selected == "search")
            Pill("Settings", { if (selected != "settings") app.go(Screen.Settings) }, selected = selected == "settings")
        }
    }
}
