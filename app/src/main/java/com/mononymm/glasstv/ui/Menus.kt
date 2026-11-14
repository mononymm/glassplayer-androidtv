package com.mononymm.glasstv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mononymm.glasstv.plex.EngineChoice
import com.mononymm.glasstv.plex.Item
import kotlinx.coroutines.launch

data class MenuEntry(val label: String, val detail: String? = null, val checked: Boolean = false, val action: () -> Unit)

/** Glass list menu in a dialog. Picks close the menu. */
@Composable
fun GlassMenu(title: String?, entries: List<MenuEntry>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(380.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(G.panel)
                .border(1.dp, G.rim, RoundedCornerShape(16.dp))
                .padding(14.dp),
        ) {
            title?.let {
                Text(it, style = G.h2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(entries) { i, e ->
                    FocusBox(
                        onClick = { onDismiss(); e.action() },
                        modifier = Modifier.fillMaxWidth().let { if (i == 0) it.focusRequester(first) else it },
                        shape = RoundedCornerShape(10.dp),
                        focusScale = 1.02f,
                        idleColor = Color.Transparent,
                        focusColor = Color(0x2EFFFFFF),
                        glow = false,
                    ) { f ->
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                (if (e.checked) "●  " else "") + e.label,
                                style = G.label.copy(color = if (f) G.text else G.dim),
                            )
                            e.detail?.let { Text(it, style = G.small.copy(color = G.faint)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
    LaunchedEffect(Unit) { first.focusSoon() }
}

/** Long-press menu for any title. */
@Composable
fun ItemMenu(item: Item, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val scope = app.scope
    val showKey = if (item.isShow) item.ratingKey else item.showKey
    val entries = buildList {
        if (item.playable) {
            add(MenuEntry(if (item.viewOffset > 0) "Resume" else "Play") { play(app, item) })
            if (item.viewOffset > 0) add(MenuEntry("Play from start") { play(app, item, fromStart = true) })
        }
        val sk = item.showKey
        if (item.isEpisode && sk != null) {
            add(MenuEntry("Go to show") {
                scope.launch { app.plex.item(sk)?.let { app.go(Screen.Detail(it)) } }
            })
        }
        if (!item.isEpisode && !item.isMovie) add(MenuEntry("Open") { open(app, item) })
        val what = when {
            item.isShow -> "show"; item.isSeason -> "season"; else -> ""
        }
        add(
            if (item.watched) MenuEntry("Mark ${what} unwatched".replace("  ", " ")) {
                scope.launch { app.plex.markUnwatched(item.ratingKey); app.changed() }
            } else MenuEntry("Mark ${what} watched".replace("  ", " ")) {
                scope.launch { app.plex.markWatched(item.ratingKey); app.changed() }
            },
        )
        if (showKey != null) {
            val cur = app.prefs.engineForShow(showKey)
            add(MenuEntry("Player for this show", cur.label) {
                val nextChoice = EngineChoice.entries[(cur.ordinal + 1) % EngineChoice.entries.size]
                app.prefs.setEngineForShow(showKey, nextChoice)
            })
        }
    }
    GlassMenu(item.showTitle?.takeIf { item.isEpisode }?.let { "$it · ${item.title}" } ?: item.title, entries, onDismiss)
}
