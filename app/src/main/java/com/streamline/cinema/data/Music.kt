package com.streamline.cinema.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Music addons declare the `music` catalog type. Name heuristics are deliberately
 * absent: a live addon's "Music" channel catalog is still a live catalog.
 */
val AddonCatalog.isMusicCatalog: Boolean
    get() = type.trim().equals(MUSIC_TYPE, ignoreCase = true)

/** Focused manifests also identify themselves with a top-level `contentType`. */
val AddonManifest.declaresMusic: Boolean
    get() = contentType?.trim()?.equals(MUSIC_TYPE, ignoreCase = true) == true

/** Catalog types that carry music rows in manifests written for a bespoke client. */
private val MUSIC_CONTENT_TYPES = setOf("music", "track", "album", "artist", "playlist")

/** A group that lets a catalog answer a free-text query. */
val AddonCatalog.declaresSearch: Boolean
    get() = extras.any { it.name.equals("search", ignoreCase = true) }

/**
 * Music catalogs of a manifest, in manifest order. A manifest that calls itself
 * a music addon also contributes its music-shaped catalogs (`artist`, `playlist`, …)
 * and any catalog that declares search, because those manifests publish their
 * index that way instead of using the `music` type.
 */
val AddonManifest.musicCatalogs: List<AddonCatalog>
    get() {
        if (!declaresMusic) return catalogs.filter { it.isMusicCatalog }
        return catalogs.filter { catalog ->
            catalog.isMusicCatalog ||
                catalog.type.trim().lowercase(Locale.ROOT) in MUSIC_CONTENT_TYPES ||
                catalog.declaresSearch
        }
    }

data class MusicCatalog(
    val addonName: String,
    val addonUrl: String,
    val id: String,
    val name: String,
    val type: String,
    val extras: List<CatalogExtra> = emptyList(),
    /** Stream ids the addon declares it can serve (`manifest.idPrefixes`). */
    val idPrefixes: List<String> = emptyList()
) {
    val key: String get() = "$addonUrl|$type|$id"

    /** Groups the catalog can be filtered by, with `search` excluded from chips. */
    val selectableExtras: List<CatalogExtra> get() = extras.filter { it.isSelectable }

    /** Only catalogs that declare a `search` extra can answer a free-text query. */
    val supportsSearch: Boolean
        get() = extras.any { it.name.equals("search", ignoreCase = true) }
}

data class MusicCatalogLoad(
    val catalogs: List<MusicCatalog>,
    val failures: List<String> = emptyList()
)

data class MusicSearchResult(
    val tracks: List<MusicTrack>,
    val failures: List<String> = emptyList()
)

data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val artwork: String? = null,
    val addonName: String = "",
    val addonUrl: String = "",
    val type: String = "",
    /**
     * Coordinates of the addon's stream resource, which need not match the row's
     * label: a manifest that labels its rows `track` may stream them from `movie`.
     */
    val streamType: String = type,
    val streamId: String = id
) {
    val key: String get() = "$addonUrl|$id"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("durationMs", durationMs)
        artwork?.let { put("artwork", it) }
        put("addonName", addonName)
        put("addonUrl", addonUrl)
        put("type", type)
        put("streamType", streamType)
        put("streamId", streamId)
    }

    companion object {
        /** Reads the form [toJson] writes; an entry without id or title is junk. */
        fun fromJson(o: JSONObject): MusicTrack? {
            val id = o.stringOrNull("id") ?: return null
            val title = o.stringOrNull("title") ?: return null
            val type = o.optString("type")
            return MusicTrack(
                id = id,
                title = title,
                artist = o.optString("artist"),
                album = o.optString("album"),
                durationMs = o.optLong("durationMs").coerceAtLeast(0L),
                artwork = o.stringOrNull("artwork")?.takeIf { it.startsWith("http") },
                addonName = o.optString("addonName"),
                addonUrl = o.optString("addonUrl"),
                type = type,
                streamType = o.optString("streamType").ifBlank { type },
                streamId = o.optString("streamId").ifBlank { id }
            )
        }
    }
}

/**
 * Meta shape of a music catalog entry. [MusicTrack.id] doubles as the video id
 * SponsorBlock is queried with; `duration` is seconds, while `runtime` follows
 * the Stremio convention and treats a bare number as minutes.
 */
internal object MusicMeta {

    internal fun parseCatalog(
        body: String,
        addonUrl: String,
        addonName: String,
        type: String,
        idPrefixes: List<String> = emptyList()
    ): List<MusicTrack> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        // Standard manifests answer with `metas`; music manifests written for a
        // bespoke client publish their own track objects under `items`.
        val entries = root.optJSONArray("metas") ?: root.optJSONArray("items")
            ?: return emptyList()
        val count = entries.length().coerceAtMost(MAX_TRACKS)
        return (0 until count)
            .mapNotNull { index ->
                entries.optJSONObject(index)?.let {
                    track(it, addonUrl, addonName, type, idPrefixes)
                }
            }
            .distinctBy { it.id }
    }

    internal fun track(
        entry: JSONObject,
        addonUrl: String,
        addonName: String,
        type: String,
        idPrefixes: List<String> = emptyList()
    ): MusicTrack? {
        val id = entry.stringOrNull("id") ?: return null
        val title = entry.stringOrNull("name") ?: entry.stringOrNull("title") ?: return null
        val entryType = entry.stringOrNull("type")?.trim()?.takeIf { it.isNotEmpty() } ?: type
        val (streamType, streamId) = streamIdentity(entryType, type, id, idPrefixes)
        return MusicTrack(
            id = id,
            title = title,
            artist = artistOf(entry),
            album = entry.stringOrNull("album").orEmpty(),
            durationMs = durationMs(entry),
            artwork = artworkOf(entry),
            addonName = addonName,
            addonUrl = addonUrl,
            type = entryType,
            streamType = streamType,
            streamId = streamId
        )
    }

    /**
     * Where a row streams from. A `music` catalog answers `/stream/music/{id}`.
     * Manifests that label their rows `track` (or another music type) publish
     * the stream from their video namespace and expect the manifest's declared
     * id prefix, so rows in any other catalog use `movie` + prefix.
     */
    private fun streamIdentity(
        entryType: String,
        catalogType: String,
        id: String,
        idPrefixes: List<String>
    ): Pair<String, String> {
        if (catalogType.trim().equals(MUSIC_TYPE, ignoreCase = true)) return entryType to id
        val prefixed = if (idPrefixes.isEmpty() || idPrefixes.any { id.startsWith(it) }) {
            id
        } else {
            idPrefixes.first() + id
        }
        val streamType = if (entryType.trim().equals(MUSIC_TYPE, ignoreCase = true)) {
            entryType
        } else {
            "movie"
        }
        return streamType to prefixed
    }

    internal fun durationMs(entry: JSONObject): Long {
        val explicitMs = entry.optLong("durationMs", 0L)
        if (explicitMs > 0L) return explicitMs
        val explicit = entry.opt("duration").takeUnless { it == null || it == JSONObject.NULL }
        if (explicit != null) {
            val ms = parseDurationMs(explicit.toString(), bareUnitSeconds = 1L)
            if (ms > 0L) return ms
        }
        val runtime = entry.opt("runtime").takeUnless { it == null || it == JSONObject.NULL }
            ?: return 0L
        return parseDurationMs(runtime.toString(), bareUnitSeconds = 60L)
    }

    private fun parseDurationMs(text: String, bareUnitSeconds: Long): Long {
        val clean = text.trim().lowercase()
        if (clean.isEmpty()) return 0L
        val seconds: Double = if (clean.contains(':')) {
            val parts = clean.split(':')
            if (parts.size !in 2..3) return 0L
            val values = parts.map { it.trim().toLongOrNull() ?: return 0L }
            if (values.size == 2) {
                (values[0] * 60 + values[1]).toDouble()
            } else {
                (values[0] * 3600 + values[1] * 60 + values[2]).toDouble()
            }
        } else {
            val match = NUMBER_UNIT.find(clean) ?: return 0L
            val value = match.groupValues[1].toDoubleOrNull() ?: return 0L
            when (match.groupValues[2]) {
                "" -> value * bareUnitSeconds.toDouble()
                "s", "sec", "secs", "second", "seconds" -> value
                "m", "min", "mins", "minute", "minutes" -> value * 60
                "h", "hr", "hrs", "hour", "hours" -> value * 3600
                else -> return 0L
            }
        }
        if (seconds <= 0.0 || seconds > MAX_TRACK_SECONDS) return 0L
        return (seconds * 1000.0).toLong()
    }

    private fun artistOf(entry: JSONObject): String =
        artists(entry.opt("artist"))
            .ifBlank { artists(entry.opt("artists")) }
            .ifBlank { entry.stringOrNull("performer").orEmpty() }

    private fun artists(value: Any?): String = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { index ->
            when (val item = value.opt(index)) {
                is String -> item.trim().takeIf { it.isNotEmpty() }
                is JSONObject -> item.stringOrNull("name")
                else -> null
            }
        }.joinToString(", ")
        is String -> value.trim()
        else -> ""
    }

    private fun artworkOf(entry: JSONObject): String? =
        listOf("poster", "background", "logo", "artworkURL")
            .firstNotNullOfOrNull { key ->
                entry.stringOrNull(key)?.takeIf { it.startsWith("http") }
            }

    private val NUMBER_UNIT = Regex("""^(\d+(?:\.\d+)?)\s*([a-z]*)$""")
}

private const val MUSIC_TYPE = "music"
private const val MAX_TRACKS = 500

/** A merged multi-catalog search result never needs to render more rows. */
internal const val MAX_MUSIC_SEARCH_RESULTS = 200
private const val MAX_TRACK_SECONDS = 24L * 60 * 60
