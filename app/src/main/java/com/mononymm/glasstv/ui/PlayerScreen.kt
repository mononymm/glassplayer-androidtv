package com.mononymm.glasstv.ui

import android.text.format.DateFormat
import android.view.KeyEvent as AK
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.TextStyle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import com.mononymm.glasstv.plex.Chapter
import com.mononymm.glasstv.plex.SubResult
import kotlinx.coroutines.launch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mononymm.glasstv.plex.EngineChoice
import com.mononymm.glasstv.plex.Item
import com.mononymm.glasstv.player.PlaybackSession
import com.mononymm.glasstv.player.PlayerState
import com.mononymm.glasstv.player.TrackInfo
import kotlinx.coroutines.delay
import java.util.Date

private enum class Panel { AUDIO, SUBS, INFO }


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
    // 0 = buttons, 1 = chapters row, 2 = playlist row.
    var level by remember { mutableIntStateOf(0) }
    var speedOpen by remember { mutableStateOf(false) }
    var subSearch by remember { mutableStateOf(false) }
    var rowFocused by remember { mutableStateOf(false) }
    var seekFocused by remember { mutableStateOf(false) }
    val hasPlaylist = session.playlist.size > 1
    val hasChapters = (session.info?.chapters?.size ?: 0) > 1

    val root = remember { FocusRequester() }
    val playBtn = remember { FocusRequester() }
    val panelFirst = remember { FocusRequester() }
    val tileFirst = remember { FocusRequester() }

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
        level = 0
    }
    fun levelUp() {
        level = if (level == 2 && hasChapters) 1 else 0
    }
    fun exit() {
        if (!app.back()) app.replaceAll(Screen.Home)
    }

    // Auto-hide controls while playing.
    LaunchedEffect(controls, lastInput, panel, st?.paused, level) {
        if (controls && panel == null && st?.paused == false) {
            delay(if (level == 0) 5000 else 15000)
            hideControls()
        }
    }
    LaunchedEffect(peek, lastInput) { if (peek) { delay(1800); peek = false } }
    LaunchedEffect(toastAt) { if (toast != null) { delay(900); toast = null } }
    // Focus the play button once it exists. At start-up the controls show before the file has
    // loaded (no play button yet), so until then the screen itself holds focus and handles OK.
    val ready = st != null
    LaunchedEffect(controls, ready) {
        if (controls && ready && panel == null) (if (level > 0) tileFirst else playBtn).focusSoon()
        else root.focusSoon()
    }
    LaunchedEffect(panel) {
        speedOpen = false
        subSearch = false
        if (panel != null) panelFirst.focusSoon()
        else if (controls) (if (level > 0) tileFirst else playBtn).focusSoon()
    }
    LaunchedEffect(level) {
        if (controls && panel == null) (if (level > 0) tileFirst else playBtn).focusSoon()
    }
    LaunchedEffect(controls) { while (controls) { now = System.currentTimeMillis(); delay(10_000) } }

    // A media session, so Bluetooth headphone buttons (and other media controllers) reach the
    // player: Android sends those to the active session, not to the app on screen.
    val mediaCtx = LocalContext.current
    val mediaSession = remember {
        android.media.session.MediaSession(mediaCtx, "Glass").apply {
            setCallback(object : android.media.session.MediaSession.Callback() {
                override fun onPlay() { session.engine?.setPaused(false) }
                override fun onPause() { session.engine?.setPaused(true) }
                override fun onFastForward() { session.engine?.let { it.seekTo(it.state.positionMs + 30_000) } }
                override fun onRewind() { session.engine?.let { it.seekTo((it.state.positionMs - 10_000).coerceAtLeast(0)) } }
                override fun onSeekTo(pos: Long) { session.engine?.seekTo(pos) }
            })
            isActive = true
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            mediaSession.isActive = false
            mediaSession.release()
        }
    }
    LaunchedEffect(st?.paused, st?.durationMs) {
        val s = st ?: return@LaunchedEffect
        mediaSession.setPlaybackState(
            android.media.session.PlaybackState.Builder()
                .setActions(
                    android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or
                        android.media.session.PlaybackState.ACTION_PLAY_PAUSE or android.media.session.PlaybackState.ACTION_FAST_FORWARD or
                        android.media.session.PlaybackState.ACTION_REWIND or android.media.session.PlaybackState.ACTION_SEEK_TO,
                )
                .setState(
                    if (s.paused) android.media.session.PlaybackState.STATE_PAUSED else android.media.session.PlaybackState.STATE_PLAYING,
                    s.positionMs, if (s.paused) 0f else s.speed,
                )
                .build(),
        )
        mediaSession.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, session.item.title)
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, s.durationMs)
                .build(),
        )
    }
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
                if (panel != null) {
                    if (up) {
                        when {
                            speedOpen -> speedOpen = false
                            subSearch -> subSearch = false
                            else -> panel = null
                        }
                    }
                    return true
                }
                if (controls) { if (up) { if (level > 0) levelUp() else hideControls() }; return true }
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
        if (controls && panel == null && countdown <= 0) {
            val down = ev.type == KeyEventType.KeyDown
            when (k.keyCode) {
                AK.KEYCODE_DPAD_DOWN -> {
                    if (level == 0 && !rowFocused) return false
                    if (down && k.repeatCount == 0) {
                        level = when (level) {
                            0 -> if (hasChapters) 1 else if (hasPlaylist) 2 else 0
                            1 -> if (hasPlaylist) 2 else 1
                            else -> 2
                        }
                    }
                    return true
                }
                AK.KEYCODE_DPAD_UP -> {
                    if (level == 0) {
                        // Up from the seek bar hides the controls.
                        if (!seekFocused) return false
                        if (down && k.repeatCount == 0) hideControls()
                        return true
                    }
                    if (down && k.repeatCount == 0) levelUp()
                    return true
                }
            }
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
            .onKeyEvent { if (!controls || st == null) rootKeys(it) else if (it.type == KeyEventType.KeyDown) { touch(); false } else false }
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

        AnimatedVisibility(controls && st != null && panel == null, enter = fadeIn(), exit = fadeOut()) {
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
                level = level,
                hasPlaylist = hasPlaylist,
                hasChapters = hasChapters,
                tileFirst = tileFirst,
                onRowFocus = { rowFocused = it },
                onSeekFocus = { seekFocused = it },
                onNextEpisode = { session.next?.let { session.play(it); hideControls() } },
                onPickEpisode = { ep ->
                    if (ep.ratingKey == session.item.ratingKey) level = 0
                    else { session.play(ep); hideControls() }
                },
                onPickChapter = { c -> engine?.seekTo(c.startMs); level = 0 },
            )
        }

        panel?.let { p ->
            SidePanel(p, session, st, panelFirst, speedOpen, { speedOpen = it }, subSearch, { subSearch = it }) { panel = null }
        }

        if (countdown > 0) {
            session.next?.let { n -> UpNextCard(n, countdown, Modifier.align(Alignment.BottomEnd).padding(48.dp)) }
        }
    }
}

private val PANEL_H = 300.dp
private val LIFT = 284.dp // PANEL_H - bottom padding, so the dimmed controls sit just above the row
private val SIDE_BTN = 40.dp

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
    level: Int,
    hasPlaylist: Boolean,
    hasChapters: Boolean,
    tileFirst: FocusRequester,
    onRowFocus: (Boolean) -> Unit,
    onSeekFocus: (Boolean) -> Unit,
    onNextEpisode: () -> Unit,
    onPickEpisode: (Item) -> Unit,
    onPickChapter: (Chapter) -> Unit,
) {
    val ctx = LocalContext.current
    val item = session.item
    val lowered = level > 0
    val live = !lowered
    val lift by animateDpAsState(if (lowered) LIFT else 0.dp, tween(260), label = "lift")
    val fade by animateFloatAsState(if (lowered) 0.35f else 1f, tween(260), label = "fade")
    // Keep the last lower level so the panel doesn't go blank while it slides away.
    var panelLevel by remember { mutableIntStateOf(1) }
    if (level > 0) panelLevel = level

    Box(Modifier.fillMaxSize()) {
        // Soft scrims so white text reads over bright video.
        Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Color(0x59000000), Color.Transparent))))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(280.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))),
        )

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .offset(y = -lift)
                .graphicsLayer { alpha = fade }
                .padding(start = 48.dp, end = 48.dp, bottom = 16.dp),
        ) {
            // Title on the left, clock and file line on the right.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (item.isEpisode) item.showTitle ?: item.title else item.title,
                        style = G.h2.copy(fontSize = 20.sp, shadow = textShadow), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (item.isEpisode) {
                        Text(
                            listOfNotNull(item.episodeTag, item.title).joinToString("  ·  "),
                            style = G.label.copy(color = Color(0xD9FFFFFF), shadow = textShadow), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    val tf = DateFormat.getTimeFormat(ctx)
                    val left = ((st.durationMs - st.positionMs) / st.speed.coerceAtLeast(0.1f)).toLong().coerceAtLeast(0)
                    val clock = tf.format(Date(now)) + if (st.durationMs > 0) "  ·  ends ${tf.format(Date(now + left))}" else ""
                    Text(clock, style = G.small.copy(color = Color(0xE6FFFFFF), fontWeight = FontWeight.SemiBold, shadow = textShadow))
                    session.info?.summary?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = G.small.copy(color = Color(0xCCFFFFFF), fontSize = 11.sp, shadow = textShadow), maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // Seek pill: elapsed on the left, total on the right, same inset both sides.
            var seekF by remember { mutableStateOf(false) }
            // Darker, more solid pill while the seek bar is selected, so it reads over bright video.
            val pillBg by animateColorAsState(if (seekF) Color(0xC80E0E12) else Color(0x24FFFFFF), tween(160), label = "pill")
            val pillRim by animateColorAsState(if (seekF) Color(0x6EFFFFFF) else Color(0x2EFFFFFF), tween(160), label = "rim")
            Row(
                Modifier.fillMaxWidth().height(52.dp).clip(G.pillShape).background(pillBg)
                    .border(if (seekF) 1.5.dp else 1.dp, pillRim, G.pillShape).padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shown = scrubMs ?: st.positionMs
                Text(fmt(shown), style = G.label.copy(fontSize = 13.sp), textAlign = TextAlign.Start, modifier = Modifier.width(56.dp))
                SeekBar(
                    shown, st.durationMs, session.info?.chapters?.map { it.startMs } ?: emptyList(), onScrub,
                    Modifier.weight(1f).onFocusChanged { seekF = it.isFocused; onSeekFocus(it.isFocused) }, enabled = live,
                )
                Text(fmt(st.durationMs), style = G.label.copy(fontSize = 13.sp, color = Color(0x99FFFFFF)), textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
            }
            Spacer(Modifier.height(14.dp))
            // Buttons: two on each side at matching insets, play group dead centre.
            Box(Modifier.fillMaxWidth().height(54.dp).onFocusChanged { onRowFocus(it.hasFocus) }) {
                Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassIconButton({ onPanel(Panel.AUDIO) }, size = SIDE_BTN, enabled = live) { c -> NoteIcon(c) }
                    val subsOn = st.subs.any { it.selected }
                    GlassIconButton({ onPanel(Panel.SUBS) }, size = SIDE_BTN, tint = if (subsOn) G.red else null, enabled = live) { c -> CaptionsIcon(c, filled = subsOn) }
                }
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassIconButton({ onChapter(-1) }, size = 42.dp, dim = !hasChapters, enabled = live) { c -> ChapterIcon(c, back = true) }
                    BigPlayButton(st.paused, onToggle, Modifier.focusRequester(playBtn), enabled = live)
                    GlassIconButton({ onChapter(1) }, size = 42.dp, dim = !hasChapters, enabled = live) { c -> ChapterIcon(c, back = false) }
                }
                Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassIconButton({ onPanel(Panel.INFO) }, size = SIDE_BTN, enabled = live) { c -> TuneIcon(c) }
                    val hasNext = session.next != null
                    // Same look as subtitles when on: plain glass bubble, red icon.
                    GlassIconButton(onNextEpisode, size = SIDE_BTN, tint = if (hasNext) G.red else null, dim = !hasNext, enabled = live && hasNext) { c -> NextEpisodeIcon(c) }
                }
            }
        }

        AnimatedVisibility(
            lowered,
            enter = slideInVertically(tween(260)) { it } + fadeIn(tween(260)),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            LowerPanel(panelLevel, session, st, hasPlaylist, hasChapters, tileFirst, onPickEpisode, onPickChapter)
        }
    }
}

/** The row under the controls: the chapters (level 1) or the season's episodes (level 2). */
@Composable
private fun LowerPanel(
    level: Int,
    session: PlaybackSession,
    st: PlayerState,
    hasPlaylist: Boolean,
    hasChapters: Boolean,
    first: FocusRequester,
    onPickEpisode: (Item) -> Unit,
    onPickChapter: (Chapter) -> Unit,
) {
    val app = LocalApp.current
    val inset = 56.dp
    // The neighbouring level's name: same place on the left, a little smaller and faded.
    val faded = G.h2.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = G.faint)
    Column(
        Modifier.fillMaxWidth().height(PANEL_H)
            .background(
                if (Skin.amoled) Brush.verticalGradient(0f to Color.Transparent, 0.16f to Color(0xF0000000), 1f to Color.Black)
                else Brush.verticalGradient(0f to Color(0x00101015), 0.16f to Color(0xE6101015), 1f to Color(0xF2101015)),
            )
            .padding(top = 22.dp),
    ) {
        if (level == 2) {
            Text(if (hasChapters) "Chapters" else " ", style = faded, modifier = Modifier.padding(start = inset))
            Spacer(Modifier.height(2.dp))
            Row(Modifier.padding(start = inset), verticalAlignment = Alignment.Bottom) {
                Text("Playlist", style = G.h2)
                session.item.seasonIndex?.let {
                    Text("   Season $it", style = G.small.copy(color = G.faint), modifier = Modifier.padding(bottom = 2.dp))
                }
            }
            val eps = session.playlist
            val curKey = session.item.ratingKey
            val cur = eps.indexOfFirst { it.ratingKey == curKey }.coerceAtLeast(0)
            TileRow(eps.size, cur, first) { i, mod, onFocus ->
                val ep = eps[i]
                val isCur = ep.ratingKey == curKey
                val left = (st.durationMs - st.positionMs).coerceAtLeast(0)
                PlayerTile(
                    image = app.plex.image(ep.thumb, 496, 280),
                    plainText = null,
                    tag = ep.episodeTag ?: ep.index?.let { "E$it" },
                    title = ep.title,
                    sub = when {
                        isCur -> if (st.durationMs > 0) minutes(left) + " left" else minutes(ep.duration)
                        else -> (if (!ep.watched) remaining(ep) else null) ?: minutes(ep.duration)
                    },
                    current = isCur,
                    currentLabel = "Now playing",
                    watched = ep.watched && !isCur,
                    progress = when {
                        isCur -> if (st.durationMs > 0) st.positionMs.toFloat() / st.durationMs else null
                        ep.progress > 0f && !ep.watched -> ep.progress
                        else -> null
                    },
                    onClick = { onPickEpisode(ep) },
                    onFocus = onFocus,
                    modifier = mod,
                )
            }
        } else {
            Text("Chapters", style = G.h2, modifier = Modifier.padding(start = inset))
            val chapters = session.info?.chapters ?: emptyList()
            val pos = st.positionMs
            val cur = chapters.indexOfLast { it.startMs <= pos }.coerceAtLeast(0)
            TileRow(chapters.size, cur, first) { i, mod, onFocus ->
                val c = chapters[i]
                val end = chapters.getOrNull(i + 1)?.startMs ?: st.durationMs
                val len = (end - c.startMs).coerceAtLeast(0)
                val isCur = i == cur
                PlayerTile(
                    image = c.thumb?.let { app.plex.image(it, 496, 280) },
                    plainText = fmt(c.startMs),
                    tag = if (c.thumb != null) fmt(c.startMs) else null,
                    title = c.title?.takeIf { it.isNotBlank() } ?: "Chapter ${i + 1}",
                    sub = if (isCur) "${shortDur(pos - c.startMs)} in" else shortDur(len),
                    current = isCur,
                    currentLabel = "Now",
                    watched = false,
                    progress = if (isCur && len > 0) ((pos - c.startMs).toFloat() / len) else null,
                    onClick = { onPickChapter(c) },
                    onFocus = onFocus,
                    modifier = mod,
                )
            }
            if (hasPlaylist) Text("Playlist", style = faded, modifier = Modifier.padding(start = inset))
        }
    }
}

/** Horizontal row that keeps the focused tile in the middle of the screen. */
@Composable
private fun TileRow(
    count: Int,
    current: Int,
    first: FocusRequester,
    tile: @Composable (index: Int, modifier: Modifier, onFocus: () -> Unit) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = ((maxWidth - WIDE_W.dp) / 2).coerceAtLeast(0.dp)
        val state = rememberLazyListState(initialFirstVisibleItemIndex = current.coerceIn(0, maxOf(0, count - 1)))
        val scope = rememberCoroutineScope()
        LazyRow(
            state = state,
            contentPadding = PaddingValues(start = side, end = side, top = 14.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(count) { i ->
                tile(i, if (i == current) Modifier.focusRequester(first) else Modifier) {
                    scope.launch { state.animateScrollToItem(i) }
                }
            }
        }
    }
}

/** Episode or chapter tile, styled like the library's wide cards. */
@Composable
private fun PlayerTile(
    image: String?,
    plainText: String?,
    tag: String?,
    title: String,
    sub: String,
    current: Boolean,
    currentLabel: String,
    watched: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.width(WIDE_W.dp)) {
        FocusBox(
            onClick = onClick,
            modifier = Modifier.size(WIDE_W.dp, WIDE_H.dp),
            onFocus = onFocus,
            idleColor = if (image == null) Color(0x1FFFFFFF) else G.glass,
        ) { f ->
            if (image != null) {
                NetImage(image, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xCC000000))))
            } else if (plainText != null) {
                Text(plainText, style = G.title.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.align(Alignment.Center))
            }
            if (current && !f) Box(Modifier.matchParentSize().border(2.dp, G.red, G.cardShape))
            if (f && !current) {
                Box(Modifier.align(Alignment.Center).size(46.dp).clip(CircleShape).background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                    PlayGlyph(G.red, 20.dp)
                }
            }
            if (watched) WatchedBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (current) {
                Box(Modifier.align(Alignment.TopStart).padding(6.dp).clip(G.pillShape).background(G.red).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(currentLabel, style = G.small.copy(color = Color.White, fontWeight = FontWeight.Medium))
                }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth()) {
                tag?.let { Text(it, style = G.small.copy(color = G.dim)) }
                if (progress != null) {
                    Spacer(Modifier.height(5.dp))
                    ProgressLine(progress, Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = G.small.copy(color = G.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, style = G.small.copy(color = G.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "45 sec", "7 min", "1 h 4 min". */
private fun shortDur(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000).toInt()
    return if (s < 60) "$s sec" else minutes(ms)
}

private val textShadow = androidx.compose.ui.graphics.Shadow(Color(0xD9000000), androidx.compose.ui.geometry.Offset(0f, 1.5f), 6f)

@Composable
private fun SeekBar(pos: Long, dur: Long, chapters: List<Long>, onScrub: (Int, Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Box(
        modifier
            .height(30.dp)
            .onKeyEvent { ev ->
                val k = ev.nativeKeyEvent
                when (k.keyCode) {
                    AK.KEYCODE_DPAD_LEFT -> { if (ev.type == KeyEventType.KeyDown) onScrub(-1, k.repeatCount); true }
                    AK.KEYCODE_DPAD_RIGHT -> { if (ev.type == KeyEventType.KeyDown) onScrub(1, k.repeatCount); true }
                    else -> false
                }
            }
            .focusable(enabled = enabled, interactionSource = src),
        contentAlignment = Alignment.CenterStart,
    ) {
        SeekLine(pos, dur, chapters, focused, watchedMark = true)
    }
}

/** The big play/pause button: red outline glyph in a glass circle with a red rim. */
@Composable
private fun BigPlayButton(paused: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    FocusBox(
        onClick = onClick,
        modifier = modifier.size(52.dp),
        focusEnabled = enabled,
        shape = CircleShape,
        focusScale = 1.1f,
        idleColor = Skin.button,
        focusColor = Color(0x33FFFFFF),
        glow = true,
    ) { _ ->
        Box(Modifier.fillMaxSize().border(2.dp, G.red, CircleShape), contentAlignment = Alignment.Center) {
            // PlayGlyph's path already sits right of its box centre (about 1.5dp at this size), which
            // is the usual optical correction for a triangle; an extra offset pushed it visibly off.
            if (paused) PlayGlyph(G.red, 21.dp) else PauseIcon(G.red)
        }
    }
}

@Composable
private fun GlassIconButton(
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp,
    dim: Boolean = false,
    plain: Boolean = false,
    tint: Color? = null,
    red: Boolean = false,
    enabled: Boolean = true,
    icon: @Composable (Color) -> Unit,
) {
    FocusBox(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        focusScale = 1.12f,
        idleColor = when {
            plain -> Color.Transparent
            red -> Color(0x38E8333F)
            else -> Skin.button
        },
        focusColor = if (red) Color(0x66E8333F) else Color(0x40FFFFFF),
        glow = !plain,
        focusEnabled = enabled,
    ) { f ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            icon(
                when {
                    tint != null -> tint
                    red -> G.red
                    dim && !f -> Color(0x59FFFFFF)
                    else -> Color.White
                },
            )
        }
    }
}

@Composable
private fun PauseIcon(c: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(5.dp, 17.dp).clip(G.pillShape).border(2.dp, c, G.pillShape))
        Box(Modifier.size(5.dp, 17.dp).clip(G.pillShape).border(2.dp, c, G.pillShape))
    }
}

/** Like SF Symbols backward.end.fill / forward.end.fill. */
@Composable
private fun ChapterIcon(c: Color, back: Boolean) {
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        fun x(v: Float) = if (back) v * w else w - v * w
        drawRoundRect(c, topLeft = androidx.compose.ui.geometry.Offset(if (back) w * 0.12f else w * 0.76f, h * 0.18f),
            size = androidx.compose.ui.geometry.Size(w * 0.12f, h * 0.64f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f))
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(x(0.88f), h * 0.18f); lineTo(x(0.88f), h * 0.82f); lineTo(x(0.30f), h * 0.5f); close()
        }
        drawPath(p, c)
    }
}

/** Like SF Symbols music.note. */
@Composable
private fun NoteIcon(c: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        drawOval(c, topLeft = androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.62f), size = androidx.compose.ui.geometry.Size(w * 0.36f, h * 0.28f))
        drawRect(c, topLeft = androidx.compose.ui.geometry.Offset(w * 0.46f, h * 0.12f), size = androidx.compose.ui.geometry.Size(w * 0.09f, h * 0.66f))
        val flag = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.50f, h * 0.10f); lineTo(w * 0.84f, h * 0.22f); lineTo(w * 0.84f, h * 0.38f); lineTo(w * 0.50f, h * 0.27f); close()
        }
        drawPath(flag, c)
    }
}

/** Like SF Symbols captions.bubble (filled when subtitles are on). */
@Composable
private fun CaptionsIcon(c: Color, filled: Boolean) {
    androidx.compose.foundation.Canvas(Modifier.size(21.dp)) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        val bubble = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(w * 0.06f, h * 0.14f, w * 0.94f, h * 0.72f, androidx.compose.ui.geometry.CornerRadius(w * 0.16f)))
            moveTo(w * 0.26f, h * 0.70f); lineTo(w * 0.24f, h * 0.90f); lineTo(w * 0.46f, h * 0.70f); close()
        }
        if (filled) drawPath(bubble, c) else drawPath(bubble, c, style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        val line = if (filled) Color.Black.copy(alpha = 0.75f) else c
        drawLine(line, androidx.compose.ui.geometry.Offset(w * 0.24f, h * 0.36f), androidx.compose.ui.geometry.Offset(w * 0.62f, h * 0.36f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(line, androidx.compose.ui.geometry.Offset(w * 0.24f, h * 0.52f), androidx.compose.ui.geometry.Offset(w * 0.76f, h * 0.52f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** Three sliders: playback settings (file info, speed, player). */
@Composable
private fun TuneIcon(c: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        val rows = listOf(0.24f to 0.66f, 0.50f to 0.34f, 0.76f to 0.58f)
        rows.forEach { (y, knob) ->
            drawLine(c, androidx.compose.ui.geometry.Offset(w * 0.08f, h * y), androidx.compose.ui.geometry.Offset(w * 0.92f, h * y), sw, androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(c, radius = w * 0.12f, center = androidx.compose.ui.geometry.Offset(w * knob, h * y))
        }
    }
}

/** Two triangles and a bar: next episode (the chapter buttons use one triangle). */
@Composable
private fun NextEpisodeIcon(c: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        fun tri(x0: Float, x1: Float) = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * x0, h * 0.20f); lineTo(w * x1, h * 0.5f); lineTo(w * x0, h * 0.80f); close()
        }
        drawPath(tri(0.06f, 0.44f), c)
        drawPath(tri(0.40f, 0.78f), c)
        drawRoundRect(c, topLeft = androidx.compose.ui.geometry.Offset(w * 0.80f, h * 0.20f),
            size = androidx.compose.ui.geometry.Size(w * 0.12f, h * 0.60f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f))
    }
}

@Composable
private fun SeekLine(pos: Long, dur: Long, chapters: List<Long>?, focused: Boolean, watchedMark: Boolean = false) {
    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().height(if (watchedMark) 26.dp else 14.dp), contentAlignment = Alignment.CenterStart) {
        if (watchedMark && dur > 0) {
            // Small arrow over the bar where Plex counts the file as watched (90%, as in iOS and glass.lua).
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                val x = size.width * 0.9f
                val mid = size.height / 2
                val half = 5.dp.toPx()
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x - half, mid - 12.dp.toPx()); lineTo(x + half, mid - 12.dp.toPx()); lineTo(x, mid - 5.dp.toPx()); close()
                }
                drawPath(p, Color(0xEBFFFFFF))
            }
        }
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

private class TrackRow(val label: String, val selected: Boolean, val pick: () -> Unit)

private val MenuShape = RoundedCornerShape(22.dp)
private val RowShape = RoundedCornerShape(12.dp)
private val MenuLabel = TextStyle(color = G.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)

/** Centred glass card for Audio, Subtitles and Playback. The player controls fade out behind it. */
@Composable
private fun SidePanel(
    p: Panel,
    session: PlaybackSession,
    st: PlayerState?,
    first: FocusRequester,
    speedOpen: Boolean,
    setSpeedOpen: (Boolean) -> Unit,
    subSearch: Boolean,
    setSubSearch: (Boolean) -> Unit,
    close: () -> Unit,
) {
    val engine = session.engine
    Box(Modifier.fillMaxSize().background(Color(0x78000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(440.dp).clip(MenuShape).background(Skin.card).border(1.dp, G.rim, MenuShape)
                .padding(horizontal = 10.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val title = when (p) {
                Panel.AUDIO -> "Audio"; Panel.SUBS -> if (subSearch) "Search for subtitles" else "Subtitles"; Panel.INFO -> "Playback"
            }
            Text(title, style = G.h2, modifier = Modifier.padding(bottom = 16.dp))
            when (p) {
                Panel.AUDIO -> {
                    val rows = (st?.audio ?: emptyList()).map { t -> TrackRow(t.label, t.selected) { engine?.selectAudio(t.id) } }
                    TrackList(rows, rows.indexOfFirst { it.selected }.coerceAtLeast(0), first, close)
                }
                Panel.SUBS -> SubtitleMenu(session, st, first, subSearch, setSubSearch, close)
                Panel.INFO -> PlaybackMenu(session, st, first, speedOpen, setSpeedOpen)
            }
        }
    }
}

/** One selectable line in a menu card: label on the left, value on the right. */
@Composable
private fun MenuRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.(focused: Boolean) -> Unit,
) {
    FocusBox(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RowShape,
        focusScale = 1.02f,
        idleColor = Color.Transparent,
        focusColor = Color(0x2EFFFFFF),
        idleRim = Color.Transparent,
        glow = false,
    ) { f ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content(f) }
    }
}

/** Subtitles card: Search sits above the tracks; the card opens on the top track. */
@Composable
private fun SubtitleMenu(session: PlaybackSession, st: PlayerState?, first: FocusRequester, searching: Boolean, setSearching: (Boolean) -> Unit, close: () -> Unit) {
    val engine = session.engine
    val searchRow = remember { FocusRequester() }
    var wasSearching by remember { mutableStateOf(false) }
    LaunchedEffect(searching) {
        if (searching) wasSearching = true else if (wasSearching) searchRow.focusSoon()
    }
    if (searching) {
        SubtitleSearch(session, close)
        return
    }
    MenuRow(onClick = { setSearching(true) }, modifier = Modifier.focusRequester(searchRow)) { f ->
        SearchGlyph(if (f) G.text else G.dim)
        Spacer(Modifier.width(12.dp))
        Text("Search for subtitles", style = MenuLabel.copy(color = if (f) G.text else G.dim), modifier = Modifier.weight(1f))
        Text("OpenSubtitles", style = G.small.copy(color = G.faint))
        Spacer(Modifier.width(8.dp))
        Chevron(G.faint)
    }
    MenuDivider()
    val rows = (st?.subs ?: emptyList()).map { t -> TrackRow(t.label, t.selected) { engine?.selectSub(t.id) } } +
        TrackRow("Off", st?.subs?.none { it.selected } == true) { engine?.selectSub(null) }
    TrackList(rows, firstIndex = 0, first = first, close = close)
}

@Composable
private fun TrackList(rows: List<TrackRow>, firstIndex: Int, first: FocusRequester, close: () -> Unit) {
    if (rows.isEmpty()) Text("Nothing to choose here.", style = G.small, modifier = Modifier.padding(12.dp))
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(rows) { i, r ->
            MenuRow(
                onClick = { r.pick(); close() },
                modifier = if (i == firstIndex) Modifier.focusRequester(first) else Modifier,
            ) { _ ->
                Text(
                    r.label, style = MenuLabel.copy(color = if (r.selected) G.text else G.dim),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (r.selected) {
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(G.red))
                }
            }
        }
    }
}

@Composable
private fun MenuDivider() {
    Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(Color(0x1EFFFFFF)))
}

private val SUB_LANGS = listOf("English" to "en", "Japanese" to "ja", "Spanish" to "es")

/** Plex's on-demand search. Opens on the best match; Up reaches the language switch. */
@Composable
private fun SubtitleSearch(session: PlaybackSession, close: () -> Unit) {
    var lang by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<SubResult>?>(null) }
    var status by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf<String?>(null) }
    val langRow = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { langRow.focusSoon() }
    LaunchedEffect(lang) {
        results = null
        status = "Searching OpenSubtitles…"
        val r = runCatching { session.searchSubtitles(SUB_LANGS[lang].second) }
        results = r.getOrNull() ?: emptyList()
        status = when {
            r.isFailure -> "Search didn't work. Try again in a bit."
            results.isNullOrEmpty() -> "No ${SUB_LANGS[lang].first} subtitles found."
            else -> ""
        }
        if (!focusedOnce && !results.isNullOrEmpty()) { focusedOnce = true; firstResult.focusSoon() }
    }

    MenuRow(
        onClick = {},
        modifier = Modifier.focusRequester(langRow).onPreviewKeyEvent { ev ->
            when (ev.nativeKeyEvent.keyCode) {
                AK.KEYCODE_DPAD_LEFT -> { if (ev.type == KeyEventType.KeyUp && lang > 0) lang--; true }
                AK.KEYCODE_DPAD_RIGHT -> { if (ev.type == KeyEventType.KeyUp && lang < SUB_LANGS.lastIndex) lang++; true }
                else -> false
            }
        },
    ) { f ->
        Text("Language", style = MenuLabel, modifier = Modifier.weight(1f))
        Segmented(SUB_LANGS.map { it.first }, lang, f)
    }
    MenuDivider()
    val list = results
    if (list.isNullOrEmpty()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (list == null) {
                CircularProgressIndicator(Modifier.size(16.dp), color = G.red, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(status, style = G.small.copy(color = G.dim))
        }
        return
    }
    if (status.isNotEmpty()) Text(status, style = G.small.copy(color = G.red), modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(list) { i, r ->
            MenuRow(
                onClick = {
                    if (downloading == null) {
                        downloading = r.key
                        status = ""
                        scope.launch {
                            val ok = runCatching { session.downloadSubtitle(r) }.getOrDefault(false)
                            downloading = null
                            if (ok) close() else status = "Couldn't download that one. Try another."
                        }
                    }
                },
                modifier = if (i == 0) Modifier.focusRequester(firstResult) else Modifier,
            ) { f ->
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MenuLabel.copy(color = if (f) G.text else G.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val detail = if (downloading == r.key) "Downloading…" else listOfNotNull(
                        r.codec.uppercase(),
                        "Best match".takeIf { i == 0 },
                        "Hearing impaired".takeIf { r.hearingImpaired },
                    ).joinToString("  ·  ")
                    Text(detail, style = G.small.copy(color = if (downloading == r.key) G.red else G.faint))
                }
            }
        }
    }
}

@Composable
private fun SearchGlyph(c: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val sw = w * 0.12f
        drawCircle(c, radius = w * 0.32f, center = androidx.compose.ui.geometry.Offset(w * 0.42f, w * 0.42f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        drawLine(c, androidx.compose.ui.geometry.Offset(w * 0.66f, w * 0.66f), androidx.compose.ui.geometry.Offset(w * 0.92f, w * 0.92f), sw * 1.2f, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
private fun PlaybackMenu(session: PlaybackSession, st: PlayerState?, first: FocusRequester, speedOpen: Boolean, setSpeedOpen: (Boolean) -> Unit) {
    val engine = session.engine
    val speed = st?.speed ?: 1f
    val current = SPEEDS.indexOfFirst { kotlin.math.abs(it - speed) < 0.01f }
    val pillFirst = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(speedOpen) {
        if (speedOpen) { wasOpen = true; pillFirst.focusSoon() } else if (wasOpen) first.focusSoon()
    }

    // Speed: one row; OK opens the choices in place (← → then OK).
    if (!speedOpen) {
        MenuRow(onClick = { setSpeedOpen(true) }, modifier = Modifier.focusRequester(first)) { _ ->
            Text("Speed", style = MenuLabel, modifier = Modifier.weight(1f))
            Text(fmtSpeed(speed), style = MenuLabel.copy(color = G.dim))
            Spacer(Modifier.width(8.dp))
            Chevron(G.dim)
        }
    } else {
        var hadFocus by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxWidth().clip(RowShape).background(Color(0x1AFFFFFF)).border(1.dp, Color(0x3CFFFFFF), RowShape)
                .onFocusChanged {
                    if (it.hasFocus) hadFocus = true
                    else if (hadFocus) setSpeedOpen(false)
                }
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speed", style = MenuLabel, modifier = Modifier.weight(1f))
                Text("← → to choose, OK to set", style = G.small.copy(color = G.faint))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SPEEDS.forEachIndexed { i, s ->
                    val isCur = i == current
                    FocusBox(
                        onClick = { engine?.setSpeed(s); setSpeedOpen(false) },
                        modifier = Modifier.weight(1f).height(30.dp)
                            .let { if (i == (if (current >= 0) current else 2)) it.focusRequester(pillFirst) else it },
                        shape = G.pillShape,
                        focusScale = 1.06f,
                        idleColor = if (isCur) Color(0x40FFFFFF) else Color(0x14FFFFFF),
                        focusColor = Color(0xF2F4F4F6),
                        idleRim = Color.Transparent,
                        glow = false,
                    ) { f ->
                        Text(
                            fmtSpeed(s),
                            style = G.label.copy(fontSize = 13.sp, color = if (f) Color(0xFF0B0B0F) else if (isCur) G.text else G.dim),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))

    // Player: ← → switches between mpv and ExoPlayer (restarts at the same spot).
    val mpv = engine?.choice == EngineChoice.MPV
    fun pick(wantMpv: Boolean) { if (wantMpv != mpv) session.switchEngine() }
    MenuRow(
        onClick = { session.switchEngine() },
        modifier = Modifier.onPreviewKeyEvent { ev ->
            when (ev.nativeKeyEvent.keyCode) {
                AK.KEYCODE_DPAD_LEFT -> { if (ev.type == KeyEventType.KeyUp) pick(true); true }
                AK.KEYCODE_DPAD_RIGHT -> { if (ev.type == KeyEventType.KeyUp) pick(false); true }
                else -> false
            }
        },
    ) { f ->
        Column(Modifier.weight(1f)) {
            Text("Player", style = MenuLabel)
            Text(whyText(session.reason), style = G.small.copy(color = G.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Segmented(listOf("mpv", "ExoPlayer"), if (mpv) 0 else 1, f)
    }

    // File details: read-only.
    session.info?.let { mi ->
        Spacer(Modifier.height(18.dp))
        Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(1.dp).background(Color(0x1EFFFFFF)))
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Text("File", style = G.small.copy(color = G.faint, fontWeight = FontWeight.Medium))
            Spacer(Modifier.height(8.dp))
            fileDetails(mi, st).forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(k, style = G.small.copy(fontSize = 13.sp, color = G.faint))
                    Spacer(Modifier.width(16.dp))
                    Text(
                        v, style = G.small.copy(fontSize = 13.sp, color = G.dim), textAlign = TextAlign.End,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun whyText(reason: String): String = when (reason) {
    "Dolby Vision", "HDR" -> "Picked for $reason"
    "Styled subtitles" -> "Picked for styled subtitles"
    "Chosen in player" -> "Chosen here"
    "Default" -> "Your default player"
    "" -> ""
    else -> reason
}

/** Pill-shaped two-way switch; the chosen side fills white while its row is focused. */
@Composable
private fun Segmented(options: List<String>, selected: Int, focused: Boolean) {
    Row(
        Modifier.clip(G.pillShape).background(Color(0x1AFFFFFF)).border(1.dp, Color(0x2EFFFFFF), G.pillShape).padding(2.dp),
    ) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            val bg = when {
                on && focused -> Color(0xF2F4F4F6)
                on -> Color(0x40FFFFFF)
                else -> Color.Transparent
            }
            val fg = when {
                on && focused -> Color(0xFF0B0B0F)
                on -> G.text
                else -> G.dim
            }
            Text(
                o, style = G.label.copy(fontSize = 13.sp, color = fg),
                modifier = Modifier.clip(G.pillShape).background(bg).padding(horizontal = 14.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun Chevron(c: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(10.dp)) {
        val w = size.width
        val h = size.height
        val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.3f, h * 0.1f); lineTo(w * 0.7f, h * 0.5f); lineTo(w * 0.3f, h * 0.9f) }
        drawPath(p, c, style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.16f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Label/value pairs for the File section of the playback panel. */
private fun fileDetails(mi: com.mononymm.glasstv.plex.MediaInfo, st: PlayerState?): List<Pair<String, String>> = buildList {
    val fps = st?.videoFps?.takeIf { it > 0 } ?: mi.frameRate
    val video = listOfNotNull(
        mi.resolution,
        mi.videoCodec?.uppercase(),
        if (mi.dolbyVision) "Dolby Vision" + (mi.dvProfile?.let { " P$it" } ?: "") else if (mi.hdr) "HDR" else null,
        fps?.let { "%.3f fps".format(it) },
    ).joinToString("  ·  ")
    if (video.isNotBlank()) add("Video" to video)
    val audio = st?.audio?.firstOrNull { it.selected }?.label
        ?: listOfNotNull(mi.audioCodec?.uppercase(), mi.audioChannels?.let { channels(it) }).joinToString(" ")
    if (audio.isNotBlank()) add("Audio" to audio)
    add("Subtitles" to (st?.subs?.firstOrNull { it.selected }?.label ?: "Off"))
    val file = listOfNotNull(
        mi.container?.uppercase(),
        mi.sizeBytes?.takeIf { it > 0 }?.let { fmtBytes(it) },
        mi.bitrateKbps?.let { if (it >= 1000) "%.1f Mbps".format(it / 1000f) else "$it kbps" },
    ).joinToString("  ·  ")
    if (file.isNotBlank()) add("File" to file)
    val render = st?.renderSize
    if (render != null) add("Rendering" to listOfNotNull(render, st?.scaler).joinToString("  ·  "))
    else add("Rendering" to "Scaled by the TV")
}

private fun channels(n: Int): String = when (n) {
    1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$n ch"
}

private fun fmtBytes(bytes: Long): String {
    val gb = bytes / 1_073_741_824.0
    return if (gb >= 1) "%.2f GB".format(gb) else "%.0f MB".format(bytes / 1_048_576.0)
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
