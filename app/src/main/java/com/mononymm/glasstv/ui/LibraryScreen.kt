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
    val firstItem = remember { FocusRequester() }
    val grid = rememberLazyGridState()
    var focusedIdx by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(cacheKey, app.refresh) {
        runCatching { app.plex.library(section, sort) }.onSuccess {
            items = it
            app.cache[cacheKey] = it
        }
    }
    LaunchedEffect(items?.isNotEmpty() == true) { if (items?.isNotEmpty() == true) firstItem.focusSoon() }

    var sortMenu by remember { mutableStateOf(false) }
    // No info area here: two full rows of posters. Sort and Player sit in the top strip.
    BrowseFrame(tab = section.key, hero = hero, heroHeight = 0, headerCenter = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("Sort: ${sorts.first { it.second == sort }.first}  ⌄", { sortMenu = true })
            Pill("Player: ${engine.label}", {
                val n = EngineChoice.entries[(engine.ordinal + 1) % EngineChoice.entries.size]
                app.prefs.setEngineForLibrary(section.key, n)
                engine = n
            })
            Text(items?.let { "  ${it.size} titles" } ?: "  Loading…", style = G.small)
        }
    }) {
        if (sortMenu) {
            GlassMenu("Sort by", sorts.map { (label, key) -> MenuEntry(label, checked = sort == key) { sort = key } }) { sortMenu = false }
        }
        Column(Modifier.fillMaxSize()) {
            val list = items ?: emptyList()
            Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                // No top padding: each cell carries its own, so every row sits at the same spot
                // when it snaps up (the first row used to sit 18dp lower than the rest).
                contentPadding = PaddingValues(start = EDGE, end = EDGE, top = 0.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
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
                    }, Modifier.padding(top = 8.dp).let { m -> if (i == 0) m.focusRequester(firstItem) else m }, fill = true)
                }
            }
            if (list.size > 1) {
                ScrollBar(
                    frac = focusedIdx.toFloat() / (list.size - 1),
                    letter = if (sort == sorts[0].second) letterOf(list.getOrNull(focusedIdx)) else null,
                    modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().width(EDGE).padding(top = 10.dp, bottom = 20.dp),
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
 * Where you are in the library. Title sort: the letters # A-Z in one column, with a pill
 * sitting on the current letter. Other sorts: a thin track with a short thumb, like a
 * normal scroll bar.
 */
@Composable
private fun ScrollBar(frac: Float, letter: Char?, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val small = TextStyle(color = Color(0x6EF4F4F6), fontSize = 9.sp, fontWeight = FontWeight.Medium)
    val big = TextStyle(color = Color(0xFF0B0B0F), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Canvas(modifier) {
        val h = size.height
        val cx = size.width / 2
        if (letter == null) {
            val tw = 3.dp.toPx()
            val thumbW = 5.dp.toPx()
            val thumbH = 32.dp.toPx()
            drawRoundRect(Color(0x28FFFFFF), Offset(cx - tw / 2, 0f), Size(tw, h), CornerRadius(tw / 2))
            val y = (h - thumbH) * frac.coerceIn(0f, 1f)
            drawRoundRect(Color(0xE6F4F4F6), Offset(cx - thumbW / 2, y), Size(thumbW, thumbH), CornerRadius(thumbW / 2))
            return@Canvas
        }
        val step = h / LETTERS.size
        val cur = LETTERS.indexOf(letter).coerceAtLeast(0)
        val py = step * (cur + 0.5f)
        val pw = 20.dp.toPx()
        val ph = 24.dp.toPx()
        drawRoundRect(Color(0xF2F4F4F6), Offset(cx - pw / 2, py - ph / 2), Size(pw, ph), CornerRadius(pw / 2))
        LETTERS.forEachIndexed { i, ch ->
            if (kotlin.math.abs(i - cur) == 1) return@forEachIndexed // tucked under the pill
            val t = measurer.measure(ch.toString(), if (i == cur) big else small)
            val y = step * (i + 0.5f)
            drawText(t, topLeft = Offset(cx - t.size.width / 2f, y - t.size.height / 2f))
        }
    }
}
