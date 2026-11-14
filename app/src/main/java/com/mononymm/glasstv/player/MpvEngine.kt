package com.mononymm.glasstv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.mononymm.glasstv.plex.EngineChoice
import com.mononymm.glasstv.plex.Prefs
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvEvent
import dev.jdtech.mpv.MPVLib.MpvFormat
import java.io.File
import java.util.Locale

/**
 * libmpv engine. Subtitles (ASS/SSA with full styling) are rendered by libass
 * into the video frames themselves, so they can never drift from the picture.
 */
class MpvEngine(private val ctx: Context, private val prefs: Prefs) : Engine, MPVLib.EventObserver {
    override val choice = EngineChoice.MPV
    override val state = PlayerState()

    private val main = Handler(Looper.getMainLooper())
    private var mpv: MPVLib? = null
    private var surfaceView: SurfaceView? = null
    private var pending: LoadRequest? = null
    private var loaded: LoadRequest? = null
    private var surfaceReady = false
    private var released = false

    init {
        val m = MPVLib.create(ctx.applicationContext)
        if (m == null) {
            state.error = "mpv failed to start"
        } else {
            mpv = m
            configure(m)
            m.init()
            observe(m)
            m.addObserver(this)
        }
    }

    private fun configure(m: MPVLib) {
        val files = ctx.filesDir
        val ca = MpvAssets.caFile(ctx)
        val fonts = MpvAssets.fontsDir(ctx)
        m.setOptionString("config", "no")
        m.setOptionString("profile", "fast")
        m.setOptionString("vo", "gpu")
        m.setOptionString("gpu-context", "android")
        m.setOptionString("opengl-es", "yes")
        m.setOptionString("hwdec", "mediacodec,mediacodec-copy")
        m.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        m.setOptionString("ao", "audiotrack,opensles")
        m.setOptionString("audio-set-media-role", "yes")
        m.setOptionString("tls-verify", if (ca != null) "yes" else "no")
        ca?.let { m.setOptionString("tls-ca-file", it.absolutePath) }
        m.setOptionString("input-default-bindings", "no")
        m.setOptionString("osc", "no")
        m.setOptionString("osd-level", "0")
        m.setOptionString("keep-open", "yes")
        m.setOptionString("idle", "yes")
        m.setOptionString("force-window", "no")
        m.setOptionString("save-position-on-quit", "no")
        m.setOptionString("cache", "yes")
        m.setOptionString("demuxer-max-bytes", "96MiB")
        m.setOptionString("demuxer-max-back-bytes", "32MiB")
        m.setOptionString("network-timeout", "30")
        m.setOptionString("sub-auto", "no")
        m.setOptionString("embeddedfonts", "yes")
        m.setOptionString("sub-ass-override", "no")
        m.setOptionString("sub-fonts-dir", fonts.absolutePath)
        m.setOptionString("osd-fonts-dir", fonts.absolutePath)
        m.setOptionString("sub-font", "Roboto")
        m.setOptionString("alang", "jpn,ja,eng,en")
        m.setOptionString("slang", "eng,en")
        m.setOptionString("screenshot-directory", files.absolutePath)
    }

    private fun observe(m: MPVLib) {
        m.observeProperty("time-pos", MpvFormat.MPV_FORMAT_DOUBLE)
        m.observeProperty("duration", MpvFormat.MPV_FORMAT_DOUBLE)
        m.observeProperty("pause", MpvFormat.MPV_FORMAT_FLAG)
        m.observeProperty("paused-for-cache", MpvFormat.MPV_FORMAT_FLAG)
        m.observeProperty("eof-reached", MpvFormat.MPV_FORMAT_FLAG)
        m.observeProperty("speed", MpvFormat.MPV_FORMAT_DOUBLE)
        m.observeProperty("track-list/count", MpvFormat.MPV_FORMAT_INT64)
        m.observeProperty("aid", MpvFormat.MPV_FORMAT_STRING)
        m.observeProperty("sid", MpvFormat.MPV_FORMAT_STRING)
        m.observeProperty("container-fps", MpvFormat.MPV_FORMAT_DOUBLE)
    }

    override fun createView(ctx: Context): View {
        val sv = SurfaceView(ctx)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val m = mpv ?: return
                m.attachSurface(holder.surface)
                m.setOptionString("force-window", "yes")
                m.setPropertyString("vo", "gpu")
                surfaceReady = true
                pending?.let { pending = null; start(it) }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                mpv?.setPropertyString("android-surface-size", "${width}x$height")
                if (prefs.matchFrameRate) FrameRate.apply(holder.surface, state.videoFps)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                val m = mpv ?: return
                m.setPropertyString("vo", "null")
                m.setOptionString("force-window", "no")
                m.detachSurface()
            }
        })
        surfaceView = sv
        return sv
    }

    override fun load(req: LoadRequest) {
        if (surfaceReady) start(req) else pending = req
    }

    private fun start(req: LoadRequest) {
        val m = mpv ?: return
        loaded = req
        state.ended = false
        state.buffering = true
        val hdr = req.headers.entries.joinToString(",") { "${it.key}: ${it.value}" }
        m.setOptionString("http-header-fields", hdr)
        m.setPropertyString("start", if (req.startMs > 0) String.format(Locale.US, "%.3f", req.startMs / 1000.0) else "none")
        m.command(arrayOf("loadfile", req.url, "replace"))
    }

    private fun onFileLoaded() {
        val m = mpv ?: return
        m.setPropertyString("start", "none")
        loaded?.externalSubs?.forEach { s ->
            m.command(arrayOf("sub-add", s.url, "auto", s.label, s.language ?: ""))
        }
        // Prefer a styled (full) English track over "signs & songs" when nothing was chosen.
        refreshTracks()
    }

    override fun setPaused(paused: Boolean) {
        mpv?.setPropertyBoolean("pause", paused)
    }

    override fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0) / 1000.0
        mpv?.command(arrayOf("seek", String.format(Locale.US, "%.3f", target), "absolute"))
        state.positionMs = ms.coerceAtLeast(0)
    }

    override fun setSpeed(speed: Float) {
        mpv?.setPropertyDouble("speed", speed.toDouble())
    }

    override fun selectAudio(id: String) {
        mpv?.setPropertyString("aid", id)
    }

    override fun selectSub(id: String?) {
        mpv?.setPropertyString("sid", id ?: "no")
    }

    override fun release() {
        if (released) return
        released = true
        FrameRate.clear(surfaceView?.holder?.surface)
        val m = mpv ?: return
        mpv = null
        m.removeObserver(this)
        runCatching { m.command(arrayOf("stop")) }
        runCatching { m.destroy() }
    }

    private fun refreshTracks() {
        val m = mpv ?: return
        val n = m.getPropertyInt("track-list/count") ?: 0
        val audio = ArrayList<TrackInfo>()
        val subs = ArrayList<TrackInfo>()
        for (i in 0 until n) {
            val type = m.getPropertyString("track-list/$i/type") ?: continue
            val id = m.getPropertyInt("track-list/$i/id")?.toString() ?: continue
            val title = m.getPropertyString("track-list/$i/title")
            val lang = m.getPropertyString("track-list/$i/lang")
            val codec = m.getPropertyString("track-list/$i/codec")
            val sel = m.getPropertyBoolean("track-list/$i/selected") ?: false
            val label = listOfNotNull(
                title?.takeIf { it.isNotBlank() },
                lang?.let { Locale.forLanguageTag(it).displayLanguage.takeIf { d -> d.isNotBlank() } ?: it },
                codec?.uppercase(),
            ).distinct().joinToString(" · ").ifBlank { "Track $id" }
            when (type) {
                "audio" -> audio += TrackInfo(id, label, sel)
                "sub" -> subs += TrackInfo(id, label, sel)
            }
        }
        state.audio = audio
        state.subs = subs
    }

    // ----- MPVLib.EventObserver (called on mpv's event thread) -----

    override fun eventProperty(property: String) {}

    override fun eventProperty(property: String, value: Long) {
        if (property == "track-list/count") main.post { refreshTracks() }
    }

    override fun eventProperty(property: String, value: Double) {
        main.post {
            when (property) {
                "time-pos" -> state.positionMs = (value * 1000).toLong()
                "duration" -> state.durationMs = (value * 1000).toLong()
                "speed" -> state.speed = value.toFloat()
                "container-fps" -> {
                    state.videoFps = value.toFloat()
                    if (prefs.matchFrameRate) FrameRate.apply(surfaceView?.holder?.surface, value.toFloat())
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        main.post {
            when (property) {
                "pause" -> state.paused = value
                "paused-for-cache" -> state.buffering = value
                "eof-reached" -> if (value) state.ended = true
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        if (property == "aid" || property == "sid") main.post { refreshTracks() }
    }

    override fun event(eventId: Int) {
        main.post {
            when (eventId) {
                MpvEvent.MPV_EVENT_FILE_LOADED -> onFileLoaded()
                MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> state.buffering = false
                MpvEvent.MPV_EVENT_END_FILE -> {}
            }
        }
    }
}

/** Files mpv needs on disk: the CA bundle for https and a fallback subtitle font. */
object MpvAssets {
    fun caFile(ctx: Context): File? {
        val out = File(ctx.filesDir, "cacert.pem")
        if (out.exists() && out.length() > 0) return out
        return runCatching {
            ctx.assets.open("cacert.pem").use { input -> out.outputStream().use { input.copyTo(it) } }
            out
        }.getOrNull()
    }

    fun fontsDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "fonts").apply { mkdirs() }
        val wanted = listOf("Roboto-Regular.ttf", "NotoSansCJK-Regular.ttc", "DroidSansFallback.ttf")
        for (name in wanted) {
            val src = File("/system/fonts", name)
            val dst = File(dir, name)
            if (src.exists() && !dst.exists()) runCatching { src.copyTo(dst) }
        }
        if (dir.listFiles().isNullOrEmpty()) {
            File("/system/fonts").listFiles()?.firstOrNull { it.name.startsWith("Roboto") }
                ?.let { runCatching { it.copyTo(File(dir, it.name)) } }
        }
        return dir
    }
}
