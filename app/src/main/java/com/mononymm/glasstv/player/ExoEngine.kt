package com.mononymm.glasstv.player

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.mononymm.glasstv.plex.EngineChoice
import com.mononymm.glasstv.plex.Prefs
import java.util.Locale

/**
 * Media3 / ExoPlayer engine: the TV's own decoders, so Dolby Vision, HDR10 and
 * audio passthrough work. Subtitles are drawn on the UI layer, which the TV shows
 * slightly ahead of the (processed) video; [Prefs.subDelayMs] shifts them back.
 */
@OptIn(UnstableApi::class)
class ExoEngine(private val ctx: Context, private val prefs: Prefs) : Engine {
    override val choice = EngineChoice.EXO
    override val state = PlayerState()

    private val main = Handler(Looper.getMainLooper())
    private val trackSelector = DefaultTrackSelector(ctx)
    private val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(20000)
        .setReadTimeoutMs(30000)
    private val player: ExoPlayer
    private var playerView: PlayerView? = null
    private var subtitleView: SubtitleView? = null
    private var released = false

    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            state.positionMs = player.currentPosition
            val d = player.duration
            if (d != C.TIME_UNSET && d > 0) state.durationMs = d
            main.postDelayed(this, 250)
        }
    }

    init {
        val renderers = DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(ctx, renderers)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http)))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        // We switch the display refresh rate ourselves (always, not only when seamless).
        player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                state.buffering = s == Player.STATE_BUFFERING || s == Player.STATE_IDLE
                if (s == Player.STATE_ENDED) state.ended = true
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                state.paused = !player.playWhenReady
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                state.paused = !playWhenReady
            }

            override fun onTracksChanged(tracks: Tracks) {
                readTracks(tracks)
            }

            override fun onCues(cueGroup: CueGroup) {
                val cues = cueGroup.cues
                val delay = prefs.subDelayMs.toLong()
                if (delay <= 0) setCues(cues) else main.postDelayed({ setCues(cues) }, delay)
            }

            override fun onPlayerError(error: PlaybackException) {
                state.error = error.errorCodeName + (error.message?.let { ": $it" } ?: "")
            }
        })
    }

    private fun setCues(cues: List<Cue>) {
        if (!released) subtitleView?.setCues(cues)
    }

    override fun createView(ctx: Context): View {
        val root = FrameLayout(ctx)
        val pv = PlayerView(ctx).apply {
            useController = false
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
            player = this@ExoEngine.player
            this.subtitleView?.visibility = View.GONE
        }
        val sv = SubtitleView(ctx).apply {
            setApplyEmbeddedStyles(true)
            setApplyEmbeddedFontSizes(true)
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.05f)
            setBottomPaddingFraction(0.06f)
            setStyle(
                CaptionStyleCompat(
                    Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null,
                ),
            )
        }
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(pv, FrameLayout.LayoutParams(mp, mp))
        root.addView(sv, FrameLayout.LayoutParams(mp, mp))
        playerView = pv
        subtitleView = sv
        return root
    }

    override fun load(req: LoadRequest) {
        http.setDefaultRequestProperties(req.headers)
        val subs = req.externalSubs.mapNotNull { s ->
            val mime = when (s.codec) {
                "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                "vtt", "webvtt" -> MimeTypes.TEXT_VTT
                else -> null
            } ?: return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                .setMimeType(mime)
                .setLabel(s.label)
                .setLanguage(s.language)
                .build()
        }
        val item = MediaItem.Builder().setUri(req.url).setSubtitleConfigurations(subs).build()
        state.ended = false
        state.buffering = true
        player.setMediaItem(item, req.startMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
        main.removeCallbacks(ticker)
        main.post(ticker)
        req.info.frameRate?.let { applyFps(it) }
    }

    private fun applyFps(fps: Float) {
        if (fps <= 1f) return
        state.videoFps = fps
        if (prefs.matchFrameRate) {
            val sv = playerView?.videoSurfaceView as? SurfaceView
            FrameRate.apply(sv?.holder?.surface, fps)
        }
    }

    private fun readTracks(tracks: Tracks) {
        val audio = ArrayList<TrackInfo>()
        val subs = ArrayList<TrackInfo>()
        tracks.groups.forEachIndexed { g, group ->
            for (t in 0 until group.length) {
                if (!group.isTrackSupported(t)) continue
                val f = group.getTrackFormat(t)
                val lang = f.language?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
                val codec = (f.codecs ?: f.sampleMimeType?.substringAfter('/'))?.uppercase()
                val label = listOfNotNull(f.label, lang, codec).distinct().joinToString(" · ").ifBlank { "Track ${t + 1}" }
                val info = TrackInfo("$g:$t", label, group.isTrackSelected(t))
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> audio += info
                    C.TRACK_TYPE_TEXT -> subs += info
                    C.TRACK_TYPE_VIDEO -> if (group.isTrackSelected(t) && f.frameRate > 0) applyFps(f.frameRate)
                }
            }
        }
        state.audio = audio
        state.subs = subs
    }

    private fun select(type: Int, id: String) {
        val (g, t) = id.split(":").map { it.toInt() }
        val group = player.currentTracks.groups.getOrNull(g) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t))
            .build()
    }

    override fun setPaused(paused: Boolean) {
        player.playWhenReady = !paused
        state.paused = paused
    }

    override fun seekTo(ms: Long) {
        player.seekTo(ms.coerceAtLeast(0))
        state.positionMs = ms.coerceAtLeast(0)
    }

    override fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        state.speed = speed
    }

    override fun selectAudio(id: String) = select(C.TRACK_TYPE_AUDIO, id)

    override fun selectSub(id: String?) {
        if (id == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            setCues(emptyList())
        } else {
            select(C.TRACK_TYPE_TEXT, id)
        }
    }

    override fun release() {
        if (released) return
        released = true
        main.removeCallbacksAndMessages(null)
        FrameRate.clear((playerView?.videoSurfaceView as? SurfaceView)?.holder?.surface)
        playerView?.player = null
        player.release()
    }
}
