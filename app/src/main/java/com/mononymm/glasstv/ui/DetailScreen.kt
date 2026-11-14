package com.mononymm.glasstv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mononymm.glasstv.plex.EngineChoice
import com.mononymm.glasstv.plex.Item
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(start: Item) {
    val app = LocalApp.current
    var item by remember { mutableStateOf(start) }
    var seasons by remember { mutableStateOf(app.cached<List<Item>>("seasons:${start.ratingKey}") ?: emptyList()) }
    var season by rememberSaveable { mutableStateOf<String?>(null) }
    var episodes by remember { mutableStateOf<List<Item>>(emptyList()) }
    var upNext by remember { mutableStateOf<Item?>(null) }
    var engine by remember { mutableStateOf(app.prefs.engineForShow(start.ratingKey)) }
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(app.refresh) {
        app.plex.item(start.ratingKey)?.let { item = it }
        if (item.isShow) {
            val s = runCatching { app.plex.children(item.ratingKey) }.getOrDefault(emptyList())
            seasons = s
            app.cache["seasons:${start.ratingKey}"] = s
            val all = runCatching { app.plex.allEpisodes(item.ratingKey) }.getOrDefault(emptyList())
            upNext = all.firstOrNull { it.viewOffset > 0 && !it.watched } ?: all.firstOrNull { it.viewCount == 0 } ?: all.firstOrNull()
            if (season == null) {
                season = upNext?.seasonKey ?: s.firstOrNull { it.index != 0 }?.ratingKey ?: s.firstOrNull()?.ratingKey
            }
        }
    }
    LaunchedEffect(season, app.refresh) {
        val k = season ?: return@LaunchedEffect
        episodes = app.cached("eps:$k") ?: emptyList()
        runCatching { app.plex.children(k) }.onSuccess { episodes = it; app.cache["eps:$k"] = it }
    }
    LaunchedEffect(Unit) { playFocus.focusSoon() }

    Box(Modifier.fillMaxSize()) {
        NetImage(
            app.plex.image(item.backdrop ?: item.thumb, 1920, 1080),
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
        )
        Box(Modifier.fillMaxSize().background(scrimLeft))
        Box(Modifier.fillMaxSize().background(scrimBottom))

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 56.dp, bottom = 48.dp)) {
            item {
                Column(Modifier.padding(start = 56.dp).width(620.dp)) {
                    val logo = item.logo
                    if (logo != null) {
                        NetImage(app.plex.image(logo, 800, 260), Modifier.height(96.dp).width(420.dp), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
                    } else {
                        Text(item.title, style = G.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(12.dp))
                    MetaLine(item, if (item.isShow && seasons.isNotEmpty()) "${seasons.count { it.index != 0 }} seasons" else null)
                    Spacer(Modifier.height(12.dp))
                    Text(item.summary ?: "", style = G.body, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(18.dp))

                    val target = if (item.isShow) upNext else item
                    val inProgress = target != null && target.viewOffset > 0 && !target.watched
                    if (inProgress && target != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProgressLine(target.progress, Modifier.width(220.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(remaining(target) ?: "", style = G.small)
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val playLabel = when {
                            target == null -> "Play"
                            inProgress -> "Resume" + (target.episodeTag?.let { " $it" } ?: "")
                            item.isShow -> "Play " + (target.episodeTag ?: "")
                            else -> "Play"
                        }
                        ActionButton(
                            playLabel.trim(),
                            { target?.let { play(app, it) } },
                            Modifier.focusRequester(playFocus),
                            primary = true,
                            icon = { c -> PlayGlyph(c, 14.dp) },
                        )
                        if (inProgress && target != null) ActionButton("Play from start", { play(app, target, fromStart = true) })
                        ActionButton(if (item.watched) "Mark unwatched" else "Mark watched", {
                            app.scope.launch {
                                if (item.watched) app.plex.markUnwatched(item.ratingKey) else app.plex.markWatched(item.ratingKey)
                                app.changed()
                            }
                        })
                        if (item.isShow || item.isMovie) {
                            ActionButton("Player: ${engine.label}", {
                                val n = EngineChoice.entries[(engine.ordinal + 1) % EngineChoice.entries.size]
                                app.prefs.setEngineForShow(item.ratingKey, n)
                                engine = n
                            })
                        }
                    }
                }
            }

            if (item.isShow && seasons.isNotEmpty()) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 30.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(seasons, key = { it.ratingKey }) { s ->
                            Pill(
                                s.title + if (s.watched) "  ✓" else "",
                                { season = s.ratingKey },
                                selected = season == s.ratingKey,
                            )
                        }
                    }
                }
                item {
                    val cur = seasons.firstOrNull { it.ratingKey == season }
                    if (cur != null) {
                        Row(Modifier.padding(start = 56.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill(if (cur.watched) "Mark season unwatched" else "Mark season watched", {
                                app.scope.launch {
                                    if (cur.watched) app.plex.markUnwatched(cur.ratingKey) else app.plex.markWatched(cur.ratingKey)
                                    app.changed()
                                }
                            })
                        }
                    }
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(episodes, key = { it.ratingKey }) { ep ->
                            WideCard(ep, {}, showName = false)
                        }
                    }
                }
                item {
                    Box(Modifier.fillMaxWidth().height(1.dp))
                }
            }
        }
    }
}
