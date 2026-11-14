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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mononymm.glasstv.plex.Item

const val POSTER_W = 128
const val POSTER_H = 192
const val WIDE_W = 248
const val WIDE_H = 140

@Composable
fun PosterCard(item: Item, onFocus: (Item) -> Unit, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    var menu by remember { mutableStateOf(false) }
    Column(modifier.width(POSTER_W.dp)) {
        FocusBox(
            onClick = { open(app, item) },
            onLongClick = { menu = true },
            modifier = Modifier.size(POSTER_W.dp, POSTER_H.dp),
            onFocus = { onFocus(item) },
        ) { _ ->
            NetImage(app.plex.image(item.poster, 300, 450), Modifier.fillMaxSize())
            if (item.watched) WatchedBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            else if (item.isShow && item.leafCount > item.viewedLeafCount && item.viewedLeafCount > 0) {
                CountBadge(item.leafCount - item.viewedLeafCount, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            if (item.progress > 0f && !item.watched) {
                ProgressLine(item.progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, style = G.small.copy(color = G.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.year?.toString() ?: "", style = G.small.copy(color = G.faint), maxLines = 1)
    }
    if (menu) ItemMenu(item) { menu = false }
}

/** 16:9 card for Continue watching and episodes. */
@Composable
fun WideCard(
    item: Item,
    onFocus: (Item) -> Unit,
    modifier: Modifier = Modifier,
    showName: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val app = LocalApp.current
    var menu by remember { mutableStateOf(false) }
    Column(modifier.width(WIDE_W.dp)) {
        FocusBox(
            onClick = { onClick?.invoke() ?: play(app, item) },
            onLongClick = { menu = true },
            modifier = Modifier.size(WIDE_W.dp, WIDE_H.dp),
            onFocus = { onFocus(item) },
        ) { f ->
            val still = if (item.isEpisode) item.thumb else (item.art ?: item.thumb)
            NetImage(app.plex.image(still, 496, 280), Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xCC000000)),
                ),
            )
            if (f) {
                Box(Modifier.align(Alignment.Center).size(46.dp).clip(CircleShape).background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                    PlayGlyph(G.red, 20.dp)
                }
            }
            if (item.watched) WatchedBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth()) {
                item.episodeTag?.let { Text(it, style = G.small.copy(color = G.dim)) }
                if (item.progress > 0f && !item.watched) {
                    Spacer(Modifier.height(5.dp))
                    ProgressLine(item.progress, Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val top = if (showName && item.isEpisode) item.showTitle ?: item.title else item.title
        Text(top, style = G.small.copy(color = G.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = when {
            showName && item.isEpisode -> item.title
            item.isEpisode -> remaining(item) ?: minutes(item.duration)
            else -> item.year?.toString() ?: ""
        }
        Text(sub, style = G.small.copy(color = G.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (menu) ItemMenu(item) { menu = false }
}

@Composable
fun WatchedBadge(modifier: Modifier = Modifier) {
    Box(modifier.size(18.dp).clip(CircleShape).background(G.red), contentAlignment = Alignment.Center) {
        Text("✓", style = G.small.copy(color = Color.White))
    }
}

@Composable
fun CountBadge(n: Int, modifier: Modifier = Modifier) {
    Box(modifier.clip(G.pillShape).background(G.red).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text("$n", style = G.small.copy(color = Color.White))
    }
}

@Composable
fun ItemRow(
    title: String,
    items: List<Item>,
    wide: Boolean,
    onFocus: (Item) -> Unit,
) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Text(title, style = G.h2, modifier = Modifier.padding(start = 48.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 14.dp),
        ) {
            items(items, key = { it.ratingKey }) {
                if (wide) WideCard(it, onFocus) else PosterCard(it, onFocus)
            }
        }
    }
}

fun minutes(ms: Long): String {
    if (ms <= 0) return ""
    val m = (ms / 60000).toInt()
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

fun remaining(item: Item): String? =
    if (item.viewOffset > 0 && item.duration > 0) minutes(item.duration - item.viewOffset) + " left" else null

/** Open a title: shows/movies go to detail, episodes start playing. */
fun open(app: AppModel, item: Item) {
    when {
        item.isEpisode -> app.go(Screen.Player(item, false))
        item.isSeason -> app.go(Screen.Detail(item.copy(ratingKey = item.showKey ?: item.ratingKey, type = "show", title = item.showTitle ?: item.title)))
        else -> app.go(Screen.Detail(item))
    }
}

fun play(app: AppModel, item: Item, fromStart: Boolean = false) {
    if (item.playable) app.go(Screen.Player(item, fromStart)) else open(app, item)
}

@Composable
fun MetaLine(item: Item, extra: String? = null) {
    val parts = buildList {
        item.year?.let { add(it.toString()) }
        item.contentRating?.let { add(it) }
        if (item.isShow && item.leafCount > 0) add("${item.leafCount} episodes")
        if (item.isMovie || item.isEpisode) minutes(item.duration).takeIf { it.isNotBlank() }?.let { add(it) }
        item.rating?.takeIf { it > 0 }?.let { add("★ %.1f".format(it)) }
        extra?.let { add(it) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        parts.forEach { Text(it, style = G.small.copy(color = G.dim)) }
    }
}
