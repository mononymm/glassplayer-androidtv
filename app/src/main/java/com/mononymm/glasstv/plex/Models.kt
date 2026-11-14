package com.mononymm.glasstv.plex

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
internal fun JSONObject.int(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
internal fun JSONObject.long(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
internal fun JSONObject.arr(k: String): JSONArray = optJSONArray(k) ?: JSONArray()
internal inline fun <T> JSONArray.map(f: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { optJSONObject(it) }.map(f)

data class Section(val key: String, val title: String, val type: String)

data class Server(
    val name: String,
    val id: String,
    val accessToken: String,
    val connections: List<Conn>,
)

data class Conn(val uri: String, val local: Boolean, val relay: Boolean)

data class Item(
    val ratingKey: String,
    val type: String,
    val title: String,
    val showTitle: String? = null,
    val seasonIndex: Int? = null,
    val index: Int? = null,
    val year: Int? = null,
    val summary: String? = null,
    val thumb: String? = null,
    val art: String? = null,
    val showThumb: String? = null,
    val showArt: String? = null,
    val seasonThumb: String? = null,
    val logo: String? = null,
    val duration: Long = 0,
    val viewOffset: Long = 0,
    val viewCount: Int = 0,
    val leafCount: Int = 0,
    val viewedLeafCount: Int = 0,
    val sectionId: String? = null,
    val showKey: String? = null,
    val seasonKey: String? = null,
    val contentRating: String? = null,
    val rating: Double? = null,
    val addedAt: Long = 0,
    val lastViewedAt: Long = 0,
    val aired: String? = null,
) {
    val isEpisode get() = type == "episode"
    val isShow get() = type == "show"
    val isSeason get() = type == "season"
    val isMovie get() = type == "movie"
    val playable get() = isEpisode || isMovie || type == "clip"

    val watched: Boolean
        get() = when {
            isShow || isSeason -> leafCount > 0 && viewedLeafCount >= leafCount
            else -> viewCount > 0 && viewOffset == 0L
        }

    val progress: Float get() = if (duration > 0) (viewOffset.toFloat() / duration).coerceIn(0f, 1f) else 0f

    /** "S2 · E5" style tag for episodes. */
    val episodeTag: String?
        get() = if (isEpisode && seasonIndex != null && index != null) "S$seasonIndex · E$index" else null

    val backdrop: String? get() = art ?: showArt
    val poster: String? get() = if (isEpisode) (showThumb ?: seasonThumb ?: thumb) else thumb

    companion object {
        fun from(o: JSONObject): Item {
            val images = o.arr("Image").map { it }
            val logo = images.firstOrNull { it.str("type") == "clearLogo" }?.str("url")
            return Item(
                ratingKey = o.str("ratingKey") ?: "",
                type = o.str("type") ?: "",
                title = o.str("title") ?: "",
                showTitle = o.str("grandparentTitle") ?: if (o.str("type") == "season") o.str("parentTitle") else null,
                seasonIndex = o.int("parentIndex"),
                index = o.int("index"),
                year = o.int("year"),
                summary = o.str("summary"),
                thumb = o.str("thumb"),
                art = o.str("art"),
                showThumb = o.str("grandparentThumb") ?: if (o.str("type") == "season") o.str("parentThumb") else null,
                showArt = o.str("grandparentArt"),
                seasonThumb = o.str("parentThumb"),
                logo = logo,
                duration = o.long("duration") ?: 0,
                viewOffset = o.long("viewOffset") ?: 0,
                viewCount = o.int("viewCount") ?: 0,
                leafCount = o.int("leafCount") ?: 0,
                viewedLeafCount = o.int("viewedLeafCount") ?: 0,
                sectionId = o.str("librarySectionID"),
                showKey = o.str("grandparentRatingKey") ?: if (o.str("type") == "season") o.str("parentRatingKey") else null,
                seasonKey = o.str("parentRatingKey"),
                contentRating = o.str("contentRating"),
                rating = if (o.has("audienceRating")) o.optDouble("audienceRating") else if (o.has("rating")) o.optDouble("rating") else null,
                addedAt = o.long("addedAt") ?: 0,
                lastViewedAt = o.long("lastViewedAt") ?: 0,
                aired = o.str("originallyAvailableAt"),
            )
        }
    }
}

data class SubTrack(
    val id: Long,
    val codec: String,
    val language: String?,
    val title: String,
    val external: Boolean,
    val key: String?,
    val forced: Boolean,
    val selected: Boolean,
) {
    val styled get() = codec == "ass" || codec == "ssa"
}

data class Chapter(val startMs: Long, val title: String?, val thumb: String? = null)

/** Everything needed to decide which engine plays a file and to start it. */
data class MediaInfo(
    val item: Item,
    val partKey: String,
    val container: String?,
    val videoCodec: String?,
    val width: Int,
    val height: Int,
    val frameRate: Float?,
    val dolbyVision: Boolean,
    val dvProfile: Int?,
    val hdr: Boolean,
    val audioCodec: String?,
    val subs: List<SubTrack>,
    val chapters: List<Chapter>,
    val bitrateKbps: Int?,
    val sizeBytes: Long? = null,
    val audioChannels: Int? = null,
) {
    val hasStyledSubs get() = subs.any { it.styled }

    val summary: String
        get() = buildList {
            if (height > 0) add(if (height >= 2000) "4K" else "${height}p")
            videoCodec?.let { add(it.uppercase()) }
            if (dolbyVision) add("Dolby Vision" + (dvProfile?.let { " P$it" } ?: "")) else if (hdr) add("HDR")
            audioCodec?.let { add(it.uppercase()) }
            bitrateKbps?.let { add(if (it >= 1000) "%.1f Mbps".format(it / 1000f) else "$it kbps") }
        }.joinToString("  ·  ")

    companion object {
        fun from(o: JSONObject): MediaInfo? {
            val item = Item.from(o)
            val media = o.arr("Media").map { it }.firstOrNull() ?: return null
            val part = media.arr("Part").map { it }.firstOrNull() ?: return null
            val streams = part.arr("Stream").map { it }
            val video = streams.firstOrNull { it.int("streamType") == 1 }
            val audio = streams.firstOrNull { it.int("streamType") == 2 && it.optBoolean("selected") }
                ?: streams.firstOrNull { it.int("streamType") == 2 }
            val trc = video?.str("colorTrc") ?: ""
            val dv = video?.optBoolean("DOVIPresent") == true || (video?.str("DOVIProfile") != null)
            val subs = streams.filter { it.int("streamType") == 3 }.map {
                SubTrack(
                    id = it.long("id") ?: 0,
                    codec = (it.str("codec") ?: "").lowercase(),
                    language = it.str("languageCode"),
                    title = it.str("extendedDisplayTitle") ?: it.str("displayTitle") ?: it.str("title") ?: "Subtitle",
                    external = it.str("key") != null,
                    key = it.str("key"),
                    forced = it.optBoolean("forced"),
                    selected = it.optBoolean("selected"),
                )
            }
            val chapters = o.arr("Chapter").map {
                Chapter(it.long("startTimeOffset") ?: 0, it.str("tag"), it.str("thumb"))
            }.sortedBy { it.startMs }
            return MediaInfo(
                item = item,
                partKey = part.str("key") ?: return null,
                container = part.str("container") ?: media.str("container"),
                videoCodec = video?.str("codec") ?: media.str("videoCodec"),
                width = media.int("width") ?: video?.int("width") ?: 0,
                height = media.int("height") ?: video?.int("height") ?: 0,
                frameRate = video?.let { if (it.has("frameRate")) it.optDouble("frameRate").toFloat() else null },
                dolbyVision = dv,
                dvProfile = video?.int("DOVIProfile"),
                hdr = dv || trc == "smpte2084" || trc == "arib-std-b67",
                audioCodec = audio?.str("codec") ?: media.str("audioCodec"),
                subs = subs,
                chapters = chapters,
                bitrateKbps = media.int("bitrate"),
                sizeBytes = part.long("size"),
                audioChannels = audio?.int("channels") ?: media.int("audioChannels"),
            )
        }
    }
}
