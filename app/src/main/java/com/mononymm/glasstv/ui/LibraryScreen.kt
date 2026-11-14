package com.mononymm.glasstv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import androidx.compose.ui.unit.dp
import com.mononymm.glasstv.plex.EngineChoice
import com.mononymm.glasstv.plex.Item
import com.mononymm.glasstv.plex.Section

private val sorts = listOf(
    "Title" to "titleSort:asc",
    "Recently added" to "addedAt:desc",
    "Recently watched" to "lastViewedAt:desc",
    "Year" to "originallyAvailableAt:desc",
    "Rating" to "audienceRating:desc",
)

@Composable
fun LibraryScreen(section: Section) {
    val app = LocalApp.current
    var sort by rememberSaveable { mutableStateOf(sorts[0].second) }
    val cacheKey = "lib:${section.key}:$sort"
    var items by remember(cacheKey) { mutableStateOf(app.cached<List<Item>>(cacheKey)) }
    var hero by remember { mutableStateOf<Item?>(null) }
    var engine by remember { mutableStateOf(app.prefs.engineForLibrary(section.key)) }
    val firstSort = remember { FocusRequester() }
    val grid = rememberLazyGridState()
    var focusedIdx by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(cacheKey, app.refresh) {
        runCatching { app.plex.library(section, sort) }.onSuccess {
            items = it
            app.cache[cacheKey] = it
        }
    }
    LaunchedEffect(Unit) { firstSort.focusSoon() }

    BrowseFrame(tab = section.key, hero = hero, heroHeight = 150) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.padding(start = EDGE, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                sorts.forEachIndexed { i, (label, key) ->
                    Pill(label, { sort = key }, Modifier.let { if (i == 0) it.focusRequester(firstSort) else it }, selected = sort == key)
                }
                Spacer(Modifier.width(18.dp))
                Pill("Player: ${engine.label}", {
                    val n = EngineChoice.entries[(engine.ordinal + 1) % EngineChoice.entries.size]
                    app.prefs.setEngineForLibrary(section.key, n)
                    engine = n
                })
                Spacer(Modifier.width(12.dp))
                Text(items?.let { "${it.size} titles" } ?: "Loading…", style = G.small)
            }
            val list = items ?: emptyList()
            Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(POSTER_W.dp),
                // No top padding: each cell carries its own, so every row sits at the same spot
                // when it snaps up (the first row used to sit 18dp lower than the rest).
                contentPadding = PaddingValues(start = EDGE, end = EDGE, top = 0.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                state = grid,
                modifier = Modifier.fillMaxSize(),
            ) {
                // Each row of posters snaps to the top of the grid, titles included.
                itemsIndexed(list, key = { _, it -> it.ratingKey }) { i, it ->
                    PosterCard(it, { f ->
                        hero = f
                        focusedIdx = i
                        scope.launch { grid.animateScrollToItem(i) }
                    }, Modifier.padding(top = 10.dp))
                }
            }
            if (list.size > 1) {
                ScrollBar(
                    frac = focusedIdx.toFloat() / (list.size - 1),
                    letter = if (sort == sorts[0].second) letterOf(list.getOrNull(focusedIdx)) else null,
                    modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().width(EDGE).padding(top = 12.dp, bottom = 28.dp),
                )
            }
            }
        }
    }
}

private fun letterOf(item: Item?): Char? {
    val c = (item?.titleSort ?: item?.title)?.trim()?.firstOrNull()?.uppercaseChar() ?: return null
    return if (c in 'A'..'Z') c else '#'
}

private val LETTERS = listOf('#') + ('A'..'Z')

/**
 * Where you are in the library: a thin track with a red fill, and for the Title sort the
 * letters # A–Z with the current one marked.
 */
@Composable
private fun ScrollBar(frac: Float, letter: Char?, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val small = TextStyle(color = Color(0x6EF4F4F6), fontSize = 9.sp, fontWeight = FontWeight.Medium)
    val big = TextStyle(color = Color(0xFF0B0B0F), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Canvas(modifier) {
        val h = size.height
        val cx = size.width / 2
        val trackX = cx + 14.dp.toPx()
        val tw = 3.dp.toPx()
        drawRoundRect(Color(0x28FFFFFF), Offset(trackX - tw / 2, 0f), Size(tw, h), CornerRadius(tw / 2))
        drawRoundRect(G.red, Offset(trackX - tw / 2, 0f), Size(tw, h * frac.coerceIn(0f, 1f)), CornerRadius(tw / 2))
        if (letter != null) {
            val step = h / LETTERS.size
            LETTERS.forEachIndexed { i, ch ->
                val y = step * (i + 0.5f)
                if (ch == letter) {
                    drawCircle(Color(0xF2F4F4F6), 8.dp.toPx(), Offset(cx, y))
                    val t = measurer.measure(ch.toString(), big)
                    drawText(t, topLeft = Offset(cx - t.size.width / 2f, y - t.size.height / 2f))
                } else {
                    val t = measurer.measure(ch.toString(), small)
                    drawText(t, topLeft = Offset(cx - t.size.width / 2f, y - t.size.height / 2f))
                }
            }
        }
    }
}
