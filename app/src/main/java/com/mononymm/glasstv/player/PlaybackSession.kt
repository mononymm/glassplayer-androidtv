package com.mononymm.glasstv.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mononymm.glasstv.plex.EngineChoice
import com.mononymm.glasstv.plex.Item
import com.mononymm.glasstv.plex.MediaInfo
import com.mononymm.glasstv.plex.Plex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One playback: loads Plex metadata, picks the engine, reports progress back to Plex,
 * and finds the next episode. Can switch engines mid-file, keeping the position.
 */
class PlaybackSession(
    private val ctx: Context,
    private val plex: Plex,
    startItem: Item,
    private val fromStart: Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var item by mutableStateOf(startItem)
        private set
    var info by mutableStateOf<MediaInfo?>(null)
        private set
    var engine by mutableStateOf<Engine?>(null)
        private set
    var reason by mutableStateOf("")
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var next by mutableStateOf<Item?>(null)
        private set
    /** Bumps whenever the engine (and so the video view) is replaced. */
    var generation by mutableStateOf(0)
        private set

    private var scrobbled = false
    private var reporter: Job? = null
    private var lastPaused: Boolean? = null

    init {
        open(startItem, if (fromStart) 0L else -1L, null)
    }

    /** startMs = -1 resumes from Plex's saved position. */
    private fun open(target: Item, startMs: Long, force: EngineChoice?) {
        scope.launch {
            loadError = null
            next = null
            scrobbled = false
            val mi = runCatching { plex.mediaInfo(target.ratingKey) }.getOrNull()
            if (mi == null) {
                loadError = "Couldn't load this file from Plex."
                return@launch
            }
            item = mi.item
            info = mi
            val pick = if (force != null) EnginePicker.Pick(force, "Chosen in player")
            else EnginePicker.pick(mi, plex.prefs, mi.item.showKey, mi.item.sectionId)
            reason = pick.reason
            val start = if (startMs >= 0) startMs else resumePoint(mi.item)
            startEngine(pick.engine, mi, start)
            if (mi.item.isEpisode) {
                next = runCatching { plex.nextEpisode(mi.item) }.getOrNull()
            }
        }
    }

    private fun resumePoint(it: Item): Long {
        val off = it.viewOffset
        // Plex keeps a position for nearly-finished files; start those over.
        return if (off > 0 && (it.duration == 0L || off < it.duration * 0.95)) off else 0L
    }

    private fun startEngine(choice: EngineChoice, mi: MediaInfo, startMs: Long) {
        engine?.release()
        val e = Engines.create(choice, ctx, plex.prefs)
        engine = e
        generation++
        val subs = mi.subs.filter { it.external && it.key != null }.map {
            ExternalSub(plex.subtitleUrl(it.key!!), it.codec, it.title, it.language)
        }
        e.load(LoadRequest(plex.mediaUrl(mi.partKey), startMs, plex.mediaHeaders(), mi, subs))
        startReporting(e)
    }

    private fun startReporting(e: Engine) {
        reporter?.cancel()
        lastPaused = null
        reporter = scope.launch {
            var ticks = 0
            while (isActive) {
                delay(1000)
                val s = e.state
                if (s.durationMs <= 0) continue
                ticks++
                val pausedChanged = lastPaused != s.paused
                if (pausedChanged || ticks % 10 == 0) {
                    lastPaused = s.paused
                    report(if (s.paused) "paused" else "playing", s.positionMs, s.durationMs)
                }
                if (!scrobbled && s.positionMs > s.durationMs * 0.9) {
                    scrobbled = true
                    val k = item.ratingKey
                    scope.launch { plex.markWatched(k) }
                }
            }
        }
    }

    private suspend fun report(state: String, pos: Long, dur: Long) {
        val it = item
        withContext(Dispatchers.IO) { runCatching { plex.timeline(it, state, pos, dur) } }
    }

    fun switchEngine() {
        val e = engine ?: return
        val mi = info ?: return
        val other = if (e.choice == EngineChoice.MPV) EngineChoice.EXO else EngineChoice.MPV
        val pos = e.state.positionMs
        reason = "Chosen in player"
        startEngine(other, mi, pos)
    }

    /** Stops the current file (reporting its position) and plays [target]. */
    fun play(target: Item, fromStart: Boolean = false) {
        stopReporting()
        open(target, if (fromStart) 0L else -1L, null)
    }

    private fun stopReporting() {
        reporter?.cancel()
        val e = engine ?: return
        val s = e.state
        val it = item
        if (s.durationMs > 0) {
            CoroutineScope(Dispatchers.IO).launch {
                withContext(NonCancellable) { runCatching { plex.timeline(it, "stopped", s.positionMs, s.durationMs) } }
            }
        }
    }

    fun close() {
        stopReporting()
        engine?.release()
        engine = null
        scope.cancel()
    }
}
