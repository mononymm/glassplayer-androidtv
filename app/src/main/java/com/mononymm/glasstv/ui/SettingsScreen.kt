package com.mononymm.glasstv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.mononymm.glasstv.plex.EngineChoice

@Composable
fun SettingsScreen() {
    val app = LocalApp.current
    val p = app.prefs
    var subDelay by remember { mutableStateOf(p.subDelayMs) }
    var matchFps by remember { mutableStateOf(p.matchFrameRate) }
    var upNext by remember { mutableStateOf(p.upNext) }
    var defEngine by remember { mutableStateOf(p.defaultEngine) }
    var libEngines by remember { mutableStateOf(app.sections.associate { it.key to p.engineForLibrary(it.key) }) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.focusSoon() }

    BrowseFrame(tab = "settings", hero = null, heroHeight = 0) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 16.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Header("Server") }
            item {
                SettingRow(
                    "${p.serverName ?: "No server"}",
                    app.plex.hostLabel().let { if (it.isBlank()) "" else "Connected via $it" },
                    "Change",
                    Modifier.focusRequester(first),
                ) { app.go(Screen.Servers) }
            }

            item { Header("Playback") }
            item {
                SettingRow(
                    "Player for everyday files",
                    "Files without Dolby Vision, HDR or styled subtitles. Those are always routed automatically.",
                    defEngine.label,
                ) {
                    defEngine = if (defEngine == EngineChoice.EXO) EngineChoice.MPV else EngineChoice.EXO
                    p.defaultEngine = defEngine
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(560.dp)) {
                        Text("Subtitle delay (ExoPlayer)", style = G.label)
                        Text(
                            "Your TV shows video slightly later than the menu layer. If subtitles appear early, raise this. mpv draws subtitles into the picture and never needs it.",
                            style = G.small,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Pill("−", { subDelay = (subDelay - 25).coerceAtLeast(0); p.subDelayMs = subDelay })
                    Text("$subDelay ms", style = G.label, modifier = Modifier.padding(horizontal = 14.dp))
                    Pill("+", { subDelay = (subDelay + 25).coerceAtMost(1000); p.subDelayMs = subDelay })
                }
            }
            item {
                SettingRow("Match frame rate", "Switch the TV to 24/25/30/60 Hz to match the video. Removes judder.", if (matchFps) "On" else "Off") {
                    matchFps = !matchFps; p.matchFrameRate = matchFps
                }
            }
            item {
                SettingRow("Up next", "Play the next episode automatically after a countdown.", if (upNext) "On" else "Off") {
                    upNext = !upNext; p.upNext = upNext
                }
            }

            if (app.sections.isNotEmpty()) {
                item { Header("Player per library") }
                app.sections.forEach { s ->
                    item(key = "lib-${s.key}") {
                        val cur = libEngines[s.key] ?: EngineChoice.AUTO
                        SettingRow(s.title, "Auto picks per file: ExoPlayer for Dolby Vision/HDR, mpv for styled (ASS) subtitles.", cur.label) {
                            val n = EngineChoice.entries[(cur.ordinal + 1) % EngineChoice.entries.size]
                            p.setEngineForLibrary(s.key, n)
                            libEngines = libEngines + (s.key to n)
                        }
                    }
                }
            }

            item { Header("Account") }
            item { SettingRow("Sign out", "Removes your Plex sign-in from this TV.", "Sign out") { app.signOut() } }
            item { Text("Glass ${app.version}", style = G.small.copy(color = G.faint), modifier = Modifier.padding(top = 16.dp)) }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = G.h2, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

@Composable
private fun SettingRow(title: String, detail: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(560.dp)) {
            Text(title, style = G.label)
            if (detail.isNotBlank()) Text(detail, style = G.small)
        }
        Spacer(Modifier.width(16.dp))
        Pill(value, onClick, modifier)
    }
}
