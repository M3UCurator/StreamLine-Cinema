package com.streamline.cinema.data

import java.net.URLEncoder
import java.util.Locale

/**
 * A chart source answers with metadata only. Playback leaves the app: a row that
 * carries a video id opens the official app, every other row opens a search for
 * it. Nothing here streams audio, so these sources can sit beside the addon
 * catalogs without the app becoming a content source.
 *
 * The capability flags are the contract the UI obeys: a source that cannot
 * honour a dimension must not show a control for it. Deezer follows the
 * requester's country with no parameter to override it, and the YouTube Data API
 * publishes a single Music category rather than music genres.
 */
enum class ChartSource(
    val label: String,
    val requiresKey: Boolean,
    val supportsCountry: Boolean,
    val supportsGenre: Boolean,
    val caveat: String? = null
) {
    APPLE(
        label = "APPLE MUSIC",
        requiresKey = false,
        supportsCountry = true,
        supportsGenre = true
    ),
    DEEZER(
        label = "DEEZER",
        requiresKey = false,
        supportsCountry = false,
        supportsGenre = true,
        caveat = "Deezer charts follow this device's country; there is no country parameter."
    ),
    YOUTUBE(
        label = "YOUTUBE",
        requiresKey = true,
        supportsCountry = true,
        supportsGenre = false,
        caveat = "The Data API publishes one Music category, not music genres."
    );
}

data class ChartCountry(val code: String, val name: String)

/**
 * Apple storefronts and YouTube `regionCode` values are both ISO 3166-1
 * alpha-2, so one catalogue serves every country-aware source.
 */
object ChartCountries {
    private const val FALLBACK_CODE = "US"

    val all: List<ChartCountry> = listOf(
        ChartCountry("AE", "United Arab Emirates"),
        ChartCountry("AR", "Argentina"),
        ChartCountry("AT", "Austria"),
        ChartCountry("AU", "Australia"),
        ChartCountry("BE", "Belgium"),
        ChartCountry("BR", "Brazil"),
        ChartCountry("CA", "Canada"),
        ChartCountry("CH", "Switzerland"),
        ChartCountry("CL", "Chile"),
        ChartCountry("CN", "China"),
        ChartCountry("CO", "Colombia"),
        ChartCountry("CZ", "Czechia"),
        ChartCountry("DE", "Germany"),
        ChartCountry("DK", "Denmark"),
        ChartCountry("EG", "Egypt"),
        ChartCountry("ES", "Spain"),
        ChartCountry("FI", "Finland"),
        ChartCountry("FR", "France"),
        ChartCountry("GB", "United Kingdom"),
        ChartCountry("GH", "Ghana"),
        ChartCountry("GR", "Greece"),
        ChartCountry("HK", "Hong Kong"),
        ChartCountry("ID", "Indonesia"),
        ChartCountry("IE", "Ireland"),
        ChartCountry("IL", "Israel"),
        ChartCountry("IN", "India"),
        ChartCountry("IT", "Italy"),
        ChartCountry("JP", "Japan"),
        ChartCountry("KE", "Kenya"),
        ChartCountry("KR", "South Korea"),
        ChartCountry("MA", "Morocco"),
        ChartCountry("MX", "Mexico"),
        ChartCountry("MY", "Malaysia"),
        ChartCountry("NG", "Nigeria"),
        ChartCountry("NL", "Netherlands"),
        ChartCountry("NO", "Norway"),
        ChartCountry("NZ", "New Zealand"),
        ChartCountry("PH", "Philippines"),
        ChartCountry("PK", "Pakistan"),
        ChartCountry("PL", "Poland"),
        ChartCountry("PT", "Portugal"),
        ChartCountry("SA", "Saudi Arabia"),
        ChartCountry("SE", "Sweden"),
        ChartCountry("SG", "Singapore"),
        ChartCountry("TH", "Thailand"),
        ChartCountry("TR", "Türkiye"),
        ChartCountry("US", "United States"),
        ChartCountry("VN", "Vietnam"),
        ChartCountry("ZA", "South Africa")
    ).sortedBy { it.name }

    private val byCode = all.associateBy(ChartCountry::code)

    fun find(code: String?): ChartCountry? =
        byCode[code?.trim()?.uppercase(Locale.ROOT).orEmpty()]

    /** The device's own storefront when it is one the app offers, else US. */
    fun defaultFor(locale: Locale = Locale.getDefault()): ChartCountry =
        find(locale.country) ?: find(FALLBACK_CODE) ?: all.first()
}

internal const val ANY_GENRE_ID = "any"

/**
 * Catalogues spell the same genre differently ("Hip-Hop/Rap" on Apple,
 * "Rap/Hip Hop" on Deezer), so a label matches when its words match regardless
 * of order or punctuation. [id] is the app's stable key, not a provider id.
 */
data class ChartGenre(
    val id: String,
    val label: String,
    private val known: List<String> = emptyList()
) {
    private val keys: Set<Set<String>> = known.map(::genreWords).toSet()

    fun matches(candidate: String): Boolean =
        id == ANY_GENRE_ID || genreWords(candidate) in keys

    /** A row can carry several catalogue labels; any one of them may match. */
    fun matchesAny(candidates: List<String>): Boolean =
        id == ANY_GENRE_ID || candidates.any(::matches)
}

private fun genreWords(name: String): Set<String> =
    name.lowercase(Locale.ROOT)
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotEmpty() }
        .toSet()

object ChartGenres {
    /** The one genre every source can honour. */
    val any = ChartGenre(ANY_GENRE_ID, "All")

    val all: List<ChartGenre> = listOf(any) + listOf(
        ChartGenre("afrobeats", "Afrobeats", listOf("Afrobeats", "Afro-Pop", "African Music", "African")),
        ChartGenre("alternative", "Alternative", listOf("Alternative", "Alternative Rock")),
        ChartGenre("classical", "Classical", listOf("Classical", "Classical Music")),
        ChartGenre("country", "Country", listOf("Country")),
        ChartGenre("dance", "Dance/Electronic", listOf("Dance", "Electronic", "Electro", "Electronic/Dance")),
        ChartGenre("gospel", "Gospel", listOf("Gospel", "Christian", "Christian & Gospel")),
        ChartGenre("hiphop", "Hip-Hop/Rap", listOf("Hip-Hop/Rap", "Rap/Hip Hop", "Hip Hop", "Rap")),
        ChartGenre("indian", "Indian", listOf("Indian", "Bollywood", "Filmi")),
        ChartGenre("jazz", "Jazz", listOf("Jazz")),
        ChartGenre("kpop", "K-Pop", listOf("K-Pop", "Korean")),
        ChartGenre("latin", "Latin", listOf("Latin", "Latin Music", "Reggaeton")),
        ChartGenre("metal", "Metal", listOf("Metal", "Heavy Metal")),
        ChartGenre("pop", "Pop", listOf("Pop")),
        ChartGenre("reggae", "Reggae", listOf("Reggae", "Reggae/Dancehall")),
        ChartGenre("rnb", "R&B/Soul", listOf("R&B/Soul", "R&B", "Soul & Funk", "Soul")),
        ChartGenre("rock", "Rock", listOf("Rock"))
    ).sortedBy { it.label }

    fun find(id: String?): ChartGenre? = all.firstOrNull { it.id == id }
}

data class ChartRequest(
    val source: ChartSource,
    val country: ChartCountry,
    val genre: ChartGenre = ChartGenres.any
) {
    /** The header line; dimensions the source cannot honour are left out. */
    fun describe(): String {
        val parts = mutableListOf(source.label)
        parts += if (source.supportsCountry) country.name else "Global"
        if (source.supportsGenre) parts += genre.label
        return parts.joinToString(" · ")
    }
}

/** Turns the persisted strings back into catalogue entries, with safe defaults. */
object ChartSelection {

    fun source(name: String?): ChartSource =
        ChartSource.entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
            ?: ChartSource.APPLE

    fun country(code: String?): ChartCountry =
        ChartCountries.find(code) ?: ChartCountries.defaultFor()

    /** Stored as `""` when the viewer never picked one. */
    fun genre(id: String?): ChartGenre =
        ChartGenres.find(id) ?: ChartGenres.any

    /** A keyed source the app cannot call yet is not offered at all. */
    fun available(youtubeKey: String): List<ChartSource> =
        ChartSource.entries.filter { !it.requiresKey || youtubeKey.isNotBlank() }

    /** Falls back to Apple when the saved source is YouTube and its key is gone. */
    fun request(
        sourceName: String?,
        countryCode: String?,
        genreId: String?,
        youtubeKey: String
    ): ChartRequest {
        val picked = source(sourceName)
        val usable = if (picked.requiresKey && youtubeKey.isBlank()) ChartSource.APPLE else picked
        return ChartRequest(usable, country(countryCode), genre(genreId))
    }
}

/** A row of a chart. [videoId] is set only when the source can deep-link it. */
data class ChartTrack(
    val source: ChartSource,
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val artwork: String? = null,
    val durationMs: Long = 0L,
    val videoId: String? = null,
    val openUrl: String? = null,
    val genreLabel: String? = null
)

data class ChartResult(
    val request: ChartRequest,
    val rows: List<ChartTrack>,
    /** A caveat the line under the band should show, e.g. Deezer's IP-based country. */
    val note: String? = null
)

interface ChartProvider {
    val source: ChartSource

    /** Throws IOException when the source refuses; an empty list is a valid answer. */
    suspend fun chart(request: ChartRequest, limit: Int = MAX_CHART_ROWS): ChartResult
}

/**
 * Optional capability beside [ChartProvider]: a source that can look a title up
 * by name. Kept separate because search costs quota that a chart call does not.
 */
interface ChartSearchProvider {
    suspend fun search(query: String, country: ChartCountry, limit: Int = MAX_CHART_ROWS): List<ChartTrack>
}

/**
 * Playback never starts here. A row with a video id hands off to the official
 * YouTube app through its documented `vnd.youtube:` intent; anything else opens
 * a YouTube search in the browser. No undocumented URI schemes are used.
 */
object ChartHandoff {
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")

    fun validVideoId(raw: String?): String? =
        raw?.trim()?.takeIf { videoIdPattern.matches(it) }

    /** Android TV resolves `vnd.youtube:<id>` to the installed YouTube app. */
    fun watchUri(videoId: String): String = "vnd.youtube:$videoId"

    fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

    /** Artist first narrows an official video search better than a bare title. */
    fun queryFor(track: ChartTrack): String =
        listOf(track.artist, track.title).map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(" ")

    fun searchUrl(query: String): String =
        "https://www.youtube.com/results?search_query=" + encode(query)

    /** The row's best destination; null when it carries nothing to open. */
    fun openTarget(track: ChartTrack): String? {
        validVideoId(track.videoId)?.let { return watchUri(it) }
        return track.openUrl?.takeIf { it.isNotBlank() }
    }

    internal fun encode(value: String): String =
        runCatching { URLEncoder.encode(value, "UTF-8") }.getOrDefault(value)
}

/** The Data API's videos.list cap per page, so every source returns the same size. */
const val MAX_CHART_ROWS = 50

/**
 * Six-hour chart cache. Charts move slowly, and a refresh is a request the
 * source may ration (the YouTube key has a daily quota), so re-selecting the
 * same pills must not hit the network again.
 */
class ChartCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val capacity: Int = 12,
    private val now: () -> Long = System::currentTimeMillis
) {
    private data class Entry(val result: ChartResult, val storedAt: Long)

    private val entries = object : LinkedHashMap<String, Entry>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > capacity
    }

    @Synchronized
    fun get(key: String): ChartResult? {
        val entry = entries[key] ?: return null
        if (now() - entry.storedAt >= ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.result
    }

    @Synchronized
    fun put(key: String, result: ChartResult) {
        entries[key] = Entry(result, now())
    }

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        const val DEFAULT_TTL_MS = 6L * 60 * 60 * 1000
    }
}
