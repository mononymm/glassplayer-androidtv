package com.mononymm.glasstv.player

import android.content.Context
import android.os.Build
import android.view.Surface
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mononymm.glasstv.plex.EngineChoice
import com.mononymm.glasstv.plex.MediaInfo
import com.mononymm.glasstv.plex.Prefs

data class TrackInfo(val id: String, val label: String, val selected: Boolean)

/** What a player engine needs to start a file. */
data class LoadRequest(
    val url: String,
    val startMs: Long,
    val headers: Map<String, String>,
    val info: MediaInfo,
    /** External (sidecar) subtitles: url, codec, label, language. */
    val externalSubs: List<ExternalSub>,
)

data class ExternalSub(val url: String, val codec: String, val label: String, val language: String?)

/** Observable playback state, written by the engines on the main thread. */
class PlayerState {
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var paused by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var ended by mutableStateOf(false)
    var speed by mutableFloatStateOf(1f)
    var audio by mutableStateOf<List<TrackInfo>>(emptyList())
    var subs by mutableStateOf<List<TrackInfo>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var videoFps by mutableFloatStateOf(0f)
}

interface Engine {
    val choice: EngineChoice
    val state: PlayerState
    fun createView(ctx: Context): View
    fun load(req: LoadRequest)
    fun setPaused(paused: Boolean)
    fun seekTo(ms: Long)
    fun setSpeed(speed: Float)
    fun selectAudio(id: String)
    /** null turns subtitles off. */
    fun selectSub(id: String?)
    fun release()
}

object Engines {
    fun create(choice: EngineChoice, ctx: Context, prefs: Prefs): Engine = when (choice) {
        EngineChoice.MPV -> MpvEngine(ctx, prefs)
        else -> ExoEngine(ctx, prefs)
    }
}

/** Ask the TV to switch its refresh rate to match the video (24p etc). */
object FrameRate {
    fun apply(surface: Surface?, fps: Float) {
        if (surface == null || !surface.isValid || fps <= 1f) return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
            } else if (Build.VERSION.SDK_INT >= 30) {
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        } catch (_: Throwable) {
        }
    }

    fun clear(surface: Surface?) {
        if (surface == null || !surface.isValid) return
        try {
            if (Build.VERSION.SDK_INT >= 30) surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        } catch (_: Throwable) {
        }
    }
}

/** Decides which engine plays a file. Overrides win: show, then library, then the rules below. */
object EnginePicker {
    data class Pick(val engine: EngineChoice, val reason: String)

    fun pick(info: MediaInfo, prefs: Prefs, showKey: String?, sectionId: String?): Pick {
        prefs.engineForShow(showKey ?: info.item.ratingKey).takeIf { it != EngineChoice.AUTO }?.let { return Pick(it, if (showKey != null) "Show setting" else "Movie setting") }
        prefs.engineForLibrary(sectionId).takeIf { it != EngineChoice.AUTO }?.let { return Pick(it, "Library setting") }
        return when {
            info.dolbyVision -> Pick(EngineChoice.EXO, "Dolby Vision")
            info.hasStyledSubs -> Pick(EngineChoice.MPV, "Styled subtitles")
            info.hdr -> Pick(EngineChoice.EXO, "HDR")
            else -> Pick(prefs.defaultEngine, "Default")
        }
    }
}
