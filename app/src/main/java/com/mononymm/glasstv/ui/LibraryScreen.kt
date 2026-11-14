package com.mononymm.glasstv.ui

import androidx.compose.foundation.layout.Arrangement
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
            LazyVerticalGrid(
                columns = GridCells.Adaptive(POSTER_W.dp),
                contentPadding = PaddingValues(start = EDGE, end = EDGE, top = 18.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                state = grid,
                modifier = Modifier.fillMaxSize(),
            ) {
                // Each row of posters snaps to the top of the grid, titles included.
                itemsIndexed(list, key = { _, it -> it.ratingKey }) { i, it ->
                    PosterCard(it, { f ->
                        hero = f
                        scope.launch { grid.animateScrollToItem(i) }
                    })
                }
            }
        }
    }
}
