package com.mononymm.glasstv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mononymm.glasstv.plex.Plex
import com.mononymm.glasstv.plex.Server
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SignInScreen() {
    val app = LocalApp.current
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val retry = remember { FocusRequester() }

    LaunchedEffect(attempt) {
        error = null
        code = null
        val pin = runCatching { app.plex.createPin() }.getOrElse {
            error = "Couldn't reach plex.tv. Check the TV's internet connection."
            return@LaunchedEffect
        }
        code = pin.code
        // PINs last ~15 minutes; poll every 2 s.
        repeat(450) {
            delay(2000)
            val token = runCatching { app.plex.checkPin(pin) }.getOrNull()
            if (token != null) {
                app.prefs.accountToken = token
                app.replaceAll(Screen.Servers)
                return@LaunchedEffect
            }
        }
        error = "That code expired."
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        GlassPanel(Modifier.width(560.dp)) {
            Logo(Modifier.size(64.dp))
            Spacer(Modifier.height(14.dp))
            Text("Sign in with Plex", style = G.title.copy(fontSize = 26.sp))
            Spacer(Modifier.height(22.dp))
            Step(1, "On your phone or computer, open", "plex.tv/link")
            Spacer(Modifier.height(10.dp))
            Step(2, "Enter this code", null)
            Spacer(Modifier.height(18.dp))
            val chars = (code ?: "    ").padEnd(4).take(4)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                chars.forEach { ch ->
                    Box(
                        Modifier.size(64.dp, 78.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color(0x1FFFFFFF)).border(1.dp, G.rim, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (ch == ' ') "·" else ch.toString(), style = G.title.copy(fontSize = 38.sp, fontWeight = FontWeight.Bold))
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                error ?: "This screen moves on by itself once you've entered the code.",
                style = G.small, textAlign = TextAlign.Center,
            )
            if (error != null) {
                Spacer(Modifier.height(14.dp))
                ActionButton("Get a new code", { attempt++ }, Modifier.focusRequester(retry), primary = true)
                LaunchedEffect(Unit) { retry.focusSoon() }
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String, highlight: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(G.red), contentAlignment = Alignment.Center) {
            Text("$n", style = G.small.copy(color = Color.White, fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = G.body.copy(color = G.text))
        if (highlight != null) {
            Spacer(Modifier.width(8.dp))
            Text(highlight, style = G.h2.copy(color = G.red))
        }
    }
}

@Composable
fun ServersScreen() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<Server>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    suspend fun choose(plex: Plex, s: Server) {
        status = "Connecting to ${s.name}…"
        val uri = plex.connect(s)
        if (uri == null) {
            status = "Couldn't reach ${s.name}. Is it running and on the same network?"
        } else {
            plex.useServer(s, uri)
            app.changed()
            app.replaceAll(Screen.Home)
        }
    }

    LaunchedEffect(Unit) {
        val list = runCatching { app.plex.servers() }.getOrElse {
            status = "Couldn't load your servers."
            emptyList()
        }
        servers = list
        if (list.size == 1) choose(app.plex, list[0])
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        GlassPanel(Modifier.width(560.dp)) {
            Logo(Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text("Choose a server", style = G.title.copy(fontSize = 26.sp))
            Spacer(Modifier.height(20.dp))
            val list = servers
            when {
                list == null -> Text("Looking for servers…", style = G.body)
                list.isEmpty() -> Text("No Plex servers on this account.", style = G.body)
                else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    list.forEachIndexed { i, s ->
                        FocusBox(
                            onClick = { scope.launch { choose(app.plex, s) } },
                            modifier = Modifier.fillMaxWidth().height(64.dp).let { if (i == 0) it.focusRequester(first) else it },
                            shape = RoundedCornerShape(14.dp),
                            focusScale = 1.03f,
                        ) { f ->
                            Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, style = G.h2)
                                    Text(if (s.connections.any { it.local }) "On your network" else "Remote", style = G.small)
                                }
                                if (f) PlayGlyph(G.red, 16.dp)
                            }
                        }
                    }
                }
            }
            status?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, style = G.small, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(20.dp))
            Pill("Sign out", { app.signOut() })
        }
    }
    LaunchedEffect(servers) { if (!servers.isNullOrEmpty()) first.focusSoon() }
}
