package com.streamline.cinema.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class MediaType {
    MOVIE, TV;

    companion object {
        fun from(raw: String?): MediaType =
            if (raw.equals("tv", ignoreCase = true) || raw.equals("series", ignoreCase = true)) TV else MOVIE
    }
}

/** Stable key for an episode the user marked watched without playing it. */
fun watchedEpisodeKey(itemKey: String, season: Int, episode: Int): String = "$itemKey|$season|$episode"

enum class SortMode(val label: String) {
    RECENT("Recently added"),
    TITLE("A – Z"),
    RATING("Rating"),
    YEAR("Release year");

    companion object {
        fun from(raw: String?): SortMode = entries.firstOrNull { it.name == raw } ?: RECENT
    }
}

/**
 * What the content-language preference does to shelves and search results.
 * PREFER keeps every title and only moves matches up; ONLY filters out the
 * rest, which can leave a small language with an empty shelf.
 */
enum class LanguageRule(val label: String) {
    PREFER("Prefer"),
    ONLY("Only");

    companion object {
        fun from(raw: String?): LanguageRule = entries.firstOrNull { it.name == raw } ?: PREFER
    }
}

/** Content languages offered by Smart Search & Play; blank keeps the app default. */
enum class ContentLanguage(val label: String, val code: String) {
    APP_DEFAULT("App default (English)", ""),
    ENGLISH("English", "en"),
    HINDI("Hindi", "hi"),
    NEPALI("Nepali", "ne"),
    SPANISH("Spanish", "es"),
    FRENCH("French", "fr"),
    GERMAN("German", "de"),
    PORTUGUESE("Portuguese", "pt"),
    ARABIC("Arabic", "ar"),
    JAPANESE("Japanese", "ja"),
    KOREAN("Korean", "ko"),
    RUSSIAN("Russian", "ru");

    companion object {
        fun from(code: String?): ContentLanguage = entries.firstOrNull { it.code == code } ?: APP_DEFAULT
    }
}

/** Origin countries offered by Smart Search & Play; blank keeps any country. */
enum class ContentCountry(val label: String, val code: String) {
    ANY("Any country", ""),
    UNITED_STATES("United States", "US"),
    INDIA("India", "IN"),
    UNITED_KINGDOM("United Kingdom", "GB"),
    CANADA("Canada", "CA"),
    AUSTRALIA("Australia", "AU"),
    FRANCE("France", "FR"),
    GERMANY("Germany", "DE"),
    SPAIN("Spain", "ES"),
    BRAZIL("Brazil", "BR"),
    JAPAN("Japan", "JP"),
    SOUTH_KOREA("South Korea", "KR"),
    NEPAL("Nepal", "NP");

    companion object {
        fun from(code: String?): ContentCountry = entries.firstOrNull { it.code == code } ?: ANY
    }
}

enum class PosterOrientation(val label: String) {
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape");

    companion object {
        fun from(raw: String?): PosterOrientation =
            entries.firstOrNull { it.name == raw } ?: PORTRAIT
    }
}

enum class PosterSize(val label: String) {
    SMALL("Small"),
    MEDIUM("Medium"),
    LARGE("Large");

    companion object {
        fun from(raw: String?): PosterSize = entries.firstOrNull { it.name == raw } ?: MEDIUM
    }
}

/**
 * VLC and MPV are additional native engines; AUTO, BUILT_IN and EXTERNAL keep
 * their previous meanings so persisted settings resolve unchanged.
 */
enum class PlaybackEngine { AUTO, BUILT_IN, EXTERNAL, VLC, MPV }

/**
 * Hides stream sources at or above the selected tier. OFF plays everything;
 * Q4K hides 4K and 8K, Q1080 hides 1080p and above, and so on.
 */
enum class QualityLimit(val label: String, val cutoffHeight: Int) {
    OFF("NO LIMIT", 0),
    Q4K("4K", 2160),
    Q1080("1080p", 1080),
    Q720("720p", 720),
    Q480("480p", 480);

    /** True when a rendition of [height] must not be played at this setting. */
    fun excludes(height: Int): Boolean = cutoffHeight > 0 && height >= cutoffHeight

    companion object {
        fun from(raw: String?): QualityLimit = entries.firstOrNull { it.name == raw } ?: OFF
    }
}

/**
 * Hard ceiling for the video track, independent of the source-quality limit.
 * AUTO leaves ABR free; the tiers cap the rendition bitrate so a weak or
 * metered line never receives a burst it cannot drain.
 */
enum class BandwidthCap(
    val label: String,
    val detail: String,
    val bitsPerSecond: Int,
    val relative: String
) {
    AUTO("AUTO", "No bitrate cap: ABR uses every rendition the line can hold.", 0, ""),
    MBPS2("2", "Caps video at 2 Mbps — roughly 480p on a congested or metered link.", 2_000_000, "480p"),
    MBPS5("5", "Caps video at 5 Mbps — 720p, with ABR free to step further down.", 5_000_000, "720p"),
    MBPS10("10", "Caps video at 10 Mbps — 1080p with headroom for segment bursts.", 10_000_000, "1080p"),
    MBPS20("20", "Caps video at 20 Mbps — 4K without chasing a 40 Mbps peak.", 20_000_000, "4K");

    /** Chip and summary text, e.g. "5 MBPS · 720p"; AUTO has no quality hint. */
    val display: String get() = if (relative.isEmpty()) label else "$label MBPS · $relative"

    companion object {
        fun from(raw: String?): BandwidthCap = entries.firstOrNull { it.name == raw } ?: AUTO
    }
}

/**
 * Forward-buffer window. BALANCED reproduces the tuned defaults; LEAN keeps a
 * short window for frozen lines and low-RAM boxes, GENEROUS prefetches further
 * ahead when the link is stable but the CDN is slow to start a segment.
 */
enum class BufferTier(val label: String, val detail: String) {
    LEAN("LEAN", "Small forward window: lowest RAM and fastest reaction to a broken link."),
    BALANCED("BALANCED", "Tuned default: enough lookahead to ride out short dips."),
    GENEROUS("GENEROUS", "Large forward window: preloads further ahead on stable links.");

    companion object {
        fun from(raw: String?): BufferTier = entries.firstOrNull { it.name == raw } ?: BALANCED
    }
}

enum class DecoderType(val label: String, val detail: String) {
    AUTO("AUTO", "Android picks the decoder, with automatic software fallback"),
    HW("HW", "Standard MediaCodec hardware decoding"),
    HW_PLUS("HW+", "Aggressive hardware-first decoding with automatic software fallback"),
    SW("SW", "Software MediaCodec decoding for stubborn files");

    companion object {
        fun from(raw: String?): DecoderType = entries.firstOrNull { it.name == raw } ?: AUTO
    }
}

enum class PlayerUaPreset(val label: String, val userAgent: String) {
    DEFAULT("Stock browser", ""),
    TIVIMATE("TiviMate 5.0.4", "TiviMate/5.0.4 (Linux; Android 12) Android TV"),
    OTT_NAVIGATOR("OTT Navigator 1.6.8.5", "OTT Navigator/1.6.8.5 (Linux; Android 11) Android TV"),
    IPTV_SMARTERS("IPTVSmartersPro 3.1.5", "IPTVSmartersPro/3.1.5 (Linux; Android 9)"),
    WINK("Wink 1.31.1", "Wink/1.31.1 (Linux; Android 10)"),
    VLC("LibVLC 3.5.4", "LibVLC/3.5.4 (LIVE555 Streaming Media v2020.06.22)");

    companion object {
        fun from(raw: String?): PlayerUaPreset = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

enum class DnsMode(val label: String, val servers: List<String>) {
    SYSTEM("System", emptyList()),
    CLOUDFLARE("Cloudflare", listOf("https://cloudflare-dns.com/dns-query")),
    GOOGLE("Google", listOf("https://dns.google/dns-query")),
    QUAD9("Quad9", listOf("https://dns.quad9.net/dns-query")),
    ADGUARD("AdGuard", listOf("https://dns.adguard-dns.com/dns-query")),
    CUSTOM("Custom", emptyList());

    val isDoh: Boolean get() = servers.any { it.startsWith("http") }

    companion object {
        fun from(raw: String?): DnsMode = entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}


object TmdbImages {
    private const val BASE = "https://image.tmdb.org/t/p"

    fun poster(pathOrUrl: String?): String? = absolute(pathOrUrl, "w500")
    fun backdrop(pathOrUrl: String?): String? = absolute(pathOrUrl, "w1280")
    fun still(pathOrUrl: String?): String? = absolute(pathOrUrl, "w300")
    // Logos render as large as 560 dp in the hero; TMDB's rendition ladder stops
    // at w500, which upsamples there, so ask for the source artwork instead.
    fun logo(pathOrUrl: String?): String? = absolute(pathOrUrl, "original")

    private fun absolute(pathOrUrl: String?, size: String): String? {
        if (pathOrUrl.isNullOrBlank()) return null
        return if (pathOrUrl.startsWith("http")) pathOrUrl
        else "$BASE/$size/${pathOrUrl.removePrefix("/")}"
    }
}

data class Episode(
    val number: Int,
    val title: String = "",
    val overview: String = "",
    val stillUrl: String? = null,
    val airDate: String? = null,
    val runtimeMinutes: Int? = null,
    val streamUrl: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("number", number)
        put("title", title)
        put("overview", overview)
        put("stillUrl", stillUrl ?: JSONObject.NULL)
        put("airDate", airDate ?: JSONObject.NULL)
        put("runtimeMinutes", runtimeMinutes ?: JSONObject.NULL)
        put("streamUrl", streamUrl)
    }

    companion object {
        fun fromJson(o: JSONObject): Episode = Episode(
            number = o.optInt("number"),
            title = o.optString("title"),
            overview = o.optString("overview"),
            stillUrl = o.stringOrNull("stillUrl"),
            airDate = o.stringOrNull("airDate"),
            runtimeMinutes = o.intOrNull("runtimeMinutes"),
            streamUrl = o.optString("streamUrl")
        )
    }
}

data class Season(
    val number: Int,
    val name: String = "",
    val episodeCount: Int = 0,
    val airDate: String? = null,
    val posterUrl: String? = null,
    val episodes: List<Episode> = emptyList()
) {
    val displayName: String get() = if (name.isBlank()) "Season $number" else name

    fun toJson(): JSONObject = JSONObject().apply {
        put("number", number)
        put("name", name)
        put("episodeCount", episodeCount)
        put("airDate", airDate ?: JSONObject.NULL)
        put("posterUrl", posterUrl ?: JSONObject.NULL)
        put("episodes", JSONArray(episodes.map { it.toJson() }))
    }

    companion object {
        fun fromJson(o: JSONObject): Season = Season(
            number = o.optInt("number"),
            name = o.optString("name"),
            episodeCount = o.optInt("episodeCount"),
            airDate = o.stringOrNull("airDate"),
            posterUrl = o.stringOrNull("posterUrl"),
            episodes = o.optJSONArray("episodes")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Episode.fromJson(it) } }
            }.orEmpty()
        )
    }
}

data class CatalogItem(
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
    val year: String? = null,
    /** Full `YYYY-MM-DD` date: the hall is ordered by newest release. */
    val releaseDate: String? = null,
    val overview: String = "",
    val tagline: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    /** `tt…` id: subtitle and stream addons match on it, not on the TMDB id. */
    val imdbId: String = "",
    val rating: Double? = null,
    val voteCount: Int? = null,
    val certification: String? = null,
    /** ISO 639-1 from TMDB; empty on records that never carried one. */
    val originalLanguage: String = "",
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    /** Transparent title logo art, when TMDB publishes one. */
    val logoUrl: String? = null,
    /** Top-billed cast, newest credits first from TMDB. */
    val cast: List<String> = emptyList(),
    /** Director (movies) or creator (series), joined when there are several. */
    val director: String = "",
    val seasons: List<Season> = emptyList(),
    val streamUrl: String = "",
    val tmdbLoaded: Boolean = false,
    val source: String = "TMDB",
    val addedAt: Long = System.currentTimeMillis()
) {
    val key: String get() = "${type.name.lowercase()}-$tmdbId"
    val isSeries: Boolean get() = type == MediaType.TV
    val yearLabel: String get() = year?.take(4).orEmpty()

    fun playbackKey(season: Int?, episode: Int?): String =
        if (type == MediaType.TV) {
            // A series without an episode has no episode to resume; a made-up
            // S1E1 key would read (and overwrite) another episode's progress.
            if (season == null || episode == null) "$key-unknown" else "$key-s${season}e$episode"
        } else key

    fun toJson(): JSONObject = JSONObject().apply {
        put("tmdbId", tmdbId)
        put("type", type.name)
        put("title", title)
        put("year", year ?: JSONObject.NULL)
        put("releaseDate", releaseDate ?: JSONObject.NULL)
        put("overview", overview)
        put("tagline", tagline)
        put("posterUrl", posterUrl ?: JSONObject.NULL)
        put("backdropUrl", backdropUrl ?: JSONObject.NULL)
        put("imdbId", imdbId)
        put("rating", rating ?: JSONObject.NULL)
        put("voteCount", voteCount ?: JSONObject.NULL)
        put("certification", certification ?: JSONObject.NULL)
        put("originalLanguage", originalLanguage)
        put("genres", JSONArray(genres))
        put("runtimeMinutes", runtimeMinutes ?: JSONObject.NULL)
        put("logoUrl", logoUrl ?: JSONObject.NULL)
        put("cast", JSONArray(cast))
        put("director", director)
        put("seasons", JSONArray(seasons.map { it.toJson() }))
        put("streamUrl", streamUrl)
        put("tmdbLoaded", tmdbLoaded)
        put("source", source)
        put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): CatalogItem = CatalogItem(
            tmdbId = o.optInt("tmdbId"),
            type = MediaType.from(o.stringOrNull("type")),
            title = o.optString("title", "Untitled"),
            year = o.stringOrNull("year"),
            releaseDate = o.stringOrNull("releaseDate"),
            overview = o.optString("overview"),
            tagline = o.optString("tagline"),
            posterUrl = o.stringOrNull("posterUrl"),
            backdropUrl = o.stringOrNull("backdropUrl"),
            imdbId = o.optString("imdbId"),
            rating = o.doubleOrNull("rating"),
            voteCount = o.intOrNull("voteCount"),
            certification = o.stringOrNull("certification"),
            originalLanguage = o.optString("originalLanguage"),
            genres = o.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty(),
            runtimeMinutes = o.intOrNull("runtimeMinutes"),
            logoUrl = o.stringOrNull("logoUrl"),
            cast = o.optJSONArray("cast")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty(),
            director = o.optString("director"),
            seasons = o.optJSONArray("seasons")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Season.fromJson(it) } }
            }.orEmpty(),
            streamUrl = o.optString("streamUrl"),
            tmdbLoaded = o.optBoolean("tmdbLoaded"),
            source = o.optString("source", "TMDB"),
            addedAt = o.optLong("addedAt", System.currentTimeMillis())
        )
    }
}

/** Refresh schedules offered by Settings > App Sync, one per source type. */
enum class SyncCadence(val label: String, val intervalMillis: Long?) {
    APP_START("App start", null),
    MANUAL("Manual", null),
    HOURS_12("12 hrs", 12L * 60 * 60 * 1000),
    HOURS_24("24 hrs", 24L * 60 * 60 * 1000);

    /**
     * [startup] is true for the single pass after launch, where APP_START runs
     * once; the in-process timer passes false so APP_START does not repeat.
     */
    fun shouldRun(startup: Boolean, lastRun: Long, now: Long = System.currentTimeMillis()): Boolean =
        when (this) {
            MANUAL -> false
            APP_START -> startup
            HOURS_12, HOURS_24 -> lastRun <= 0L || now - lastRun >= (intervalMillis ?: Long.MAX_VALUE)
        }

    companion object {
        fun from(raw: String?): SyncCadence = entries.firstOrNull { it.name == raw } ?: MANUAL
    }
}

enum class SyncTarget(val label: String) {
    IMPORTS("Imported lists"),
    IPTV("IPTV playlists"),
    ADDONS("Stremio addons")
}

data class AppSettings(
    val tmdbApiKey: String = "",
    val proxyBaseUrl: String = "",
    val includeAdult: Boolean = false,
    val parentalPinHash: String = "",
    val playbackEngine: PlaybackEngine = PlaybackEngine.AUTO,
    val qualityLimit: QualityLimit = QualityLimit.OFF,
    /** Fast-start buffers for HLS/DASH; it does not enable LL-HLS/LL-DASH part loading. */
    val lowLatency: Boolean = true,
    val abrEnabled: Boolean = true,
    val bandwidthCap: BandwidthCap = BandwidthCap.AUTO,
    val bufferTier: BufferTier = BufferTier.BALANCED,
    val sortMode: SortMode = SortMode.RECENT,
    val posterOrientation: PosterOrientation = PosterOrientation.PORTRAIT,
    val posterSize: PosterSize = PosterSize.MEDIUM,
    val decoderType: DecoderType = DecoderType.AUTO,
    /**
     * Amlogic-class SoCs mis-parse frames through the synchronous MediaCodec
     * API; the fix switches the built-in player to asynchronous queueing and
     * enables tunneled playback.
     */
    val amlogicFix: Boolean = false,
    /** Match the display refresh rate to the source (23.976, 24, 25, 50 or 60 Hz). */
    val frameRateMatching: Boolean = false,
    val forceStereoDownmix: Boolean = false,
    val normalizeVolume: Boolean = true,
    val clearCacheOnExit: Boolean = false,
    val uiSounds: Boolean = true,
    val playerUserAgent: PlayerUaPreset = PlayerUaPreset.DEFAULT,
    val dnsMode: DnsMode = DnsMode.SYSTEM,
    val customDnsServers: List<String> = emptyList(),
    val addons: List<AddonSource> = emptyList(),
    val importsSync: SyncCadence = SyncCadence.MANUAL,
    val iptvSync: SyncCadence = SyncCadence.MANUAL,
    val addonsSync: SyncCadence = SyncCadence.APP_START,
    /** Next up follows the highest watched episode; off follows the latest play (rewatches). */
    val nextUpFurthestWatched: Boolean = true,
    /** Most-recent-first search terms shown as one-tap chips in the Playroom. */
    val recentSearches: List<String> = emptyList(),
    /** Caption size multiplier applied by the player (0.85, 1.0 or 1.2). */
    val subtitleScale: Float = 1f,
    /** Caption backdrop: 0 none, 1 dim, 2 black. */
    val subtitleBackdrop: Int = 0,
    /** JSON link to the stream badge set shown on source cards; blank hides badges. */
    val badgeSourceUrl: String = "",
    /**
     * Movies and episodes start the first searched source on their own; off
     * holds playback until a source is picked from the retrieved list. Live
     * tuning has a single source and ignores this.
     */
    val autoPlaySearchedStream: Boolean = true,
    /** ISO 639-1 content language for TMDB metadata and Smart Search ranking. */
    val contentLanguage: String = "",
    /** ISO 3166-1 alpha-2 origin country for TMDB discover; blank keeps any. */
    val contentCountry: String = "",
    val languageRule: LanguageRule = LanguageRule.PREFER,
    /** Sort addon sources tagged with [contentLanguage] ahead of untagged ones. */
    val preferLanguageSources: Boolean = true,
    /** Compare with the latest GitHub release at startup and offer the update. */
    val autoUpdateCheck: Boolean = true
) {
    val hasTmdbAccess: Boolean get() = tmdbApiKey.isNotBlank() || proxyBaseUrl.isNotBlank()

    val hasParentalPin: Boolean get() = parentalPinHash.isNotBlank()

    /**
     * Derived from the three knobs the preset owns, so flipping any of them by
     * hand (or restoring a backup) can never leave the toggle disagreeing with
     * what the player is actually doing.
     */
    val weakNetworkPreset: Boolean
        get() = bufferTier == BufferTier.LEAN &&
            bandwidthCap == BandwidthCap.MBPS2 &&
            !lowLatency

    /**
     * One tap for a bad line: a shallow tier (which also shrinks the VLC/MPV
     * caches), a 2 Mbps ceiling and a longer initial buffer instead of fast
     * start. Turning it off restores the shipped defaults.
     */
    fun withWeakNetworkPreset(enabled: Boolean): AppSettings = if (enabled) {
        copy(bufferTier = BufferTier.LEAN, bandwidthCap = BandwidthCap.MBPS2, lowLatency = false)
    } else {
        copy(bufferTier = BufferTier.BALANCED, bandwidthCap = BandwidthCap.AUTO, lowLatency = true)
    }

    val userAgentString: String
        get() = playerUserAgent.userAgent.ifBlank { Http.BROWSER_USER_AGENT }

    val activeDnsServers: List<String>
        get() = when (dnsMode) {
            DnsMode.SYSTEM -> emptyList()
            DnsMode.CUSTOM -> customDnsServers
            else -> dnsMode.servers
        }

    fun normalized(): AppSettings {
        val cleanDns = customDnsServers.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(6)
        val cleanAddons = addons.mapNotNull { addon ->
            AddonSource.normalize(addon.url)?.let { url ->
                addon.copy(url = url, name = addon.name.trim())
            }
        }.distinctBy { it.id }
        val cleanPinHash = parentalPinHash.trim().lowercase()
            .filter { it in "0123456789abcdef" }
            .take(64)
        val cleanSearches = recentSearches
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .take(8)
        val cleanLanguage = ContentLanguage.from(contentLanguage).code
        return copy(
            // 18+ without a PIN would bypass the parental gate; upgrades that
            // predate the PIN simply start with adult content off.
            includeAdult = includeAdult && cleanPinHash.isNotBlank(),
            customDnsServers = cleanDns,
            addons = cleanAddons,
            parentalPinHash = cleanPinHash,
            recentSearches = cleanSearches,
            badgeSourceUrl = badgeSourceUrl.trim().takeIf {
                it.startsWith("http://") || it.startsWith("https://")
            }.orEmpty(),
            contentLanguage = cleanLanguage,
            contentCountry = ContentCountry.from(contentCountry).code,
            // Filtering by an unset language would erase every shelf.
            languageRule = if (cleanLanguage.isBlank()) LanguageRule.PREFER
            else LanguageRule.from(languageRule.name)
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("tmdbApiKey", tmdbApiKey)
        put("proxyBaseUrl", proxyBaseUrl)
        put("includeAdult", includeAdult)
        put("parentalPinHash", parentalPinHash)
        put("playbackEngine", playbackEngine.name)
        put("qualityLimit", qualityLimit.name)
        put("lowLatency", lowLatency)
        put("abrEnabled", abrEnabled)
        put("bandwidthCap", bandwidthCap.name)
        put("bufferTier", bufferTier.name)
        put("sortMode", sortMode.name)
        put("posterOrientation", posterOrientation.name)
        put("posterSize", posterSize.name)
        put("decoderType", decoderType.name)
        put("amlogicFix", amlogicFix)
        put("frameRateMatching", frameRateMatching)
        put("forceStereoDownmix", forceStereoDownmix)
        put("normalizeVolume", normalizeVolume)
        put("clearCacheOnExit", clearCacheOnExit)
        put("uiSounds", uiSounds)
        put("playerUserAgent", playerUserAgent.name)
        put("dnsMode", dnsMode.name)
        put("customDnsServers", JSONArray(customDnsServers))
        put("addons", JSONArray(addons.map { it.toJson() }))
        put("importsSync", importsSync.name)
        put("iptvSync", iptvSync.name)
        put("addonsSync", addonsSync.name)
        put("nextUpFurthestWatched", nextUpFurthestWatched)
        put("recentSearches", JSONArray(recentSearches))
        put("subtitleScale", subtitleScale.toDouble())
        put("subtitleBackdrop", subtitleBackdrop)
        put("badgeSourceUrl", badgeSourceUrl)
        put("autoPlaySearchedStream", autoPlaySearchedStream)
        put("contentLanguage", contentLanguage)
        put("contentCountry", contentCountry)
        put("languageRule", languageRule.name)
        put("preferLanguageSources", preferLanguageSources)
        put("autoUpdateCheck", autoUpdateCheck)
    }

    companion object {
        fun fromJson(o: JSONObject): AppSettings {
            val addons = o.optJSONArray("addons")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { AddonSource.fromJson(it) } }
            }.orEmpty()
            val customDns = o.optJSONArray("customDnsServers")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            val recentSearches = o.optJSONArray("recentSearches")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            return AppSettings(
                tmdbApiKey = o.optString("tmdbApiKey"),
                proxyBaseUrl = o.optString("proxyBaseUrl"),
                includeAdult = o.optBoolean("includeAdult"),
                parentalPinHash = o.optString("parentalPinHash"),
                playbackEngine = runCatching { PlaybackEngine.valueOf(o.optString("playbackEngine")) }
                    .getOrDefault(PlaybackEngine.AUTO),
                qualityLimit = QualityLimit.from(o.stringOrNull("qualityLimit")),
                lowLatency = o.optBoolean("lowLatency", true),
                abrEnabled = o.optBoolean("abrEnabled", true),
                bandwidthCap = BandwidthCap.from(o.stringOrNull("bandwidthCap")),
                bufferTier = BufferTier.from(o.stringOrNull("bufferTier")),
                sortMode = SortMode.from(o.stringOrNull("sortMode")),
                posterOrientation = PosterOrientation.from(o.stringOrNull("posterOrientation")),
                posterSize = PosterSize.from(o.stringOrNull("posterSize")),
                decoderType = DecoderType.from(o.stringOrNull("decoderType")),
                amlogicFix = o.optBoolean("amlogicFix", false),
                frameRateMatching = o.optBoolean("frameRateMatching", false),
                forceStereoDownmix = o.optBoolean("forceStereoDownmix", false),
                normalizeVolume = o.optBoolean("normalizeVolume", true),
                clearCacheOnExit = o.optBoolean("clearCacheOnExit", false),
                uiSounds = o.optBoolean("uiSounds", true),
                playerUserAgent = PlayerUaPreset.from(o.stringOrNull("playerUserAgent")),
                dnsMode = DnsMode.from(o.stringOrNull("dnsMode")),
                customDnsServers = customDns,
                addons = addons,
                importsSync = SyncCadence.from(o.stringOrNull("importsSync")),
                iptvSync = SyncCadence.from(o.stringOrNull("iptvSync")),
                addonsSync = if (o.has("addonsSync")) {
                    SyncCadence.from(o.stringOrNull("addonsSync"))
                } else {
                    // Pre-App-Sync installs already refreshed addons on every launch.
                    SyncCadence.APP_START
                },
                nextUpFurthestWatched = o.optBoolean("nextUpFurthestWatched", true),
                recentSearches = recentSearches,
                subtitleScale = o.optDouble("subtitleScale", 1.0)
                    .toFloat()
                    .coerceIn(0.75f, 1.5f),
                subtitleBackdrop = o.optInt("subtitleBackdrop", 0).coerceIn(0, 2),
                badgeSourceUrl = o.stringOrNull("badgeSourceUrl")?.takeIf {
                    it.startsWith("http://") || it.startsWith("https://")
                }.orEmpty(),
                autoPlaySearchedStream = o.optBoolean("autoPlaySearchedStream", true),
                contentLanguage = o.optString("contentLanguage"),
                contentCountry = o.optString("contentCountry"),
                languageRule = LanguageRule.from(o.stringOrNull("languageRule")),
                preferLanguageSources = o.optBoolean("preferLanguageSources", true),
                autoUpdateCheck = o.optBoolean("autoUpdateCheck", true)
            ).normalized()
        }
    }
}

data class ImportSource(
    val name: String,
    val url: String,
    val kind: String,
    val lastSync: Long = System.currentTimeMillis(),
    val itemCount: Int = 0
) {
    val id: String get() = url.lowercase()

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("url", url)
        put("kind", kind)
        put("lastSync", lastSync)
        put("itemCount", itemCount)
    }

    companion object {
        fun fromJson(o: JSONObject) = ImportSource(
            name = o.optString("name"),
            url = o.optString("url"),
            kind = o.optString("kind", "JSON"),
            lastSync = o.optLong("lastSync"),
            itemCount = o.optInt("itemCount")
        )
    }
}

data class WatchProgress(
    val playbackKey: String,
    val itemKey: String,
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
    val posterUrl: String?,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val fraction: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    // Older saved rows may predate itemKey; fall back to the episode key so
    // they never collapse into one shared group.
    val groupKey: String get() = itemKey.ifBlank { playbackKey }

    val isResumable: Boolean get() = durationMs > 60_000 && fraction in 0.01f..0.95f

    val resumeLabel: String
        get() = if (season != null && episode != null) "Resume S${season}E${episode}" else "Resume"

    fun toJson(): JSONObject = JSONObject().apply {
        put("playbackKey", playbackKey)
        put("itemKey", itemKey)
        put("tmdbId", tmdbId)
        put("type", type.name)
        put("title", title)
        put("posterUrl", posterUrl ?: JSONObject.NULL)
        put("season", season ?: JSONObject.NULL)
        put("episode", episode ?: JSONObject.NULL)
        put("episodeTitle", episodeTitle ?: JSONObject.NULL)
        put("positionMs", positionMs)
        put("durationMs", durationMs)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = WatchProgress(
            playbackKey = o.optString("playbackKey"),
            itemKey = o.optString("itemKey"),
            tmdbId = o.optInt("tmdbId"),
            type = MediaType.from(o.stringOrNull("type")),
            title = o.optString("title"),
            posterUrl = o.stringOrNull("posterUrl"),
            season = o.intOrNull("season"),
            episode = o.intOrNull("episode"),
            episodeTitle = o.stringOrNull("episodeTitle"),
            positionMs = o.optLong("positionMs"),
            durationMs = o.optLong("durationMs"),
            updatedAt = o.optLong("updatedAt")
        )
    }
}

enum class DrmScheme {
    WIDEVINE, PLAYREADY, CLEARKEY;

    /** System id as published in DASH `schemeIdUri` and HLS `KEYFORMAT`. */
    val uuid: String
        get() = when (this) {
            WIDEVINE -> "edef8ba9-79b4-4a3f-85a1-a4dbf3b56b8a"
            PLAYREADY -> "9a04f079-9840-4286-ab92-e65be0885f95"
            CLEARKEY -> "e2719d58-a985-b3c9-781a-b030af78d30e"
        }

    companion object {
        /** Maps a scheme id, UUID or vendor alias (`com.microsoft.playready`). */
        fun fromId(raw: String?): DrmScheme? {
            val id = raw?.lowercase()?.trim() ?: return null
            return when {
                id.contains("edef8ba9") || id.contains("widevine") -> WIDEVINE
                id.contains("9a04f079") || id.contains("playready") -> PLAYREADY
                id.contains("e2719d58") || id.contains("clearkey") -> CLEARKEY
                else -> null
            }
        }
    }
}

/**
 * False when the device ships no MediaDrm module for the scheme — PlayReady is
 * absent on AOSP builds, and L1 Widevine only exists on OEM hardware.
 */
fun DrmScheme.supportedOnDevice(): Boolean = runCatching {
    android.media.MediaDrm.isCryptoSchemeSupported(java.util.UUID.fromString(uuid))
}.getOrDefault(false)

/**
 * DRM parameters for one stream. ClearKey feeds carry the W3C license JSON that
 * the source page handed out; Widevine/PlayReady feeds need a license server.
 */
data class DrmConfig(
    val scheme: DrmScheme,
    val licenseUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val licenseJson: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("scheme", scheme.name)
        put("licenseUrl", licenseUrl ?: JSONObject.NULL)
        put("headers", JSONObject(headers))
        put("licenseJson", licenseJson ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): DrmConfig? {
            val scheme = runCatching { DrmScheme.valueOf(o.optString("scheme")) }.getOrNull()
                ?: return null
            val headers = o.optJSONObject("headers")?.let { json ->
                json.keys().asSequence().associateWith { json.optString(it) }
            }.orEmpty()
            return DrmConfig(
                scheme = scheme,
                licenseUrl = o.stringOrNull("licenseUrl"),
                headers = headers,
                licenseJson = o.stringOrNull("licenseJson")
            )
        }
    }
}

data class PlaybackOption(
    val name: String,
    val url: String,
    /** Extra request headers required by the source (addon proxyHeaders). */
    val headers: Map<String, String> = emptyMap(),
    /** Subtitles shipped inline with the stream entry. */
    val subtitles: List<AddonSubtitle> = emptyList(),
    /** Set when the stream is CENC-protected. */
    val drm: DrmConfig? = null,
    /** Card fields split out of the addon's free-text stream description. */
    val meta: StreamMeta = StreamMeta()
)

data class PlaybackPlan(
    val itemKey: String,
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
    val subtitle: String,
    val posterUrl: String?,
    val season: Int?,
    val episode: Int?,
    val progressKey: String,
    val startPositionMs: Long,
    val options: List<PlaybackOption>
)

/** One source probed by the playable-stream search, and where it stands. */
enum class StreamSearchPhase { SEARCHING, FOUND, EMPTY, FAILED }

data class StreamSearchStep(
    val key: String,
    val label: String,
    val phase: StreamSearchPhase,
    val found: Int = 0,
    val detail: String? = null
)

sealed interface StreamSearchEvent {
    data class Step(val step: StreamSearchStep) : StreamSearchEvent
    data class Finished(
        val options: List<PlaybackOption>,
        val steps: List<StreamSearchStep>
    ) : StreamSearchEvent
}

data class ExternalPlayerInfo(val packageName: String, val label: String)

/** Four-digit parental PIN; only the salted hash is ever persisted. */
object ParentalPin {
    private const val SALT = "streamline-parental-v1:"

    fun isValid(pin: String): Boolean = pin.length == 4 && pin.all(Char::isDigit)

    fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest((SALT + pin).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun matches(pin: String, storedHash: String): Boolean =
        storedHash.isNotBlank() && hash(pin) == storedHash
}

fun JSONObject.stringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

fun JSONObject.doubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val value = optDouble(key)
    return if (value.isNaN()) null else value
}

fun List<CatalogItem>.sortedFor(mode: SortMode): List<CatalogItem> = when (mode) {
    SortMode.RECENT -> sortedByDescending { it.addedAt }
    SortMode.TITLE -> sortedBy { it.title.lowercase() }
    SortMode.RATING -> sortedByDescending { it.rating ?: 0.0 }
    SortMode.YEAR -> sortedByDescending { it.year ?: "" }
}

/** Newest release first; undated titles fall below dated ones, newest imports break ties. */
fun List<CatalogItem>.sortedByNewest(): List<CatalogItem> =
    sortedWith(
        compareByDescending<CatalogItem> { it.releaseDate.orEmpty() }
            .thenByDescending { it.year.orEmpty() }
            .thenByDescending { it.addedAt }
    )
