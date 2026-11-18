package com.mononymm.glasstv.plex

import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Plex API client: plex.tv sign-in + the selected Plex Media Server. */
class Plex(val prefs: Prefs, private val version: String) {

    data class Pin(val id: Long, val code: String)

    val baseUrl: String? get() = prefs.serverUri
    private val serverToken: String? get() = prefs.serverToken ?: prefs.accountToken

    fun headers(token: String? = serverToken): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("X-Plex-Client-Identifier", prefs.clientId)
        put("X-Plex-Product", "Glass")
        put("X-Plex-Version", version)
        put("X-Plex-Platform", "Android")
        put("X-Plex-Platform-Version", Build.VERSION.RELEASE ?: "")
        put("X-Plex-Device", "Android TV")
        put("X-Plex-Device-Name", "Glass (${Build.MODEL})")
        put("X-Plex-Provides", "player")
        token?.let { put("X-Plex-Token", it) }
    }

    /** Header map handed to the players for media requests. */
    fun mediaHeaders(): Map<String, String> = serverToken?.let { mapOf("X-Plex-Token" to it) } ?: emptyMap()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun get(path: String, token: String? = serverToken): JSONObject = withContext(Dispatchers.IO) {
        val base = baseUrl ?: error("No server selected")
        JSONObject(Net.request("GET", base + path, headers(token)))
    }

    private suspend fun send(method: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val base = baseUrl ?: return@withContext false
        runCatching { Net.request(method, base + path, headers()) }.isSuccess
    }

    private fun JSONObject.container(): JSONObject = optJSONObject("MediaContainer") ?: JSONObject()
    private fun JSONObject.items(): List<Item> = container().arr("Metadata").map { Item.from(it) }

    // ---------- plex.tv ----------

    suspend fun createPin(): Pin = withContext(Dispatchers.IO) {
        val o = JSONObject(Net.request("POST", "https://plex.tv/api/v2/pins?strong=false", headers(null)))
        Pin(o.getLong("id"), o.getString("code"))
    }

    suspend fun checkPin(pin: Pin): String? = withContext(Dispatchers.IO) {
        val o = JSONObject(Net.request("GET", "https://plex.tv/api/v2/pins/${pin.id}", headers(null)))
        o.str("authToken")?.takeIf { it.isNotBlank() }
    }

    suspend fun servers(): List<Server> = withContext(Dispatchers.IO) {
        val token = prefs.accountToken ?: return@withContext emptyList()
        val arr = JSONArray(
            Net.request("GET", "https://clients.plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", headers(token))
        )
        arr.map { it }.filter { (it.str("provides") ?: "").contains("server") }.map { s ->
            Server(
                name = s.str("name") ?: "Plex",
                id = s.str("clientIdentifier") ?: "",
                accessToken = s.str("accessToken") ?: token,
                connections = s.arr("connections").map {
                    Conn(it.str("uri") ?: "", it.optBoolean("local"), it.optBoolean("relay"))
                }.filter { it.uri.isNotBlank() },
            )
        }
    }

    /** Try connections in parallel, prefer local, then remote, then relay. */
    suspend fun connect(server: Server): String? = coroutineScope {
        val ordered = server.connections.sortedBy { if (it.relay) 2 else if (it.local) 0 else 1 }
        val results = ordered.map { c ->
            async(Dispatchers.IO) {
                runCatching { Net.request("GET", c.uri + "/identity", headers(server.accessToken), 4000); c }.getOrNull()
            }
        }.awaitAll()
        results.firstOrNull { it != null }?.uri
    }

    fun useServer(server: Server, uri: String) {
        prefs.serverName = server.name
        prefs.serverId = server.id
        prefs.serverToken = server.accessToken
        prefs.serverUri = uri
    }

    // ---------- library ----------

    suspend fun sections(): List<Section> =
        get("/library/sections").container().arr("Directory").map {
            Section(it.str("key") ?: "", it.str("title") ?: "", it.str("type") ?: "")
        }.filter { it.type == "show" || it.type == "movie" }

    suspend fun continueWatching(): List<Item> {
        val hub = runCatching { get("/hubs/continueWatching/items?X-Plex-Container-Size=30").items() }.getOrNull()
        if (!hub.isNullOrEmpty()) return hub
        return runCatching { get("/library/onDeck?X-Plex-Container-Size=30").items() }.getOrDefault(emptyList())
    }

    /** Recently added titles in a section, mapped to whole shows (not episodes). */
    suspend fun recentlyAdded(section: Section, limit: Int = 30): List<Item> {
        if (section.type == "movie") {
            return get("/library/sections/${section.key}/all?type=1&sort=addedAt:desc&X-Plex-Container-Size=$limit").items()
        }
        val recent = get("/library/sections/${section.key}/recentlyAdded?X-Plex-Container-Size=300").items()
        val keys = LinkedHashSet<String>()
        for (it in recent) {
            val k = when (it.type) {
                "episode" -> it.showKey
                "season" -> it.showKey
                "show" -> it.ratingKey
                else -> null
            }
            if (k != null) keys += k
            if (keys.size >= limit) break
        }
        if (keys.size < limit) {
            runCatching {
                get("/library/sections/${section.key}/all?type=2&sort=addedAt:desc&X-Plex-Container-Size=$limit").items()
            }.getOrNull()?.forEach { if (keys.size < limit) keys += it.ratingKey }
        }
        return metadata(keys.toList())
    }

    suspend fun metadata(keys: List<String>): List<Item> {
        if (keys.isEmpty()) return emptyList()
        val byKey = keys.chunked(50).flatMap { chunk ->
            get("/library/metadata/${chunk.joinToString(",")}").items()
        }.associateBy { it.ratingKey }
        return keys.mapNotNull { byKey[it] }
    }

    suspend fun item(key: String): Item? = metadata(listOf(key)).firstOrNull()

    suspend fun library(section: Section, sort: String): List<Item> {
        val type = if (section.type == "movie") 1 else 2
        return get("/library/sections/${section.key}/all?type=$type&sort=$sort").items()
    }

    suspend fun children(key: String): List<Item> = get("/library/metadata/$key/children").items()

    suspend fun allEpisodes(showKey: String): List<Item> = get("/library/metadata/$showKey/allLeaves").items()

    suspend fun mediaInfo(key: String): MediaInfo? =
        get("/library/metadata/$key?includeChapters=1&includeMarkers=1").container().arr("Metadata")
            .map { it }.firstOrNull()?.let { MediaInfo.from(it) }

    suspend fun search(query: String): List<Item> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val o = get("/hubs/search?query=${enc(q)}&limit=30&includeCollections=0")
        val found = o.container().arr("Hub").map { it }.flatMap { hub -> hub.arr("Metadata").map { Item.from(it) } }
            .filter { it.type == "show" || it.type == "movie" || it.type == "episode" }
        val seen = HashSet<String>()
        return found.filter { seen.add(it.ratingKey) && seen.add("${it.type}|${it.title}|${it.year}") }
            .sortedBy { if (it.type == "episode") 1 else 0 }
    }

    /** Next episode after [ep] (crosses seasons). */
    suspend fun nextEpisode(ep: Item): Item? {
        val show = ep.showKey ?: return null
        val all = runCatching { allEpisodes(show) }.getOrNull() ?: return null
        val i = all.indexOfFirst { it.ratingKey == ep.ratingKey }
        if (i < 0) return null
        // Skip episodes stored in the same file (a "S01E01-E02" file is two episodes in Plex).
        val file = all[i].partKey ?: ep.partKey
        return all.drop(i + 1).firstOrNull { file == null || it.partKey != file }
    }

    // ---------- subtitle search (Plex's own, backed by OpenSubtitles) ----------

    /** [lang] is a two-letter code ("en"). Best matches first. */
    suspend fun searchSubtitles(ratingKey: String, lang: String, title: String? = null): List<SubResult> {
        val t = title?.takeIf { it.isNotBlank() }?.let { "&title=${enc(it)}" } ?: ""
        val o = get("/library/metadata/$ratingKey/subtitles?language=${enc(lang)}&hearingImpaired=0&forced=0$t")
        return o.container().arr("Stream").map { s ->
            SubResult(
                key = s.str("key") ?: "",
                codec = (s.str("codec") ?: "srt").lowercase(),
                languageCode = s.str("languageCode"),
                name = s.str("title")?.takeIf { it.isNotBlank() } ?: s.str("displayTitle") ?: "Subtitle",
                hearingImpaired = s.flag("hearingImpaired"),
                forced = s.flag("forced"),
                provider = s.str("providerTitle"),
                score = if (s.has("score")) s.optDouble("score") else null,
            )
        }.filter { it.key.isNotBlank() }.sortedByDescending { it.score ?: 0.0 }
    }

    /** Asks the server to download [r] and attach it to the item. */
    suspend fun addSubtitle(ratingKey: String, r: SubResult): Boolean = send(
        "PUT",
        "/library/metadata/$ratingKey/subtitles?key=${enc(r.key)}&codec=${enc(r.codec)}" +
            "&language=${enc(r.languageCode ?: "")}&hearingImpaired=${if (r.hearingImpaired) 1 else 0}" +
            "&forced=${if (r.forced) 1 else 0}&providerTitle=${enc(r.provider ?: "OpenSubtitles")}",
    )

    // ---------- progress ----------

    suspend fun timeline(item: Item, state: String, timeMs: Long, durationMs: Long) = send(
        "GET",
        "/:/timeline?ratingKey=${item.ratingKey}&key=${enc("/library/metadata/${item.ratingKey}")}" +
            "&state=$state&time=$timeMs&duration=$durationMs&identifier=com.plexapp.plugins.library",
    )

    suspend fun markWatched(key: String) =
        send("GET", "/:/scrobble?identifier=com.plexapp.plugins.library&key=$key")

    suspend fun markUnwatched(key: String) =
        send("GET", "/:/unscrobble?identifier=com.plexapp.plugins.library&key=$key")

    // ---------- urls ----------

    /** Small, server-resized image (keeps memory low on the TV). */
    fun image(path: String?, w: Int, h: Int): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) return path
        val base = baseUrl ?: return null
        return "$base/photo/:/transcode?width=$w&height=$h&minSize=1&upscale=1&url=${enc(path)}"
    }

    fun mediaUrl(partKey: String): String = (baseUrl ?: "") + partKey

    fun subtitleUrl(key: String): String = (baseUrl ?: "") + key

    fun hostLabel(): String = baseUrl?.let { Uri.parse(it).host } ?: ""
}
