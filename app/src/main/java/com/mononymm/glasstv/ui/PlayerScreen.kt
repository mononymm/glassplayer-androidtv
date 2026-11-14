package com.mononymm.glasstv.ui

import android.text.format.DateFormat
import android.view.KeyEvent as AK
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mononymm.glasstv.plex.EngineChoice
import com.mononymm.glasstv.plex.Item
import com.mononymm.glasstv.player.PlaybackSession
import com.mononymm.glasstv.player.PlayerState
import com.mononymm.glasstv.player.TrackInfo
import kotlinx.coroutines.delay
import java.util.Date

private enum class Panel { AUDIO, SUBS, SPEED, INFO }

private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
fun PlayerScreen(item: Item, fromStart: Boolean) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val session = remember { PlaybackSession(ctx, app.plex, item, fromStart) }
    DisposableEffect(Unit) {
        onDispose {
            session.close()
            app.changed()
        }
    }

    val engine = session.engine
    val st: PlayerState? = engine?.state

    var controls by remember { mutableStateOf(true) }
    var peek by remember { mutableStateOf(false) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var scrubAt by remember { mutableLongStateOf(0L) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastAt by remember { mutableLongStateOf(0L) }
    var boosting by remember { mutableStateOf(false) }
    var boostFrom by remember { mutableStateOf(1f) }
    var countdown by remember { mutableIntStateOf(-1) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val root = remember { FocusRequester() }
    val playBtn = remember { FocusRequester() }
    val panelFirst = remember { FocusRequester() }

    fun touch() { lastInput = System.currentTimeMillis() }
    fun showToast(t: String) { toast = t; toastAt = System.currentTimeMillis() }
    fun seekBy(d: Long) {
        val s = st ?: return
        val target = (s.positionMs + d).coerceIn(0, if (s.durationMs > 0) s.durationMs - 1000 else Long.MAX_VALUE)
        engine?.seekTo(target)
        showToast(if (d > 0) "+${d / 1000}s" else "−${-d / 1000}s")
        peek = true
        touch()
    }
    fun chapter(dir: Int) {
        val s = st ?: return
        val ch = session.info?.chapters ?: emptyList()
        if (ch.isEmpty()) { seekBy(if (dir > 0) 30_000 else -10_000); return }
        val pos = s.positionMs
        val target = if (dir > 0) ch.firstOrNull { it.startMs > pos + 1500 }?.startMs
        else ch.lastOrNull { it.startMs < pos - 3000 }?.startMs ?: 0L
        if (target != null) { engine?.seekTo(target); showToast(if (dir > 0) "Next chapter" else "Previous chapter") }
        else if (dir > 0) session.next?.let { session.play(it) }
        peek = true
        touch()
    }
    fun togglePause() {
        val s = st ?: return
        engine?.setPaused(!s.paused)
        touch()
    }
    fun showControls() {
        controls = true
        touch()
    }
    fun hideControls() {
        controls = false
        panel = null
        scrubMs = null
    }
    fun exit() {
        if (!app.back()) app.replaceAll(Screen.Home)
    }

    // Auto-hide controls while playing.
    LaunchedEffect(controls, lastInput, panel, st?.paused) {
        if (controls && panel == null && st?.paused == false) {
            delay(5000)
            hideControls()
        }
    }
    LaunchedEffect(peek, lastInput) { if (peek) { delay(1800); peek = false } }
    LaunchedEffect(toastAt) { if (toast != null) { delay(900); toast = null } }
    LaunchedEffect(controls) {
        if (controls) playBtn.focusSoon() else root.focusSoon()
    }
    LaunchedEffect(panel) { if (panel != null) panelFirst.focusSoon() else if (controls) playBtn.focusSoon() }
    LaunchedEffect(controls) { while (controls) { now = System.currentTimeMillis(); delay(10_000) } }
    // Commit a scrub shortly after the last press.
    LaunchedEffect(scrubAt) {
        val target = scrubMs ?: return@LaunchedEffect
        delay(700)
        engine?.seekTo(target)
        scrubMs = null
    }
    // Up next / finish.
    val ended = st?.ended == true
    LaunchedEffect(ended, session.next) {
        if (!ended) { countdown = -1; return@LaunchedEffect }
        val n = session.next
        if (n != null && app.prefs.upNext) {
            controls = false
            countdown = 10
            while (countdown > 0) { delay(1000); countdown-- }
            session.play(n)
            countdown = -1
        } else if (n == null) {
            delay(1500)
            exit()
        }
    }

    fun rootKeys(ev: KeyEvent): Boolean {
        val k = ev.nativeKeyEvent
        val down = ev.type == KeyEventType.KeyDown
        val up = ev.type == KeyEventType.KeyUp
        when (k.keyCode) {
            AK.KEYCODE_DPAD_CENTER, AK.KEYCODE_ENTER, AK.KEYCODE_NUMPAD_ENTER -> {
                if (up) { togglePause(); showControls() }
                return true
            }
            AK.KEYCODE_DPAD_LEFT -> {
                if (down && (k.repeatCount == 0 || k.repeatCount % 3 == 0)) seekBy(-10_000)
                return true
            }
            AK.KEYCODE_DPAD_RIGHT -> {
                if (down && k.repeatCount >= 2 && !boosting && st != null) {
                    boosting = true
                    boostFrom = st.speed
                    engine?.setSpeed(2f)
                    showToast("2×")
                    peek = true
                }
                if (up) {
                    if (boosting) { engine?.setSpeed(boostFrom); boosting = false; showToast("${fmtSpeed(boostFrom)}") }
                    else seekBy(30_000)
                }
                return true
            }
            AK.KEYCODE_DPAD_UP, AK.KEYCODE_DPAD_DOWN -> {
                if (down) showControls()
                return true
            }
        }
        return false
    }

    fun globalKeys(ev: KeyEvent): Boolean {
        val k = ev.nativeKeyEvent
        val up = ev.type == KeyEventType.KeyUp
        touch()
        when (k.keyCode) {
            AK.KEYCODE_BACK, AK.KEYCODE_ESCAPE -> {
                if (countdown > 0) { if (up) exit(); return true }
                if (panel != null) { if (up) panel = null; return true }
                if (controls) { if (up) hideControls(); return true }
                if (up) exit()
                return true
            }
            AK.KEYCODE_MEDIA_PLAY_PAUSE -> { if (up) togglePause(); return true }
            AK.KEYCODE_MEDIA_PLAY -> { if (up) engine?.setPaused(false); return true }
            AK.KEYCODE_MEDIA_PAUSE -> { if (up) engine?.setPaused(true); return true }
            AK.KEYCODE_MEDIA_FAST_FORWARD -> { if (up) seekBy(30_000); return true }
            AK.KEYCODE_MEDIA_REWIND -> { if (up) seekBy(-10_000); return true }
            AK.KEYCODE_MEDIA_NEXT -> { if (up) chapter(1); return true }
            AK.KEYCODE_MEDIA_PREVIOUS -> { if (up) chapter(-1); return true }
            AK.KEYCODE_MEDIA_STOP -> { if (up) exit(); return true }
            AK.KEYCODE_CAPTIONS -> { if (up) { panel = Panel.SUBS; controls = true }; return true }
        }
        if (countdown > 0) {
            val sel = k.keyCode == AK.KEYCODE_DPAD_CENTER || k.keyCode == AK.KEYCODE_ENTER
            if (sel && up) session.next?.let { countdown = -1; session.play(it) }
            return true
        }
        return false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { globalKeys(it) }
            .onKeyEvent { if (!controls) rootKeys(it) else if (it.type == KeyEventType.KeyDown) { touch(); false } else false }
            .focusRequester(root)
            .focusable(),
    ) {
        key(session.generation) {
            val e = engine
            if (e != null) {
                AndroidView(factory = { c -> e.createView(c) }, modifier = Modifier.fillMaxSize())
            }
        }

        // Loading / error.
        val loading = st == null || (st.buffering && st.positionMs == 0L)
        if ((session.loadError ?: st?.error) != null) {
            Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(G.panel).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(session.loadError ?: st?.error ?: "", style = G.body, modifier = Modifier.width(460.dp))
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (engine != null) ActionButton("Try the other player", { session.switchEngine() }, Modifier.focusRequester(playBtn), primary = true)
                    ActionButton("Back", { exit() })
                }
            }
        } else if (loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(42.dp), color = G.red, strokeWidth = 3.dp)
        }

        toast?.let {
            Text(
                it,
                style = G.h2,
                modifier = Modifier.align(Alignment.Center).clip(G.pillShape).background(Color(0x99000000)).padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }

        // Peek bar (shown briefly on seeks while controls are hidden).
        AnimatedVisibility(peek && !controls && st != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            if (st != null) {
                Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000)))).padding(horizontal = 56.dp, vertical = 28.dp)) {
                    Text("${fmt(st.positionMs)}  /  ${fmt(st.durationMs)}", style = G.label)
                    Spacer(Modifier.height(8.dp))
                    SeekLine(st.positionMs, st.durationMs, null, false)
                }
            }
        }

        AnimatedVisibility(controls && st != null, enter = fadeIn(), exit = fadeOut()) {
            if (st != null) Controls(
                session = session,
                st = st,
                now = now,
                scrubMs = scrubMs,
                playBtn = playBtn,
                onScrub = { delta, repeat ->
                    val base = scrubMs ?: st.positionMs
                    val step = when { repeat > 12 -> 120_000L; repeat > 4 -> 30_000L; else -> 10_000L }
                    scrubMs = (base + delta * step).coerceIn(0, maxOf(0, st.durationMs - 1000))
                    scrubAt = System.currentTimeMillis()
                    touch()
                },
                onToggle = { togglePause() },
                onChapter = { chapter(it) },
                onPanel = { panel = it; touch() },
            )
        }

        panel?.let { p ->
            SidePanel(p, session, st, panelFirst) { panel = null }
        }

        if (countdown > 0) {
            session.next?.let { n -> UpNextCard(n, countdown, Modifier.align(Alignment.BottomEnd).padding(48.dp)) }
        }
    }
}

@Composable
private fun Controls(
    session: PlaybackSession,
    st: PlayerState,
    now: Long,
    scrubMs: Long?,
    playBtn: FocusRequester,
    onScrub: (Int, Int) -> Unit,
    onToggle: () -> Unit,
    onChapter: (Int) -> Unit,
    onPanel: (Panel) -> Unit,
) {
    val ctx = LocalContext.current
    val item = session.item
    val engine = session.engine
    Box(Modifier.fillMaxSize()) {
        // Top: title over the video, clock on the right.
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                .padding(start = 56.dp, end = 56.dp, top = 32.dp, bottom = 48.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (item.isEpisode) item.showTitle ?: item.title else item.title, style = G.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.isEpisode) Text(listOfNotNull(item.episodeTag, item.title).joinToString("  ·  "), style = G.label.copy(color = G.dim))
            }
            Column(horizontalAlignment = Alignment.End) {
                val tf = DateFormat.getTimeFormat(ctx)
                val left = ((st.durationMs - st.positionMs) / st.speed.coerceAtLeast(0.1f)).toLong().coerceAtLeast(0)
                Text(tf.format(Date(now)), style = G.h2)
                if (st.durationMs > 0) Text("ends ${tf.format(Date(now + left))}", style = G.small)
                Spacer(Modifier.height(4.dp))
                session.info?.summary?.takeIf { it.isNotBlank() }?.let { Text(it, style = G.small.copy(color = G.faint)) }
            }
        }

        // Bottom: time, slim bar, buttons.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(start = 56.dp, end = 56.dp, top = 60.dp, bottom = 30.dp),
        ) {
            val shown = scrubMs ?: st.positionMs
            Text("${fmt(shown)}  /  ${fmt(st.durationMs)}", style = G.label)
            Spacer(Modifier.height(8.dp))
            SeekBar(shown, st.durationMs, session.info?.chapters?.map { it.startMs } ?: emptyList(), onScrub)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(onClick = { onChapter(-1) }) { c -> ChapterGlyph(c, back = true) }
                RoundButton(onClick = onToggle, modifier = Modifier.focusRequester(playBtn), big = true) { c ->
                    if (st.paused) PlayGlyph(c, 18.dp) else PauseGlyph(c)
                }
                RoundButton(onClick = { onChapter(1) }) { c -> ChapterGlyph(c, back = false) }
                Spacer(Modifier.weight(1f))
                Pill("Audio", { onPanel(Panel.AUDIO) })
                Pill("Subtitles", { onPanel(Panel.SUBS) })
                Pill(if (st.speed == 1f) "Speed" else fmtSpeed(st.speed), { onPanel(Panel.SPEED) })
                Pill(if (engine?.choice == EngineChoice.MPV) "mpv" else "ExoPlayer", { onPanel(Panel.INFO) })
                session.next?.let { n -> Pill("Next  ${n.episodeTag ?: ""}".trim(), { session.play(n) }) }
            }
        }
    }
}

@Composable
private fun SeekBar(pos: Long, dur: Long, chapters: List<Long>, onScrub: (Int, Int) -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .height(18.dp)
            .onKeyEvent { ev ->
                val k = ev.nativeKeyEvent
                when (k.keyCode) {
                    AK.KEYCODE_DPAD_LEFT -> { if (ev.type == KeyEventType.KeyDown) onScrub(-1, k.repeatCount); true }
                    AK.KEYCODE_DPAD_RIGHT -> { if (ev.type == KeyEventType.KeyDown) onScrub(1, k.repeatCount); true }
                    else -> false
                }
            }
            .focusable(interactionSource = src),
        contentAlignment = Alignment.CenterStart,
    ) {
        SeekLine(pos, dur, chapters, focused)
    }
}

@Composable
private fun SeekLine(pos: Long, dur: Long, chapters: List<Long>?, focused: Boolean) {
    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(if (focused) 5.dp else 3.dp).clip(G.pillShape).background(Color(0x4DFFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(G.red))
        }
        if (dur > 0 && !chapters.isNullOrEmpty()) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
                val w = maxWidth
                chapters.filter { it > 0 }.forEach { c ->
                    Box(Modifier.offset(x = w * (c.toFloat() / dur)).align(Alignment.CenterStart).size(2.dp, 7.dp).background(Color(0x99000000)))
                }
            }
        }
        if (focused) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
                Box(
                    Modifier.offset(x = maxWidth * frac - 7.dp).align(Alignment.CenterStart).size(14.dp)
                        .clip(CircleShape).background(Color.White).border(2.dp, G.red, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun RoundButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    content: @Composable (Color) -> Unit,
) {
    FocusBox(
        onClick = onClick,
        modifier = modifier.size(if (big) 54.dp else 42.dp),
        shape = CircleShape,
        focusScale = 1.1f,
        idleColor = Color(0x26FFFFFF),
        focusColor = Color(0x40FFFFFF),
        glow = true,
    ) { f ->
        Box(Modifier.fillMaxSize().border(if (big) 1.5.dp else 0.dp, if (big) G.red else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
            content(if (big) G.red else if (f) Color.White else G.dim)
        }
    }
}

@Composable
private fun PauseGlyph(c: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(4.dp, 16.dp).clip(G.pillShape).background(c))
        Box(Modifier.size(4.dp, 16.dp).clip(G.pillShape).background(c))
    }
}

@Composable
private fun ChapterGlyph(c: Color, back: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (back) Box(Modifier.size(2.dp, 12.dp).background(c))
        Text(if (back) "◀" else "▶", style = G.small.copy(color = c))
        if (!back) Box(Modifier.size(2.dp, 12.dp).background(c))
    }
}

@Composable
private fun SidePanel(p: Panel, session: PlaybackSession, st: PlayerState?, first: FocusRequester, close: () -> Unit) {
    val engine = session.engine
    data class PRow(val label: String, val detail: String?, val checked: Boolean, val act: () -> Unit)

    val rows: List<PRow> = when (p) {
        Panel.AUDIO -> (st?.audio ?: emptyList()).map { t: TrackInfo -> PRow(t.label, null, t.selected) { engine?.selectAudio(t.id) } }
        Panel.SUBS -> listOf(PRow("Off", null, st?.subs?.none { it.selected } == true) { engine?.selectSub(null) }) +
            (st?.subs ?: emptyList()).map { t -> PRow(t.label, null, t.selected) { engine?.selectSub(t.id) } }
        Panel.SPEED -> speeds.map { s -> PRow(fmtSpeed(s), null, st?.speed == s) { engine?.setSpeed(s) } }
        Panel.INFO -> {
            val other = if (engine?.choice == EngineChoice.MPV) "ExoPlayer" else "mpv"
            listOf(
                PRow(
                    "Playing with ${if (engine?.choice == EngineChoice.MPV) "mpv" else "ExoPlayer"}",
                    "Why: ${session.reason}" + (st?.videoFps?.takeIf { it > 0 }?.let { "  ·  %.3f fps".format(it) } ?: ""),
                    true,
                ) {},
                PRow("Switch to $other", "Restarts at the same spot", false) { session.switchEngine() },
            )
        }
    }
    val title = when (p) {
        Panel.AUDIO -> "Audio"; Panel.SUBS -> "Subtitles"; Panel.SPEED -> "Speed"; Panel.INFO -> "Player"
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.fillMaxHeight().width(400.dp).background(G.panel).padding(horizontal = 18.dp, vertical = 36.dp),
        ) {
            Text(title, style = G.h2, modifier = Modifier.padding(start = 10.dp, bottom = 12.dp))
            if (rows.isEmpty()) Text("Nothing to choose here.", style = G.small, modifier = Modifier.padding(10.dp))
            val firstIndex = rows.indexOfFirst { it.checked }.coerceAtLeast(0)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(rows) { i, r ->
                    FocusBox(
                        onClick = { r.act(); if (p != Panel.INFO || i == 1) close() },
                        modifier = Modifier.fillMaxWidth().let { if (i == firstIndex) it.focusRequester(first) else it },
                        shape = RoundedCornerShape(10.dp),
                        focusScale = 1.02f,
                        idleColor = Color.Transparent,
                        focusColor = Color(0x2EFFFFFF),
                        glow = false,
                    ) { f ->
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text((if (r.checked) "●  " else "     ") + r.label, style = G.label.copy(color = if (f || r.checked) G.text else G.dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            r.detail?.let { Text(it, style = G.small.copy(color = G.faint)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpNextCard(next: Item, seconds: Int, modifier: Modifier) {
    val app = LocalApp.current
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(G.panel).border(1.dp, G.rim, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetImage(app.plex.image(next.thumb, 320, 180), Modifier.size(160.dp, 90.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.width(260.dp)) {
            Text("Up next in $seconds", style = G.small.copy(color = G.red))
            Text(listOfNotNull(next.episodeTag, next.title).joinToString("  ·  "), style = G.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text("OK to play now  ·  Back to stop", style = G.small.copy(color = G.faint))
        }
    }
}

fun fmt(ms: Long): String {
    val t = (ms.coerceAtLeast(0) / 1000).toInt()
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun fmtSpeed(s: Float): String = (if (s % 1f == 0f) "%.0f".format(s) else "%s".format(s.toString().trimEnd('0'))) + "×"
