package com.mononymm.glasstv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Glass", style = G.title.copy(fontSize = 44.sp))
            Spacer(Modifier.height(28.dp))
            Text("On your phone or computer, go to", style = G.body)
            Spacer(Modifier.height(6.dp))
            Text("plex.tv/link", style = G.h2.copy(fontSize = 26.sp, color = G.red))
            Spacer(Modifier.height(22.dp))
            Text("and enter this code", style = G.body)
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    code ?: "· · · ·",
                    style = G.title.copy(fontSize = 64.sp, letterSpacing = 14.sp, fontWeight = FontWeight.Black),
                )
            }
            error?.let {
                Spacer(Modifier.height(18.dp))
                Text(it, style = G.body)
                Spacer(Modifier.height(12.dp))
                ActionButton("Get a new code", { attempt++ }, Modifier.focusRequester(retry), primary = true)
                LaunchedEffect(Unit) { retry.focusSoon() }
            }
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
        Column(Modifier.width(520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Choose a server", style = G.title)
            Spacer(Modifier.height(20.dp))
            val list = servers
            if (list == null) Text("Looking for servers…", style = G.body)
            else if (list.isEmpty()) Text("No Plex servers on this account.", style = G.body)
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { s ->
                    FocusBox(
                        onClick = { scope.launch { choose(app.plex, s) } },
                        modifier = Modifier.fillMaxWidth().let { if (s == list.first()) it.focusRequester(first) else it },
                        focusScale = 1.03f,
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(s.name, style = G.h2)
                            Text(
                                if (s.connections.any { it.local }) "On your network" else "Remote",
                                style = G.small,
                            )
                        }
                    }
                }
            }
            status?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = G.body)
            }
            Spacer(Modifier.height(24.dp))
            Pill("Sign out", { app.signOut() })
        }
    }
    LaunchedEffect(servers) { if (!servers.isNullOrEmpty()) first.focusSoon() }
}
