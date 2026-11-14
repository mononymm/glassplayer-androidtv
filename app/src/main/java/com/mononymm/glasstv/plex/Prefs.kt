package com.mononymm.glasstv.plex

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/** Which player to use. AUTO decides per file (see player/EnginePicker.kt). */
enum class EngineChoice(val label: String) {
    AUTO("Auto"), MPV("mpv"), EXO("ExoPlayer");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.name == s } ?: AUTO
    }
}

class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)

    val clientId: String
        get() = p.getString("clientId", null) ?: UUID.randomUUID().toString().also {
            p.edit().putString("clientId", it).apply()
        }

    var accountToken: String?
        get() = p.getString("accountToken", null)
        set(v) = p.edit().putString("accountToken", v).apply()

    var serverName: String?
        get() = p.getString("serverName", null)
        set(v) = p.edit().putString("serverName", v).apply()

    var serverId: String?
        get() = p.getString("serverId", null)
        set(v) = p.edit().putString("serverId", v).apply()

    var serverUri: String?
        get() = p.getString("serverUri", null)
        set(v) = p.edit().putString("serverUri", v).apply()

    var serverToken: String?
        get() = p.getString("serverToken", null)
        set(v) = p.edit().putString("serverToken", v).apply()

    /** Delay (ms) applied to ExoPlayer subtitles to match the TV's video processing latency. */
    var subDelayMs: Int
        get() = p.getInt("subDelayMs", 0)
        set(v) = p.edit().putInt("subDelayMs", v).apply()

    var matchFrameRate: Boolean
        get() = p.getBoolean("matchFrameRate", true)
        set(v) = p.edit().putBoolean("matchFrameRate", v).apply()

    /** mpv upscaling with Lanczos EWA instead of bilinear (falls back on its own if frames drop). */
    var sharpScaling: Boolean
        get() = p.getBoolean("sharpScaling2", false)
        set(v) = p.edit().putBoolean("sharpScaling2", v).apply()

    var upNext: Boolean
        get() = p.getBoolean("upNext", true)
        set(v) = p.edit().putBoolean("upNext", v).apply()

    /** Player for files that don't need either engine specifically (no DV, no styled subs). */
    var defaultEngine: EngineChoice
        get() = EngineChoice.of(p.getString("defaultEngine", EngineChoice.MPV.name)).let { if (it == EngineChoice.AUTO) EngineChoice.MPV else it }
        set(v) = p.edit().putString("defaultEngine", v.name).apply()

    fun engineForLibrary(sectionId: String?): EngineChoice =
        if (sectionId == null) EngineChoice.AUTO else EngineChoice.of(p.getString("engine:lib:$sectionId", null))

    fun setEngineForLibrary(sectionId: String, c: EngineChoice) =
        p.edit().putString("engine:lib:$sectionId", c.name).apply()

    fun engineForShow(showKey: String?): EngineChoice =
        if (showKey == null) EngineChoice.AUTO else EngineChoice.of(p.getString("engine:show:$showKey", null))

    fun setEngineForShow(showKey: String, c: EngineChoice) =
        p.edit().putString("engine:show:$showKey", c.name).apply()

    fun signOut() {
        p.edit().remove("accountToken").remove("serverUri").remove("serverToken")
            .remove("serverName").remove("serverId").apply()
    }
}
