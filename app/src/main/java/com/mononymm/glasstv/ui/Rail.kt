package com.mononymm.glasstv.ui

import android.text.format.DateFormat
import android.view.KeyEvent as AK
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mononymm.glasstv.plex.Section
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Left/right inset for browse content; the icon rail lives in the left one. */
val EDGE = 80.dp
private val RAIL_COLLAPSED = 64.dp
private val RAIL_OPEN = 248.dp

/** Top strip: logo over the rail column, date and time mirrored at the right. Nothing focusable. */
@Composable
fun Header(modifier: Modifier = Modifier, center: (@Composable () -> Unit)? = null) {
    Box(modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 4.dp).height(40.dp)) {
        Logo(Modifier.align(Alignment.CenterStart).size(28.dp))
        center?.let { Box(Modifier.align(Alignment.Center)) { it() } }
        Clock(Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
fun Clock(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(now)), style = G.label.copy(color = G.dim, fontSize = 13.sp, shadow = ClockShadow))
        Spacer(Modifier.width(12.dp))
        Text(DateFormat.getTimeFormat(ctx).format(Date(now)), style = G.h2.copy(shadow = ClockShadow))
    }
}

private val ClockShadow = androidx.compose.ui.graphics.Shadow(Color(0xCC000000), Offset(0f, 2f), 8f)

private enum class RailIcon { HOME, MOVIES, SHOWS, MUSIC, PHOTOS, OTHER, SEARCH, SETTINGS, ALL }

private fun typeLabel(s: Section) = when (s.type) {
    "movie" -> "Movies"; "show" -> "TV shows"; "artist" -> "Music"; "photo" -> "Photos"; else -> "Other"
}

private fun iconFor(s: Section) = when (s.type) {
    "movie" -> RailIcon.MOVIES
    "show" -> RailIcon.SHOWS
    "artist" -> RailIcon.MUSIC
    "photo" -> RailIcon.PHOTOS
    else -> RailIcon.OTHER
}

private const val STEP_DP = 44

/** One entry in the side bar's library list ("home" or a section key). */
private class RailEntry(val key: String, val icon: RailIcon, val label: String, val section: Section?)

/**
 * Icons in the left margin. The page opens it (Left with nothing further left); while
 * closed its items can't take focus. It opens on Home; Up reaches Search, and Left from
 * Search is Settings. Right (from anything but Settings) or Back closes it and returns
 * focus to where it was. Libraries page in whole chunks with arrows when there are more.
 * Hold OK on a library until it lifts, let go, Up/Down to move it, OK to drop it.
 */
@Composable
fun SideRail(selected: String, open: Boolean, close: () -> Unit) {
    val app = LocalApp.current
    LaunchedEffect(Unit) {
        if (app.sections.isEmpty()) runCatching { app.sections = app.plex.sections() }
    }
    var moving by remember { mutableStateOf<String?>(null) }
    var armed by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    var focusIdx by remember { mutableIntStateOf(0) }
    var top by remember { mutableStateOf<String?>(null) } // "settings" / "search" while one has focus
    var wanted by remember { mutableStateOf<Int?>(null) }
    var per by remember { mutableIntStateOf(6) }
    val slots = remember { List(24) { FocusRequester() } }
    val searchReq = remember { FocusRequester() }
    val width by animateDpAsState(if (open) RAIL_OPEN else RAIL_COLLAPSED, tween(200), label = "rail")

    var options by remember { mutableStateOf<Section?>(null) }
    var showAll by remember { mutableStateOf(false) }
    // Home, the pinned libraries, then All libraries.
    val entries = listOf(RailEntry("home", RailIcon.HOME, "Home", null)) +
        app.pinnedSections.map { RailEntry(it.key, iconFor(it), it.title, it) } +
        RailEntry("all", RailIcon.ALL, "All libraries", null)
    val pages = maxOf(1, (entries.size + per - 1) / per)
    if (page >= pages) page = pages - 1

    LaunchedEffect(open) {
        if (open) { page = 0; wanted = 0 } else moving = null
    }
    LaunchedEffect(wanted, page, per) {
        val w = wanted ?: return@LaunchedEffect
        val slot = w - page * per
        if (slot in 0 until per) slots[slot].focusSoon()
        wanted = null
    }

    fun goTo(idx: Int) {
        val i = idx.coerceIn(0, entries.lastIndex)
        page = i / per
        wanted = i
    }

    Box(Modifier.fillMaxSize()) {
        if (open) Box(Modifier.fillMaxSize().background(Color(0x73000000)))
        Column(
            Modifier.fillMaxHeight().width(width)
                .background(if (open) Skin.drawer else Color.Transparent)
                .onPreviewKeyEvent { ev ->
                    if (!open) return@onPreviewKeyEvent false
                    val k = ev.nativeKeyEvent.keyCode
                    val down = ev.type == KeyEventType.KeyDown
                    val first = down && ev.nativeKeyEvent.repeatCount == 0
                    val m = moving
                    if (m != null) {
                        // Only a fresh press counts: the held OK that started moving keeps repeating.
                        if (first) armed = true
                        return@onPreviewKeyEvent when (k) {
                            AK.KEYCODE_DPAD_UP, AK.KEYCODE_DPAD_DOWN -> {
                                if (down) {
                                    val dir = if (k == AK.KEYCODE_DPAD_UP) -1 else 1
                                    val idx = entries.indexOfFirst { it.key == m }
                                    // Home stays on top and All libraries at the bottom.
                                    if (idx + dir >= 1 && idx + dir <= entries.lastIndex - 1) {
                                        app.moveSection(m, dir)
                                        goTo(idx + dir)
                                    }
                                }
                                true
                            }
                            AK.KEYCODE_DPAD_LEFT, AK.KEYCODE_DPAD_RIGHT -> true
                            AK.KEYCODE_DPAD_CENTER, AK.KEYCODE_ENTER, AK.KEYCODE_NUMPAD_ENTER, AK.KEYCODE_BACK, AK.KEYCODE_ESCAPE -> {
                                if (!down && armed) moving = null
                                true
                            }
                            else -> false
                        }
                    }
                    when (k) {
                        AK.KEYCODE_BACK, AK.KEYCODE_ESCAPE -> { if (!down) close(); true }
                        AK.KEYCODE_DPAD_RIGHT -> if (top == "settings") false else { if (down) close(); true }
                        AK.KEYCODE_DPAD_LEFT -> top != "search" // Search -> Settings; nothing else goes left
                        AK.KEYCODE_DPAD_UP -> when {
                            top != null -> true
                            focusIdx == page * per && page > 0 -> { if (down) goTo(focusIdx - 1); true }
                            focusIdx == 0 -> { if (down) searchReq.requestFocus(); true }
                            else -> false
                        }
                        AK.KEYCODE_DPAD_DOWN -> when {
                            top != null -> { if (down) goTo(page * per); true }
                            focusIdx == minOf(entries.lastIndex, page * per + per - 1) -> {
                                if (down && focusIdx < entries.lastIndex) goTo(focusIdx + 1)
                                true
                            }
                            else -> false
                        }
                        else -> false
                    }
                },
        ) {
            // Logo row (open only; when closed the page header shows the logo in this spot).
            Row(Modifier.padding(start = 18.dp, top = 20.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                if (open) {
                    Logo(Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Glass", style = G.h2)
                    Spacer(Modifier.width(8.dp))
                    Text("v${app.version}", style = G.small.copy(color = G.faint, fontSize = 11.sp))
                }
            }
            // Settings | Search
            Row(
                Modifier.fillMaxWidth().padding(start = if (open) 10.dp else 8.dp, end = if (open) 14.dp else 8.dp, top = 18.dp).height(38.dp),
                horizontalArrangement = Arrangement.spacedBy(if (open) 6.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Settings: a round button. Search: a long pill, like a search field.
                TopButton(RailIcon.SETTINGS, "Settings", open, if (open) Modifier.size(38.dp) else Modifier.weight(1f), onFocus = { top = it }, key = "settings",
                    round = true, onClick = { if (selected != "settings") app.go(Screen.Settings) else close() })
                TopButton(RailIcon.SEARCH, "Search", open, Modifier.weight(1f).focusRequester(searchReq), onFocus = { top = it }, key = "search",
                    onClick = { if (selected != "search") app.go(Screen.Search) else close() })
            }
            if (open) Box(Modifier.padding(start = 24.dp, end = 28.dp, top = 12.dp).fillMaxWidth().height(1.dp).background(Color(0x1EFFFFFF)))
            else Spacer(Modifier.height(13.dp))
            // Libraries, a whole page at a time.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val fit = ((maxHeight - 64.dp) / STEP_DP.dp).toInt().coerceIn(1, slots.size)
                if (fit != per) per = fit
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.Center) {
                        if (page > 0) Chevron(up = true, open = open)
                    }
                    val start = page * per
                    entries.subList(minOf(start, entries.size), minOf(start + per, entries.size)).forEachIndexed { slot, e ->
                        val idx = start + slot
                        key(e.key) {
                            RailItem(
                                e.icon, e.label, selected == e.key, open, moving == e.key, slots[slot],
                                onClick = {
                                    when {
                                        e.key == "all" -> showAll = true
                                        selected == e.key -> close()
                                        e.section == null -> app.replaceAll(Screen.Home)
                                        else -> { app.replaceAll(Screen.Home); app.go(Screen.Library(e.section)) }
                                    }
                                },
                                onLongClick = if (e.section != null) ({ options = e.section }) else null,
                                trailing = if (e.key == "all") app.sections.size.toString() else null,
                                onFocus = { focusIdx = idx; top = null },
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.Center) {
                        if (start + per < entries.size) Chevron(up = false, open = open)
                    }
                }
            }
            if (open) {
                Text(
                    if (moving != null) "↑ ↓ to move, OK when done" else "Hold OK on a library for options",
                    style = G.small.copy(color = if (moving != null) G.red else G.faint, fontSize = 11.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 24.dp, bottom = 16.dp),
                )
            } else Spacer(Modifier.height(32.dp))
        }
        options?.let { sec ->
            GlassMenu(sec.title, listOf(
                MenuEntry("Move", "↑ ↓ to move, OK when done") { moving = sec.key; armed = false },
                MenuEntry("Unpin from side bar", "Still in All libraries") {
                    val idx = entries.indexOfFirst { it.key == sec.key }
                    app.setPinned(sec.key, false)
                    goTo((idx - 1).coerceAtLeast(0))
                },
            )) { options = null }
        }
        if (showAll) {
            AllLibraries(
                onOpen = { s ->
                    showAll = false
                    if (selected == s.key) close() else { app.replaceAll(Screen.Home); app.go(Screen.Library(s)) }
                },
                onClose = { showAll = false; goTo(entries.lastIndex) },
            )
        }
    }
}

/** Every library, pinned or not: OK opens it, the pin on the right pins or unpins it. */
@Composable
private fun AllLibraries(onOpen: (Section) -> Unit, onClose: () -> Unit) {
    val app = LocalApp.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.focusSoon() }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
    Box(
        Modifier.fillMaxSize().background(Color(0x78000000))
            .onPreviewKeyEvent { ev ->
                val k = ev.nativeKeyEvent.keyCode
                if (k == AK.KEYCODE_BACK || k == AK.KEYCODE_ESCAPE) {
                    if (ev.type == KeyEventType.KeyUp) onClose()
                    true
                } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(440.dp).clip(shape).background(Skin.card).border(1.dp, G.rim, shape)
                .padding(horizontal = 10.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("All libraries", style = G.h2, modifier = Modifier.padding(bottom = 14.dp))
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 340.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(app.orderedSections.size) { i ->
                    val s = app.orderedSections[i]
                    val pinned = s.key !in app.hiddenSections
                    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FocusBox(
                            onClick = { onOpen(s) },
                            modifier = Modifier.weight(1f).height(40.dp).let { if (i == 0) it.focusRequester(first) else it },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            focusScale = 1.02f,
                            idleColor = Color.Transparent,
                            focusColor = Color(0x2EFFFFFF),
                            idleRim = Color.Transparent,
                            glow = false,
                        ) { f ->
                            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                val c = if (f) G.text else G.dim
                                Canvas(Modifier.size(18.dp)) { drawRailIcon(iconFor(s), c) }
                                Spacer(Modifier.width(12.dp))
                                Text(s.title, style = G.label.copy(color = c), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(typeLabel(s), style = G.small.copy(color = G.faint))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        FocusBox(
                            onClick = { app.setPinned(s.key, !pinned) },
                            modifier = Modifier.size(34.dp),
                            shape = CircleShape,
                            focusScale = 1.08f,
                            idleColor = Color(0x14FFFFFF),
                            focusColor = Color(0xF2F4F4F6),
                            idleRim = Color.Transparent,
                            glow = false,
                        ) { f ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                val c = when {
                                    f -> Color(0xFF0B0B0F)
                                    pinned -> G.red
                                    else -> Color(0x8CF4F4F6)
                                }
                                Canvas(Modifier.size(16.dp)) { drawPin(c, pinned) }
                            }
                        }
                    }
                }
            }
            Text("OK opens a library  ·  → then OK pins or unpins it", style = G.small.copy(color = G.faint), modifier = Modifier.padding(top = 12.dp))
        }
    }
}

private fun DrawScope.drawPin(c: Color, filled: Boolean) {
    val w = size.width
    val head = Path().apply {
        moveTo(w * 0.22f, w * 0.06f); lineTo(w * 0.78f, w * 0.06f); lineTo(w * 0.68f, w * 0.40f)
        lineTo(w * 0.86f, w * 0.58f); lineTo(w * 0.14f, w * 0.58f); lineTo(w * 0.32f, w * 0.40f); close()
    }
    if (filled) drawPath(head, c) else drawPath(head, c, style = Stroke(w * 0.09f, join = StrokeJoin.Round))
    drawLine(c, Offset(w * 0.5f, w * 0.58f), Offset(w * 0.5f, w * 0.96f), w * 0.1f, StrokeCap.Round)
}

@Composable
private fun TopButton(icon: RailIcon, label: String, open: Boolean, modifier: Modifier, key: String, onFocus: (String?) -> Unit, round: Boolean = false, onClick: () -> Unit) {
    FocusBox(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        shape = if (round) CircleShape else G.pillShape,
        focusScale = 1.04f,
        idleColor = if (open) Color(0x14FFFFFF) else Color.Transparent,
        focusColor = Color(0xF2F4F4F6),
        idleRim = if (open && !round) Color(0x24FFFFFF) else Color.Transparent,
        glow = false,
        focusEnabled = open,
        onFocus = { onFocus(key) },
    ) { f ->
        val c = when {
            f -> Color(0xFF0B0B0F)
            open -> G.dim
            else -> Color(0x78F4F4F6)
        }
        Row(
            Modifier.fillMaxSize().padding(start = if (open && !round) 16.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (open && !round) Arrangement.Start else Arrangement.Center,
        ) {
            Canvas(Modifier.size(if (open) 18.dp else 16.dp)) { drawRailIcon(icon, c) }
            if (open && !round) {
                Spacer(Modifier.width(10.dp))
                Text(label, style = G.label.copy(color = c), maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Chevron(up: Boolean, open: Boolean) {
    val c = if (open) G.dim else Color(0x78F4F4F6)
    Canvas(Modifier.size(12.dp, 8.dp)) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            if (up) { moveTo(0f, h); lineTo(w / 2, 0f); lineTo(w, h) } else { moveTo(0f, 0f); lineTo(w / 2, h); lineTo(w, 0f) }
        }
        drawPath(p, c, style = Stroke(w * 0.15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun RailItem(
    icon: RailIcon,
    label: String,
    selected: Boolean,
    open: Boolean,
    moving: Boolean,
    req: FocusRequester?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    trailing: String? = null,
) {
    val mod = Modifier.padding(start = if (open) 10.dp else 12.dp, end = if (open) 14.dp else 12.dp, top = 3.dp, bottom = 3.dp) // 44dp a row
        .height(38.dp)
        .let { if (open) it.fillMaxWidth() else it.width(40.dp) }
        .let { if (req != null) it.focusRequester(req) else it }
    FocusBox(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = mod,
        shape = G.pillShape,
        focusScale = 1.03f,
        idleColor = if (selected) Color(0x2EFFFFFF) else Color.Transparent,
        focusColor = if (moving) Color(0x40E8333F) else Color(0xF2F4F4F6),
        idleRim = if (moving) G.red else Color.Transparent,
        glow = false,
        focusEnabled = open,
        onFocus = onFocus,
    ) { f ->
        val c = when {
            f && !moving -> Color(0xFF0B0B0F)
            selected || moving -> G.text
            open -> G.dim
            else -> Color(0x78F4F4F6)
        }
        Row(
            Modifier.fillMaxSize().padding(start = if (open) 14.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (open) Arrangement.Start else Arrangement.Center,
        ) {
            Canvas(Modifier.size(20.dp)) { drawRailIcon(icon, c) }
            if (open) {
                Spacer(Modifier.width(14.dp))
                Text(label, style = G.label.copy(color = c, fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (moving) Text("↕", style = G.label.copy(color = G.red))
                trailing?.let { Text(it, style = G.small.copy(color = if (f) Color(0x990B0B0F) else G.faint)) }
            }
        }
    }
}

private fun DrawScope.drawRailIcon(icon: RailIcon, c: Color) {
    val w = size.width
    val sw = w * 0.09f
    val st = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun o(x: Float, y: Float) = Offset(w * x, w * y)
    when (icon) {
        RailIcon.HOME -> {
            drawPath(Path().apply { moveTo(w * 0.05f, w * 0.48f); lineTo(w * 0.5f, w * 0.08f); lineTo(w * 0.95f, w * 0.48f) }, c, style = st)
            drawPath(Path().apply { moveTo(w * 0.18f, w * 0.40f); lineTo(w * 0.18f, w * 0.90f); lineTo(w * 0.82f, w * 0.90f); lineTo(w * 0.82f, w * 0.40f) }, c, style = st)
        }
        RailIcon.MOVIES -> {
            drawRoundRect(c, o(0.08f, 0.15f), Size(w * 0.84f, w * 0.70f), CornerRadius(w * 0.12f), style = st)
            for (y in listOf(0.32f, 0.5f, 0.68f)) {
                drawRect(c, o(0.17f, y - 0.04f), Size(w * 0.08f, w * 0.08f))
                drawRect(c, o(0.75f, y - 0.04f), Size(w * 0.08f, w * 0.08f))
            }
        }
        RailIcon.SHOWS -> {
            drawRoundRect(c, o(0.05f, 0.12f), Size(w * 0.90f, w * 0.62f), CornerRadius(w * 0.12f), style = st)
            drawLine(c, o(0.30f, 0.90f), o(0.70f, 0.90f), sw, StrokeCap.Round)
        }
        RailIcon.MUSIC -> {
            drawCircle(c, w * 0.14f, o(0.30f, 0.76f))
            drawLine(c, o(0.42f, 0.76f), o(0.42f, 0.14f), sw, StrokeCap.Round)
            drawLine(c, o(0.42f, 0.14f), o(0.80f, 0.26f), sw, StrokeCap.Round)
        }
        RailIcon.PHOTOS -> {
            drawRoundRect(c, o(0.06f, 0.14f), Size(w * 0.88f, w * 0.72f), CornerRadius(w * 0.12f), style = st)
            drawPath(Path().apply { moveTo(w * 0.14f, w * 0.78f); lineTo(w * 0.40f, w * 0.48f); lineTo(w * 0.60f, w * 0.68f); lineTo(w * 0.86f, w * 0.40f) }, c, style = st)
        }
        RailIcon.OTHER -> {
            drawPath(Path().apply {
                moveTo(w * 0.06f, w * 0.22f); lineTo(w * 0.40f, w * 0.22f); lineTo(w * 0.50f, w * 0.32f); lineTo(w * 0.94f, w * 0.32f)
                lineTo(w * 0.94f, w * 0.84f); lineTo(w * 0.06f, w * 0.84f); close()
            }, c, style = st)
        }
        RailIcon.ALL -> {
            for (x in listOf(0.08f, 0.56f)) for (y in listOf(0.08f, 0.56f)) {
                drawRoundRect(c, o(x, y), Size(w * 0.36f, w * 0.36f), CornerRadius(w * 0.08f), style = st)
            }
        }
        RailIcon.SEARCH -> {
            drawCircle(c, w * 0.32f, o(0.42f, 0.42f), style = st)
            drawLine(c, o(0.66f, 0.66f), o(0.92f, 0.92f), sw * 1.2f, StrokeCap.Round)
        }
        RailIcon.SETTINGS -> {
            val ctr = o(0.5f, 0.5f)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val dx = kotlin.math.cos(a).toFloat()
                val dy = kotlin.math.sin(a).toFloat()
                drawLine(c, Offset(ctr.x + dx * w * 0.30f, ctr.y + dy * w * 0.30f), Offset(ctr.x + dx * w * 0.46f, ctr.y + dy * w * 0.46f), w * 0.15f, StrokeCap.Butt)
            }
            drawCircle(c, w * 0.31f, ctr, style = Stroke(w * 0.12f))
        }
    }
}
