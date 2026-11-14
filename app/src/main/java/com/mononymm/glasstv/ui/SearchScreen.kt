package com.mononymm.glasstv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mononymm.glasstv.plex.Item
import kotlinx.coroutines.delay

@Composable
fun SearchScreen() {
    val app = LocalApp.current
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Item>>(emptyList()) }
    var hero by remember { mutableStateOf<Item?>(null) }
    var busy by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        busy = true
        results = runCatching { app.plex.search(query) }.getOrDefault(emptyList())
        busy = false
    }
    LaunchedEffect(Unit) { field.focusSoon() }

    BrowseFrame(tab = "search", hero = hero, heroHeight = 120) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search shows and movies", style = G.body) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                textStyle = G.label,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = G.red,
                    unfocusedBorderColor = G.rim,
                    focusedContainerColor = G.glass,
                    unfocusedContainerColor = G.glass,
                    cursorColor = G.red,
                    focusedTextColor = G.text,
                    unfocusedTextColor = G.text,
                ),
                shape = G.pillShape,
                modifier = Modifier.padding(start = EDGE, bottom = 12.dp).width(520.dp).focusRequester(field),
            )
            if (busy && results.isEmpty()) Text("Searching…", style = G.small, modifier = Modifier.padding(start = EDGE + 8.dp))
            else if (!busy && query.isNotBlank() && results.isEmpty()) Text("No results", style = G.small, modifier = Modifier.padding(start = EDGE + 8.dp))
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Adaptive(POSTER_W.dp),
                contentPadding = PaddingValues(start = EDGE, end = EDGE, top = 18.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(results.size, key = { results[it].ratingKey }) { i ->
                    PosterCard(results[i], { f ->
                        hero = f
                        scope.launch { grid.animateScrollToItem(i) }
                    })
                }
            }
        }
    }
}
