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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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

/** Top strip: logo at the left inset, date and time at the right inset. Nothing focusable. */
@Composable
fun Header(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 4.dp).height(40.dp)) {
        Logo(Modifier.align(Alignment.CenterStart).size(28.dp))
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
        Text(SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(now)), style = G.label.copy(color = G.dim, fontSize = 13.sp))
        Spacer(Modifier.width(12.dp))
        Text(DateFormat.getTimeFormat(ctx).format(Date(now)), style = G.h2)
    }
}

private enum class RailIcon { HOME, MOVIES, SHOWS, MUSIC, PHOTOS, OTHER, SEARCH, SETTINGS }

private fun iconFor(s: Section) = when (s.type) {
    "movie" -> RailIcon.MOVIES
    "show" -> RailIcon.SHOWS
    "artist" -> RailIcon.MUSIC
    "photo" -> RailIcon.PHOTOS
    else -> RailIcon.OTHER
}

private val RAIL_COLLAPSED = 48.dp
private val RAIL_OPEN = 248.dp

/**
 * Icons in the left margin; it opens into a side bar when it takes focus (Left from the
 * first tile). Hold OK on a library to move it: Up/Down, then OK.
 */
@Composable
fun SideRail(selected: String) {
    val app = LocalApp.current
    LaunchedEffect(Unit) {
        if (app.sections.isEmpty()) runCatching { app.sections = app.plex.sections() }
    }
    var open by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<String?>(null) }
    var armed by remember { mutableStateOf(false) }
    val selectedReq = remember { FocusRequester() }
    val width by animateDpAsState(if (open) RAIL_OPEN else RAIL_COLLAPSED, tween(200), label = "rail")
    // Entering the rail lands on the current page, not whichever icon was nearest.
    LaunchedEffect(open) { if (open) selectedReq.focusSoon() else moving = null }

    Box(Modifier.fillMaxSize()) {
        if (open) Box(Modifier.fillMaxSize().background(Color(0x73000000)))
        Column(
            Modifier.fillMaxHeight().width(width)
                .background(if (open) Color(0xFF0E0E13) else Color.Transparent)
                .let { if (open) it.border(1.dp, Color(0x14FFFFFF)) else it }
                // Only Left (from the first tile) opens the rail; Up/Down from the page never wander in.
                .focusProperties {
                    onEnter = { if (requestedFocusDirection != FocusDirection.Left) cancelFocusChange() }
                }
                .focusGroup()
                .onFocusChanged { open = it.hasFocus }
                .onPreviewKeyEvent { ev ->
                    val m = moving ?: return@onPreviewKeyEvent false
                    val k = ev.nativeKeyEvent.keyCode
                    val down = ev.type == KeyEventType.KeyDown
                    if (down) armed = true
                    when (k) {
                        AK.KEYCODE_DPAD_UP -> { if (down) app.moveSection(m, -1); true }
                        AK.KEYCODE_DPAD_DOWN -> { if (down) app.moveSection(m, 1); true }
                        AK.KEYCODE_DPAD_LEFT, AK.KEYCODE_DPAD_RIGHT -> true
                        AK.KEYCODE_DPAD_CENTER, AK.KEYCODE_ENTER, AK.KEYCODE_NUMPAD_ENTER, AK.KEYCODE_BACK, AK.KEYCODE_ESCAPE -> {
                            // The release of the long press that started moving doesn't count.
                            if (!down && armed) moving = null
                            true
                        }
                        else -> false
                    }
                },
        ) {
            // Logo row (open only; when collapsed the page header shows the logo here).
            Row(Modifier.padding(start = 48.dp, top = 20.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                if (open) {
                    Logo(Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Glass", style = G.h2)
                }
            }
            Spacer(Modifier.height(66.dp))
            RailItem(RailIcon.HOME, "Home", selected == "home", open, false, if (selected == "home") selectedReq else null,
                onClick = { if (selected != "home") app.replaceAll(Screen.Home) })
            app.orderedSections.forEach { s ->
                key(s.key) {
                    RailItem(iconFor(s), s.title, selected == s.key, open, moving == s.key, if (selected == s.key) selectedReq else null,
                        onClick = {
                            if (selected != s.key) {
                                app.replaceAll(Screen.Home)
                                app.go(Screen.Library(s))
                            }
                        },
                        onLongClick = { moving = s.key; armed = false })
                }
            }
            if (open) {
                Text(
                    if (moving != null) "↑ ↓ to move, OK when done" else "Hold OK on a library to move it",
                    style = G.small.copy(color = if (moving != null) G.red else G.faint, fontSize = 11.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 24.dp, top = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (open) Box(Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(1.dp).background(Color(0x1EFFFFFF)))
            Spacer(Modifier.height(14.dp))
            RailItem(RailIcon.SEARCH, "Search", selected == "search", open, false, if (selected == "search") selectedReq else null,
                onClick = { if (selected != "search") app.go(Screen.Search) })
            RailItem(RailIcon.SETTINGS, "Settings", selected == "settings", open, false, if (selected == "settings") selectedReq else null,
                onClick = { if (selected != "settings") app.go(Screen.Settings) })
            Spacer(Modifier.height(48.dp))
        }
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
) {
    val mod = Modifier.padding(start = if (open) 10.dp else 6.dp, end = if (open) 14.dp else 6.dp, top = 4.dp, bottom = 4.dp)
        .height(38.dp)
        .let { if (open) it.fillMaxWidth() else it.width(36.dp) }
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
