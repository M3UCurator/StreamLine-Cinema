package com.streamline.cinema.data

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.decodeBase64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Stremio addon protocol: a manifest at `/manifest.json` plus
 * `/stream/{type}/{id}.json` resources that return playable streams.
 */
data class AddonSource(
    val url: String,
    val name: String = "",
    val enabled: Boolean = true,
    /** Provider artwork from the manifest, shown on the source cards. */
    val logo: String = ""
) {
    val id: String get() = url.trim().trimEnd('/').lowercase()

    /** Keeps the path casing; addon hosts are often case-sensitive. */
    val manifestUrl: String
        get() {
            val base = url.trim().trimEnd('/')
            return if (base.endsWith("/manifest.json", ignoreCase = true)) base else "$base/manifest.json"
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("url", url)
        put("name", name)
        put("enabled", enabled)
        put("logo", logo)
    }

    companion object {
        fun fromJson(o: JSONObject) = AddonSource(
            url = o.optString("url"),
            name = o.optString("name"),
            enabled = o.optBoolean("enabled", true),
            logo = o.optString("logo")
        )

        /** Accepts `https://host`, `https://host/` or `https://host/manifest.json`. */
        fun normalize(raw: String): String? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
            val parsed = trimmed.toHttpUrlOrNull() ?: return null
            val base = parsed.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
            return if (base.endsWith("/manifest.json")) base else "$base/manifest.json"
        }
    }
}

/**
 * A selection group declared by a catalog (`extra`): `country-name`, `sub-category`,
 * `category`, `search`… Catalogs with options are filtered server-side by the addon.
 */
data class CatalogExtra(
    val name: String,
    val options: List<String> = emptyList(),
    val isRequired: Boolean = false
) {
    /** `skip` is paging, `search` is free text; neither is a chip group. */
    val isSelectable: Boolean get() = options.isNotEmpty() && name != "skip"
}

/** A catalog declared by an addon manifest (live TV/sports addons publish non movie/series types). */
data class AddonCatalog(
    val id: String,
    val name: String,
    val type: String,
    val extras: List<CatalogExtra> = emptyList()
)

data class AddonManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    val resources: List<Any>,
    val types: List<String>,
    val idPrefixes: List<String>?,
    val catalogs: List<AddonCatalog> = emptyList(),
    /** Provider artwork declared by the manifest (`logo`). */
    val logo: String? = null
) {
    val supportsStreams: Boolean
        get() = hasResource("stream")

    /** Subtitle-only addons (OpenSubtitles and friends) supply tracks to the player. */
    val supportsSubtitles: Boolean
        get() = hasResource("subtitles")

    private fun hasResource(name: String): Boolean = resources.any { resource ->
        when (resource) {
            is String -> resource == name
            is Map<*, *> -> resource["name"] == name
            else -> false
        }
    }

    companion object {
        fun fromJson(o: JSONObject) = AddonManifest(
            id = o.optString("id"),
            name = o.optString("name", "Addon"),
            version = o.optString("version"),
            description = o.stringOrNull("description"),
            resources = parseResources(o.optJSONArray("resources")),
            types = o.optJSONArray("types").stringList(),
            idPrefixes = o.optJSONArray("idPrefixes")?.stringList()?.takeIf { it.isNotEmpty() },
            catalogs = o.optJSONArray("catalogs")?.let { arr ->
                // Live addons (FayaTV) publish 100+ catalogs; each entry is tiny, so
                // keep room for the whole list instead of truncating it.
                val count = arr.length().coerceAtMost(MAX_CATALOGS)
                (0 until count).mapNotNull { index ->
                    val entry = arr.optJSONObject(index) ?: return@mapNotNull null
                    val id = entry.stringOrNull("id") ?: return@mapNotNull null
                    AddonCatalog(
                        id = id,
                        name = entry.stringOrNull("name") ?: id,
                        type = entry.stringOrNull("type") ?: return@mapNotNull null,
                        extras = parseCatalogExtras(entry)
                    )
                }
            }.orEmpty(),
            logo = o.stringOrNull("logo")?.takeIf {
                it.startsWith("http://") || it.startsWith("https://")
            }
        )

        private fun parseCatalogExtras(entry: JSONObject): List<CatalogExtra> {
            val extras = LinkedHashMap<String, CatalogExtra>()
            entry.optJSONArray("extra")?.let { arr ->
                for (index in 0 until arr.length()) {
                    val o = arr.optJSONObject(index) ?: continue
                    val name = o.stringOrNull("name") ?: continue
                    extras[name] = CatalogExtra(
                        name = name,
                        options = o.optJSONArray("options").optionList(),
                        isRequired = o.optBoolean("isRequired", false)
                    )
                }
            }
            // Older manifests declare extras as name arrays instead of descriptor objects.
            val required = entry.optJSONArray("extraRequired")?.stringList()?.toSet().orEmpty()
            entry.optJSONArray("extraSupported")?.stringList().orEmpty().forEach { name ->
                extras.putIfAbsent(name, CatalogExtra(name = name, isRequired = name in required))
            }
            return extras.values.toList()
        }

        private fun parseResources(arr: JSONArray?): List<Any> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { index ->
                when (val value = arr.opt(index)) {
                    is String -> value
                    is JSONObject -> value.asMap()
                    else -> null
                }
            }
        }
    }
}

/** Subtitle entry from a stream's inline `subtitles` array or `/subtitles/…` resource. */
data class AddonSubtitle(
    val url: String,
    val lang: String = "",
    val label: String? = null
) {
    companion object {
        fun fromJson(o: JSONObject): AddonSubtitle? {
            val url = o.stringOrNull("url")?.takeIf { it.startsWith("http") } ?: return null
            val lang = o.stringOrNull("lang")
                ?: o.optJSONArray("langs")?.stringList()?.firstOrNull()
                ?: ""
            return AddonSubtitle(url = url, lang = lang, label = o.stringOrNull("label"))
        }
    }
}

/** A season plus the episode span a source claims, e.g. `S02E05` or `S02E05-E06`. */
data class EpisodeLabel(val season: Int, val episodes: IntRange) {
    fun covers(season: Int, episode: Int): Boolean =
        this.season == season && episode in episodes
}

data class StreamItem(
    val name: String?,
    val title: String?,
    val url: String?,
    val externalUrl: String?,
    val behaviorHints: Map<String, Any>?,
    val subtitles: List<AddonSubtitle> = emptyList()
) {
    /**
     * Season/episode this source advertises in its own label. Used only to reject
     * a source that names a different episode; an unlabelled source is not
     * assumed to be wrong.
     */
    fun advertisedEpisodes(): EpisodeLabel? {
        val text = labelText()
        for (pattern in EPISODE_RANGE_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val season = match.groupValues[1].toIntOrNull() ?: continue
            val first = match.groupValues[2].toIntOrNull() ?: continue
            val last = match.groupValues[3].toIntOrNull() ?: first
            if (first > last) continue
            return EpisodeLabel(season, first..last)
        }
        for (pattern in EPISODE_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val season = match.groupValues[1].toIntOrNull() ?: continue
            val episode = match.groupValues[2].toIntOrNull() ?: continue
            // "16x9" / "21x9" are aspect ratios, not season 16 episode 9.
            if (pattern === ASPECT_LIKE_PATTERN && (season to episode) in ASPECT_PAIRS) continue
            return EpisodeLabel(season, episode..episode)
        }
        return null
    }

    fun advertisedEpisode(): Pair<Int, Int>? =
        advertisedEpisodes()?.let { it.season to it.episodes.first }

    /** Season named without an episode, e.g. a `Season 2 Complete` pack. */
    fun advertisedSeason(): Int? {
        val text = labelText()
        // A loose episode marker means this is an episode label we could not
        // parse; claiming it is a season pack would let it pass for any episode.
        if (UNPARSED_EPISODE.containsMatchIn(text)) return null
        return SEASON_PATTERN.find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** An episode-sized token we cannot place, so the label cannot be trusted. */
    fun hasUnparsedEpisodeMarker(): Boolean =
        advertisedEpisodes() == null && UNPARSED_EPISODE.containsMatchIn(labelText())

    private fun labelText(): String = listOfNotNull(name, title).joinToString(" ")

    /** Behaviour hints can carry CDN request headers (`proxyHeaders.request`). */
    fun requestHeaders(): Map<String, String> {
        val proxy = behaviorHints?.get("proxyHeaders") as? Map<*, *> ?: return emptyMap()
        val request = proxy["request"] as? Map<*, *> ?: return emptyMap()
        return request.entries.mapNotNull { (key, value) ->
            val name = key as? String ?: return@mapNotNull null
            val text = when (value) {
                is String -> value
                is Map<*, *> -> value["value"] as? String
                else -> null
            } ?: return@mapNotNull null
            name to text
        }.toMap()
    }

    companion object {
        private val EPISODE_RANGE_PATTERNS = listOf(
            // S01E01-E02, S01E01-02, S01E01~E02 and S01E01E02.
            Regex("""\bS(\d{1,2})\s*[._ -]?\s*E(\d{1,3})\s*(?:E|[-~–])\s*(?:S\d{1,2}\s*[._ -]?\s*)?E?(\d{1,3})\b""", RegexOption.IGNORE_CASE)
        )
        private val EPISODE_PATTERNS = listOf(
            Regex("""\bS(\d{1,2})\s*[._ -]?\s*E(\d{1,3})\b""", RegexOption.IGNORE_CASE),
            Regex("""\bSeason\s*(\d{1,2})\s*(?:[·,/–-]\s*)?Ep(?:isode)?\.?\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(\d{1,2})x(\d{1,3})\b""")
        )
        private val ASPECT_LIKE_PATTERN = EPISODE_PATTERNS[2]
        private val ASPECT_PAIRS = setOf(16 to 9, 16 to 10, 21 to 9, 4 to 3)
        private val SEASON_PATTERN = Regex("""\bSeason\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)
        private val UNPARSED_EPISODE = Regex("""\b(?:Ep|Episode)\.?\s*\d{1,3}\b""", RegexOption.IGNORE_CASE)

        fun fromJson(o: JSONObject) = StreamItem(
            name = o.stringOrNull("name"),
            title = o.stringOrNull("title"),
            url = o.stringOrNull("url")?.takeIf { it.startsWith("http") },
            externalUrl = o.stringOrNull("externalUrl")?.takeIf { it.startsWith("http") },
            behaviorHints = o.optJSONObject("behaviorHints")?.asMap(),
            subtitles = o.optJSONArray("subtitles")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { AddonSubtitle.fromJson(it) }
                }
            }.orEmpty()
        )
    }
}

/** A watch page scraped down to a direct manifest, plus any DRM keys it published. */
data class ResolvedWatchStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val drm: DrmConfig? = null
)

/**
 * Protection advertised by a manifest itself. Media3 ignores `<ms:laurl>` and
 * HLS `SESSION-KEY` licence endpoints, so the DRM session has to be built from
 * what is parsed here. [protected] is set even when [drm] is null, which means
 * the feed is encrypted but advertises no usable licence (FairPlay, or a
 * PlayReady/Widevine server the device cannot reach).
 */
data class ManifestDrm(
    val protected: Boolean,
    val drm: DrmConfig? = null,
    val detail: String? = null
)

data class StreamResponse(val streams: List<StreamItem>) {
    companion object {
        fun fromJson(o: JSONObject) = StreamResponse(
            streams = o.optJSONArray("streams")?.let { arr ->
                val count = arr.length().coerceAtMost(64)
                (0 until count).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { StreamItem.fromJson(it) }
                }
            }.orEmpty()
        )
    }
}

enum class LiveSourceKind { ADDON, IPTV }

/** Live catalog owned by an addon: FayaTV-style feeds expose `tv`/`sports` types. */
data class LiveCatalog(
    val addonName: String,
    val addonUrl: String,
    val id: String,
    val name: String,
    val type: String,
    val extras: List<CatalogExtra> = emptyList(),
    val sourceKind: LiveSourceKind = LiveSourceKind.ADDON,
    val iptvSourceId: String? = null
) {
    val key: String
        get() = if (iptvSourceId != null) "iptv|$iptvSourceId" else "$addonUrl|$type|$id"

    /** Groups the user can filter this catalog by (country, category, sub-category…). */
    val selectableExtras: List<CatalogExtra> get() = extras.filter { it.isSelectable }
}

/** Catalogs that loaded plus one readable line per addon whose manifest failed. */
data class LiveCatalogLoad(
    val catalogs: List<LiveCatalog>,
    val failures: List<String> = emptyList()
)

/**
 * Channel entry from a live catalog's `metas` array, or one line of an IPTV
 * playlist. [streamUrl] is set for IPTV entries and may carry the
 * `url|Header=value` pipe syntax; addon channels keep it null and resolve
 * through `/stream`.
 */
data class LiveChannel(
    val id: String,
    val name: String,
    val type: String,
    val addonUrl: String,
    val poster: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val category: String? = null,
    val streamUrl: String? = null,
    val epgId: String? = null,
    val channelNumber: Int? = null,
    val sourceId: String? = null
) {
    val key: String get() = "$addonUrl|$id"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type)
        put("addonUrl", addonUrl)
        put("poster", poster ?: JSONObject.NULL)
        put("description", description ?: JSONObject.NULL)
        put("releaseInfo", releaseInfo ?: JSONObject.NULL)
        put("category", category ?: JSONObject.NULL)
        put("streamUrl", streamUrl ?: JSONObject.NULL)
        put("epgId", epgId ?: JSONObject.NULL)
        put("channelNumber", channelNumber ?: JSONObject.NULL)
        put("sourceId", sourceId ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject) = LiveChannel(
            id = o.optString("id"),
            name = o.optString("name"),
            type = o.optString("type"),
            addonUrl = o.optString("addonUrl"),
            poster = o.stringOrNull("poster"),
            description = o.stringOrNull("description"),
            releaseInfo = o.stringOrNull("releaseInfo"),
            category = o.stringOrNull("category"),
            streamUrl = o.stringOrNull("streamUrl"),
            epgId = o.stringOrNull("epgId"),
            channelNumber = o.optInt("channelNumber").takeIf { it > 0 },
            sourceId = o.stringOrNull("sourceId")
        )
    }
}

/**
 * Outcome of a live channel lookup: a probed playable source, the addon's external
 * watch page (DRM/premium feeds), or a readable failure reason.
 */
data class LiveStreamResult(
    val option: PlaybackOption? = null,
    val externalUrl: String? = null,
    val error: String? = null,
    /** UDP/RTP/RTSP feeds have no Media3 data source and need an external player. */
    val externalOnly: Boolean = false,
    /**
     * The feed is fine but this device has no MediaDrm module for its scheme, so
     * it must not count against the channel's link health.
     */
    val deviceLimited: Boolean = false
)

/**
 * Result of a liveness probe. [url] is the final absolute URL after redirects
 * (the original when the chain could not be walked); [error] is null when the
 * link looks playable.
 */
data class StreamProbe(
    val url: String,
    val error: String? = null,
    val status: Int? = null,
    /** Manifest body when the probe fetched one; DRM detection reuses it. */
    val manifest: String? = null
) {
    val playable: Boolean get() = error == null

    /** 404/410 name a link that is gone; every other failure may be the probe's own session. */
    val linkGone: Boolean get() = status == 404 || status == 410
}

/**
 * Addon stream as a playable source. Entries that only carry `externalUrl`
 * (upsell pages, configuration links) are not playable and return null.
 */
internal fun StreamItem.toPlaybackOption(label: String, logo: String = ""): PlaybackOption? {
    val rawUrl = url?.takeIf { it.isNotBlank() } ?: return null
    // `url|User-Agent=…&Referer=…` keeps short-lived tokens out of the manifest.
    val parsed = StreamUrls.parse(rawUrl)
    if (parsed.url.isBlank()) return null
    val meta = StreamPresentation.parse(label, name, title, logo)
    // Some addons leave `name` empty and describe the quality in `title`.
    val detail = name?.trim()?.takeIf { it.isNotEmpty() }
        ?: title?.trim()?.takeIf { it.isNotEmpty() }?.take(48)
    val displayName = when {
        meta.headline.isNotBlank() && meta.subhead.isNotBlank() ->
            "$label · ${meta.headline} ${meta.subhead}"
        meta.headline.isNotBlank() -> "$label · ${meta.headline}"
        detail == null -> label
        else -> "$label · $detail"
    }.take(72)
    return PlaybackOption(
        name = displayName,
        url = parsed.url,
        headers = StreamUrls.mergeHeaders(requestHeaders(), parsed.headers),
        subtitles = subtitles,
        meta = meta
    )
}

object StremioAddons {

    /** Probes pull tiny playlist bodies; they must never hang the Live tab. */
    private val probeClient: OkHttpClient by lazy {
        Http.client.newBuilder()
            .callTimeout(10, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** `tmdb:123` for movies, `tmdb:123:2:5` for series episodes; blank without an episode. */
    fun contentId(item: CatalogItem, season: Int?, episode: Int?): String =
        if (item.isSeries) {
            if (item.tmdbId <= 0 || season == null || episode == null) "" else "tmdb:${item.tmdbId}:$season:$episode"
        } else {
            if (item.tmdbId <= 0) "" else "tmdb:${item.tmdbId}"
        }

    /**
     * Ids to query, best match first: most stream/subtitle addons only accept IMDb ids
     * (`idPrefixes: ["tt"]`), so `tt…` leads and the TMDB id stays as the fallback.
     *
     * A series without an explicit episode has no single correct stream id, so the
     * list is empty instead of defaulting to S1E1 — that guess used to search a
     * different episode than the one the viewer was watching.
     */
    fun contentIds(item: CatalogItem, season: Int?, episode: Int?): List<String> {
        if (item.tmdbId <= 0) return emptyList()
        val suffix = if (item.isSeries) {
            if (season == null || episode == null || season < 0 || episode < 1) return emptyList()
            ":$season:$episode"
        } else ""
        val imdb = item.imdbId.trim().lowercase()
            .takeIf { IMDB_ID.matches(it) }
            ?.plus(suffix)
        return listOfNotNull(imdb, "tmdb:${item.tmdbId}$suffix").distinct()
    }

    private val IMDB_ID = Regex("^tt\\d{6,10}$")

    fun isImdbId(value: String): Boolean = IMDB_ID.matches(value.trim().lowercase())

    /**
     * A stored IMDb id is only safe to query when it came from the item's own TMDB
     * record or was hand-typed as the item's id. Ids scraped from list markup, or
     * attached to an item we never resolved, can belong to another title.
     */
    fun canQueryStoredImdb(imdbId: String, tmdbLoaded: Boolean, source: String): Boolean =
        tmdbLoaded && source == "TMDB" && isImdbId(imdbId)

    /**
     * A source that names a different season/episode than the one requested is
     * another episode (or another title entirely) and must never play. Unlabelled
     * sources pass, since most addons publish only a quality label.
     */
    fun streamMatchesRequest(
        stream: StreamItem,
        isSeries: Boolean,
        season: Int?,
        episode: Int?
    ): Boolean {
        // A token that looks like an episode but did not parse cannot be cleared.
        if (stream.hasUnparsedEpisodeMarker()) return false
        val labelled = stream.advertisedEpisodes()
        if (labelled != null) {
            if (!isSeries || season == null || episode == null) return false
            return labelled.covers(season, episode)
        }
        val labelledSeason = stream.advertisedSeason() ?: return true
        return isSeries && season != null && labelledSeason == season
    }

    suspend fun loadManifest(baseUrl: String): AddonManifest {
        val url = AddonSource.normalize(baseUrl)
            ?: throw IOException("Enter an addon base URL starting with http:// or https://")
        return AddonManifest.fromJson(JSONObject(getText(url, MAX_MANIFEST_BYTES)))
    }

    suspend fun fetchStreams(baseUrl: String, type: String, id: String): List<StreamItem> {
        val url = resourceUrl(baseUrl, "stream", type, id) ?: return emptyList()
        return StreamResponse.fromJson(JSONObject(getText(url, MAX_RESOURCE_BYTES))).streams
    }

    /**
     * Some live addons publish only an external watch page. Those pages usually
     * inline the direct DASH/HLS manifest and, for encrypted feeds, the ClearKey
     * `kid`/`key` pair. Resolve once and cache briefly so channel zapping does
     * not re-download the page every time.
     */
    suspend fun resolveWatchPage(pageUrl: String): ResolvedWatchStream? = withContext(Dispatchers.IO) {
        val now = SystemClock.elapsedRealtime()
        synchronized(watchPageCache) {
            watchPageCache[pageUrl]?.takeIf { now - it.second < WATCH_PAGE_TTL_MS }?.let { return@withContext it.first }
        }
        val html = runCatching { getText(pageUrl, MAX_WATCH_PAGE_BYTES, accept = "text/html,application/xhtml+xml,*/*") }
            .getOrNull()
        val resolved = html?.let { parseWatchPage(it, pageUrl) }
        synchronized(watchPageCache) {
            if (watchPageCache.size > 32) watchPageCache.clear()
            watchPageCache[pageUrl] = resolved to now
        }
        resolved
    }

    internal fun parseWatchPage(html: String, pageUrl: String): ResolvedWatchStream? {
        val page = normalizeEmbeddedUrls(html)
        val direct = (watchPageRegex(WATCH_MEDIA_PATTERN).find(page)?.groupValues?.getOrNull(1)
            ?: watchPageRegex(WATCH_MEDIA_FALLBACK_PATTERN).find(page)?.groupValues?.getOrNull(1))
            ?.let { resolveUrl(it, pageUrl) }
            ?: return null
        val headers = pageHeaders(pageUrl)
        val kid = watchPageRegex(WATCH_KID_PATTERN).find(page)?.groupValues?.getOrNull(1)
        val key = watchPageRegex(WATCH_KEY_PATTERN).find(page)?.groupValues?.getOrNull(1)
        val licence = findLicence(page)
        val drm = when {
            kid != null && key != null -> clearKeyConfig(kid, key)
            licence != null -> licence.let { match ->
                resolveUrl(match.url, pageUrl)?.let { url ->
                    DrmConfig(scheme = match.scheme, licenseUrl = url, headers = headers)
                }
            }
            else -> null
        }
        return ResolvedWatchStream(
            url = direct,
            headers = headers,
            drm = drm
        )
    }

    /**
     * Player configs are inlined in script tags, so URLs arrive with escaped
     * slashes (`https:\/\/…`) and ampersands (`\u0026`, `&amp;`). Matching the
     * raw HTML misses every URL on pages that escape them.
     */
    private fun normalizeEmbeddedUrls(html: String): String = html
        .replace("\\/", "/")
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("&#x2F;", "/", ignoreCase = true)
        .replace("&#47;", "/")
        .replace("&amp;", "&")
        .replace("&#x26;", "&", ignoreCase = true)

    private fun resolveUrl(raw: String, baseUrl: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        return runCatching { base.resolve(trimmed)?.toString() }.getOrNull()
    }

    /**
     * Licence endpoints frequently enforce the page's own origin, and Media3's
     * DRM callback sends only what is configured here.
     */
    private fun pageHeaders(pageUrl: String): Map<String, String> =
        withOrigin(mapOf("Referer" to pageUrl), pageUrl)

    private fun withOrigin(headers: Map<String, String>, url: String): Map<String, String> {
        val parsed = url.toHttpUrlOrNull() ?: return headers
        if (headers.keys.any { it.equals("Origin", ignoreCase = true) }) return headers
        val defaultPort = if (parsed.scheme == "https") 443 else 80
        val origin = buildString {
            append(parsed.scheme).append("://").append(parsed.host)
            if (parsed.port != defaultPort) append(':').append(parsed.port)
        }
        return headers + ("Origin" to origin)
    }

    private data class LicenceMatch(val scheme: DrmScheme, val url: String)

    private fun findLicence(page: String): LicenceMatch? {
        watchPageRegex(WATCH_LICENSE_PATTERN).find(page)?.let { match ->
            val keyName = match.groupValues[1]
            val url = match.groupValues[2]
            val scheme = DrmScheme.fromId(keyName)
                ?: DrmScheme.fromId(url)
                ?: schemeFromPage(page)
                ?: DrmScheme.WIDEVINE
            return LicenceMatch(scheme, url)
        }
        // DASH <ms:laurl> carries the endpoint as element text, not an attribute.
        watchPageRegex(WATCH_LAURL_ELEMENT_PATTERN).find(page)?.let { match ->
            val url = match.groupValues[1]
            return LicenceMatch(DrmScheme.fromId(url) ?: schemeFromPage(page) ?: DrmScheme.WIDEVINE, url)
        }
        return null
    }

    private fun schemeFromPage(page: String): DrmScheme? = when {
        page.contains("edef8ba9", ignoreCase = true) || page.contains("widevine", ignoreCase = true) ->
            DrmScheme.WIDEVINE
        page.contains("9a04f079", ignoreCase = true) || page.contains("playready", ignoreCase = true) ->
            DrmScheme.PLAYREADY
        page.contains("e2719d58", ignoreCase = true) || page.contains("clearkey", ignoreCase = true) ->
            DrmScheme.CLEARKEY
        else -> null
    }

    /**
     * Protection declared by the manifest itself. Media3 ignores DASH
     * `<ms:laurl>` and HLS `SESSION-KEY` licence endpoints, so a direct
     * encrypted `.mpd`/`.m3u8` would otherwise fail with a generic source
     * error. The probe already downloaded the manifest, so this only parses it.
     */
    fun manifestDrm(probe: StreamProbe, requestHeaders: Map<String, String> = emptyMap()): ManifestDrm? {
        val body = probe.manifest ?: return null
        return parseManifestDrm(body, probe.url, requestHeaders)
    }

    internal fun parseManifestDrm(
        manifest: String,
        manifestUrl: String,
        requestHeaders: Map<String, String> = emptyMap()
    ): ManifestDrm? {
        val text = normalizeEmbeddedUrls(manifest)
        return when {
            text.contains("<MPD", ignoreCase = true) ||
                text.contains("urn:mpeg:dash", ignoreCase = true) -> parseDashDrm(text, manifestUrl, requestHeaders)
            text.contains("#EXTM3U", ignoreCase = true) -> parseHlsDrm(text, manifestUrl, requestHeaders)
            else -> null
        }
    }

    private fun parseDashDrm(
        manifest: String,
        manifestUrl: String,
        requestHeaders: Map<String, String>
    ): ManifestDrm? {
        val protectionBlocks = watchPageRegex(DASH_PROTECTION_BLOCK_PATTERN)
            .findAll(manifest)
            .map { it.value }
            .toList()
        val cenc = manifest.contains("urn:mpeg:dash:mp4protection:2011", ignoreCase = true)
        val hasPssh = manifest.contains("<cenc:pssh", ignoreCase = true) ||
            manifest.contains("default_KID", ignoreCase = true)
        if (protectionBlocks.isEmpty() && !cenc && !hasPssh) return null
        val scheme = protectionBlocks.asSequence()
            .mapNotNull { block ->
                watchPageRegex(DASH_SCHEME_URI_PATTERN).find(block)?.groupValues?.getOrNull(1)
            }
            .mapNotNull { DrmScheme.fromId(it) }
            .firstOrNull()
            ?: DrmScheme.fromId(
                watchPageRegex(DASH_SCHEME_URI_PATTERN).find(manifest)?.groupValues?.getOrNull(1)
            )
            ?: schemeFromPage(manifest)
        val licence = findLicence(manifest)
        val licenceUrl = licence?.let { resolveUrl(it.url, manifestUrl) }
        if (licence != null && licenceUrl != null) {
            return ManifestDrm(
                protected = true,
                drm = DrmConfig(
                    scheme = licence.scheme,
                    licenseUrl = licenceUrl,
                    headers = withOrigin(requestHeaders, manifestUrl)
                ),
                detail = licence.scheme.name
            )
        }
        return ManifestDrm(protected = true, detail = scheme?.name ?: if (cenc) "CENC" else null)
    }

    private fun parseHlsDrm(
        manifest: String,
        manifestUrl: String,
        requestHeaders: Map<String, String>
    ): ManifestDrm? {
        val keyTags = watchPageRegex(HLS_KEY_TAG_PATTERN)
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toList()
        if (keyTags.isEmpty()) return null
        val headers = withOrigin(requestHeaders, manifestUrl)
        var protected = false
        var unsupported: String? = null
        for (tag in keyTags) {
            val attrs = watchPageRegex(HLS_ATTRIBUTE_PATTERN).findAll(tag).associate {
                it.groupValues[1].uppercase() to it.groupValues[2].trim('"')
            }
            // AES-128 is plain HTTP encryption that Media3 decrypts itself;
            // only the SAMPLE-AES family needs a DRM session.
            if (!attrs["METHOD"].orEmpty().startsWith("SAMPLE-AES", ignoreCase = true)) continue
            protected = true
            val keyFormat = attrs["KEYFORMAT"].orEmpty()
            val scheme = DrmScheme.fromId(keyFormat)
            if (scheme == null) {
                // A missing KEYFORMAT is Apple FairPlay, which Android cannot play.
                unsupported = unsupported ?: if (keyFormat.isBlank()) "FairPlay" else keyFormat
                continue
            }
            val uri = attrs["URI"].orEmpty()
            decodeDataUri(uri)?.let { json ->
                return ManifestDrm(
                    protected = true,
                    drm = DrmConfig(scheme = scheme, licenseJson = json),
                    detail = scheme.name
                )
            }
            val licenceUrl = resolveUrl(uri, manifestUrl) ?: continue
            return ManifestDrm(
                protected = true,
                drm = DrmConfig(scheme = scheme, licenseUrl = licenceUrl, headers = headers),
                detail = scheme.name
            )
        }
        return if (protected) ManifestDrm(protected = true, detail = unsupported) else null
    }

    /** ClearKey HLS sometimes inlines the key JSON as a `data:` URI. */
    private fun decodeDataUri(uri: String): String? {
        if (!uri.startsWith("data:", ignoreCase = true)) return null
        val meta = uri.substringAfter("data:").substringBefore(',')
        val payload = uri.substringAfter(',', "")
        if (payload.isBlank()) return null
        return if (meta.contains("base64", ignoreCase = true)) {
            runCatching { payload.decodeBase64()?.utf8() }.getOrNull()
        } else {
            runCatching { java.net.URLDecoder.decode(payload, "UTF-8") }.getOrNull()
        }
    }

    private fun clearKeyConfig(kidHex: String, keyHex: String): DrmConfig? {
        val kid = hexBytes(kidHex) ?: return null
        val key = hexBytes(keyHex) ?: return null
        fun encode(bytes: ByteArray): String = okio.ByteString.of(*bytes).base64Url()
        val json = """{"keys":[{"kty":"oct","kid":"${encode(kid)}","k":"${encode(key)}"}],"type":"temporary"}"""
        return DrmConfig(scheme = DrmScheme.CLEARKEY, licenseJson = json)
    }

    private fun hexBytes(raw: String): ByteArray? {
        val clean = raw.trim().removePrefix("0x").removePrefix("0X")
        if (clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun watchPageRegex(pattern: String): Regex =
        Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private val watchPageCache = mutableMapOf<String, Pair<ResolvedWatchStream?, Long>>()

    /** `GET /subtitles/{type}/{id}.json` for addons that expose the subtitle resource. */
    suspend fun fetchSubtitles(baseUrl: String, type: String, id: String): List<AddonSubtitle> {
        val url = resourceUrl(baseUrl, "subtitles", type, id) ?: return emptyList()
        val json = JSONObject(getText(url, MAX_RESOURCE_BYTES))
        val arr = json.optJSONArray("subtitles") ?: return emptyList()
        val count = arr.length().coerceAtMost(MAX_SUBTITLE_TRACKS)
        return (0 until count).mapNotNull { i ->
            arr.optJSONObject(i)?.let { AddonSubtitle.fromJson(it) }
        }
    }

    /**
     * `GET /catalog/{type}/{id}.json`, or `/catalog/{type}/{id}/name=value&…json`
     * when a selection group is active. Ids like `tv_Latino (Spanish)` and values
     * like `United States` must be percent-encoded or OkHttp rejects the URL.
     */
    suspend fun fetchCatalog(
        baseUrl: String,
        type: String,
        catalogId: String,
        extras: Map<String, String> = emptyMap(),
        skip: Int = 0
    ): List<LiveChannel> {
        val url = catalogUrl(baseUrl, type, catalogId, extras, skip) ?: return emptyList()
        val metas = JSONObject(getText(url, MAX_RESOURCE_BYTES)).optJSONArray("metas") ?: return emptyList()
        val count = metas.length().coerceAtMost(MAX_CATALOG_CHANNELS)
        return (0 until count).mapNotNull { i ->
            val entry = metas.optJSONObject(i) ?: return@mapNotNull null
            val id = entry.stringOrNull("id") ?: return@mapNotNull null
            val name = entry.stringOrNull("name") ?: return@mapNotNull null
            // Promo cards ("Upgrade to Premium") are not channels.
            val category = entry.stringOrNull("category")
            if (category.equals("Premium", ignoreCase = true)) return@mapNotNull null
            LiveChannel(
                id = id,
                name = name,
                type = entry.stringOrNull("type") ?: type,
                addonUrl = baseUrl,
                poster = entry.stringOrNull("poster") ?: entry.stringOrNull("background"),
                description = entry.stringOrNull("description"),
                releaseInfo = entry.stringOrNull("releaseInfo"),
                category = category
            )
        // FayaTV repeats the same channel several times in one catalog; duplicate
        // ids also collide as Compose list keys.
        }.distinctBy { it.id }
    }

    private fun baseBuilder(baseUrl: String): okhttp3.HttpUrl? {
        val manifest = AddonSource.normalize(baseUrl) ?: return null
        val base = manifest.removeSuffix("/manifest.json")
        return base.toHttpUrlOrNull()
    }

    private fun resourceUrl(baseUrl: String, resource: String, type: String, id: String): String? {
        val builder = baseBuilder(baseUrl) ?: return null
        return builder.newBuilder()
            .addPathSegment(resource)
            .addPathSegment(type)
            .addPathSegment("$id.json")
            .build()
            .toString()
    }

    private fun catalogUrl(
        baseUrl: String,
        type: String,
        catalogId: String,
        extras: Map<String, String>,
        skip: Int
    ): String? {
        val builder = baseBuilder(baseUrl) ?: return null
        val segments = StringBuilder()
        extras.forEach { (name, value) ->
            // The addon's own option values win; skip is appended last.
            if (name.isBlank() || value.isBlank() || name == "skip") return@forEach
            if (segments.isNotEmpty()) segments.append('&')
            segments.append(encode(name)).append('=').append(encode(value))
        }
        if (skip > 0) {
            if (segments.isNotEmpty()) segments.append('&')
            segments.append("skip=").append(skip)
        }
        val path = builder.newBuilder().addPathSegment("catalog").addPathSegment(type)
        if (segments.isEmpty()) {
            path.addPathSegment("$catalogId.json")
        } else {
            // Values are already percent-encoded; addEncodedPathSegment avoids %2520.
            path.addPathSegment(catalogId).addEncodedPathSegment("$segments.json")
        }
        return runCatching { path.build().toString() }.getOrNull()
    }

    /** Percent-encodes an extra value for the catalog path; spaces stay `%20`. */
    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /**
     * Cheap liveness check run before handing a live link to the player. Addons
     * publish dead CDN links (404, or 403 on an expired `hdntl` token that is
     * cached inside their HLS master), so probing the master — and its first
     * variant for HLS — lets the app skip to a working mirror instead of
     * failing after the player has already opened.
     *
     * Redirects are followed manually so shortened links (jmp2.uk, bit.ly) are
     * identified as HLS from the final URL and their relative variants resolve
     * against the redirect target, not the short link.
     *
     * @return [StreamProbe.url] is the final absolute URL; [StreamProbe.error]
     *   is null when that URL looks playable.
     */
    suspend fun probeStream(
        url: String,
        headers: Map<String, String>,
        userAgent: String = Http.BROWSER_USER_AGENT
    ): StreamProbe =
        withContext(Dispatchers.IO) {
            val httpUrl = url.toHttpUrlOrNull()
                ?: return@withContext StreamProbe(url, "invalid stream URL")
            // The probe must look like the player: providers that whitelist an
            // app UA answer 403 to a browser UA, which used to read as a dead
            // link and blocked the tune before playback could even try.
            val requestHeaders = mapOf(
                "User-Agent" to userAgent.ifBlank { Http.BROWSER_USER_AGENT }
            ) + headers
            fun request(target: okhttp3.HttpUrl): Request = Request.Builder()
                .url(target)
                .header("Accept", "*/*")
                .apply { requestHeaders.forEach { (k, v) -> if (k.isNotBlank()) header(k, v) } }
                .build()
            runCatching {
                probeClient.awaitRedirects(request(httpUrl)).use { resolved ->
                    val response = resolved.response
                    if (response.code !in 200..299) {
                        return@withContext StreamProbe(
                            url,
                            "server replied HTTP ${response.code}",
                            response.code
                        )
                    }
                    val finalUrl = resolved.finalUrl
                    val finalHttp = finalUrl.toHttpUrlOrNull()
                        ?: return@withContext StreamProbe(url, "invalid redirect target")
                    val finalPath = finalHttp.encodedPath
                    val contentType = resolved.contentType.orEmpty()
                    val isHls = finalPath.endsWith(".m3u8", true) ||
                        contentType.contains("mpegurl", ignoreCase = true)
                    val isDash = finalPath.endsWith(".mpd", true) ||
                        contentType.contains("dash+xml", ignoreCase = true)
                    if (!isHls && !isDash) return@withContext StreamProbe(finalUrl)
                    val body = response.bodyStringCapped(512 * 1024)
                    if (isDash) {
                        // The manifest body carries <ContentProtection>/<ms:laurl>,
                        // which Media3 ignores; the caller parses it for DRM.
                        return@withContext if (body.isBlank()) {
                            StreamProbe(finalUrl, "empty manifest")
                        } else {
                            StreamProbe(finalUrl, manifest = body)
                        }
                    }
                    if (body.isBlank()) return@withContext StreamProbe(finalUrl, "empty playlist")
                    // Some CDNs answer with a header-only master (no variants) that
                    // would leave the player spinning forever.
                    if (!body.contains("#EXT-X-STREAM-INF") && !body.contains("#EXTINF")) {
                        return@withContext StreamProbe(finalUrl, "empty playlist")
                    }
                    // Media playlists point straight at segments; only masters list variants.
                    if (!body.contains("#EXT-X-STREAM-INF")) {
                        return@withContext StreamProbe(finalUrl, manifest = body)
                    }
                    val variant = body.lineSequence()
                        .map(String::trim)
                        .firstOrNull { it.startsWith("http") || (it.isNotBlank() && !it.startsWith("#")) }
                        ?: return@withContext StreamProbe(finalUrl, manifest = body)
                    val variantUrl = finalHttp.resolve(variant)
                        ?: return@withContext StreamProbe(finalUrl, manifest = body)
                    probeClient.awaitRedirects(request(variantUrl)).use { copy ->
                        if (copy.code !in 200..299) {
                            StreamProbe(url, "link expired (HTTP ${copy.code})", copy.code)
                        } else {
                            // Some packagers put SAMPLE-AES KEY tags only in the
                            // media playlist, not in the master.
                            val variantBody = copy.response.bodyStringCapped(256 * 1024)
                            val combined = if (variantBody.contains("#EXT-X-KEY", ignoreCase = true)) {
                                "$body\n$variantBody"
                            } else {
                                body
                            }
                            StreamProbe(finalUrl, manifest = combined)
                        }
                    }
                }
            }.getOrElse { t -> StreamProbe(url, t.message ?: "unreachable") }
        }

    /**
     * Final absolute URL of a short link, bounded like a probe. Returns [url]
     * untouched when the chain cannot be walked, so playback can still try the
     * link as given.
     */
    suspend fun resolveFinalUrl(
        url: String,
        headers: Map<String, String>,
        userAgent: String = Http.BROWSER_USER_AGENT
    ): String =
        withContext(Dispatchers.IO) {
            val httpUrl = url.toHttpUrlOrNull() ?: return@withContext url
            val request = Request.Builder()
                .url(httpUrl)
                .header("Accept", "*/*")
                .apply {
                    (mapOf("User-Agent" to userAgent.ifBlank { Http.BROWSER_USER_AGENT }) + headers)
                        .forEach { (k, v) -> if (k.isNotBlank()) header(k, v) }
                }
                .build()
            runCatching {
                probeClient.awaitRedirects(request).use { it.finalUrl }
            }.getOrDefault(url)
        }

    /**
     * Downloads an addon JSON reply with a hard byte cap. Huge addon payloads
     * (unbounded catalogs, broken mirrors serving HTML) must fail with a clean
     * error instead of exhausting the heap on a 32-bit TV box.
     */
    private suspend fun getText(
        url: String,
        maxBytes: Int,
        accept: String = "application/json,text/plain,*/*"
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Http.BROWSER_USER_AGENT)
            .header("Accept", accept)
            .build()
        Http.client.await(request).use { response ->
            val body = response.body
            if (response.code !in 200..299) {
                // Only read a small slice of the error page for a readable message.
                val snippet = body?.byteStream()?.readLimited(MAX_ERROR_BODY_BYTES) ?: ""
                throw IOException("HTTP ${response.code} from addon" + if (snippet.isNotBlank()) ": $snippet" else "")
            }
            val source = body ?: throw IOException("Empty response from addon")
            source.byteStream().readLimited(maxBytes)
        }
    }
}

/** 2 MiB is generous for a manifest; anything bigger is not a real addon. */
private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024

/** Stream/subtitle/catalog replies are capped at 4 MiB. */
private const val MAX_RESOURCE_BYTES = 4 * 1024 * 1024

private const val MAX_ERROR_BODY_BYTES = 4 * 1024

private const val MAX_SUBTITLE_TRACKS = 100
private const val MAX_CATALOG_CHANNELS = 500
private const val MAX_CATALOGS = 256
private const val MAX_WATCH_PAGE_BYTES = 1024 * 1024
private const val WATCH_PAGE_TTL_MS = 10 * 60 * 1000L

private const val WATCH_MEDIA_PATTERN =
    """["']?(?:fileUrl|videoUrl|streamUrl|playlistUrl|manifestUrl)["']?\s*[:=]\s*["'](https?://[^"'\s]+\.(?:mpd|m3u8|mp4)[^"'\s]*)["']"""
private const val WATCH_MEDIA_FALLBACK_PATTERN =
    """["']?(?:fileUrl|videoUrl|streamUrl|playlistUrl|manifestUrl)["']?\s*[:=]\s*["'](https?://[^"'\s]+)["']"""
private const val WATCH_KID_PATTERN =
    """["']?(?:kid|keyId)["']?\s*[:=]\s*["']([0-9a-f]{32})["']"""
private const val WATCH_KEY_PATTERN =
    """["']?(?:key|decryptionKey)["']?\s*[:=]\s*["']([0-9a-f]{32})["']"""
private const val WATCH_LICENSE_PATTERN =
    """["']?(playreadyLicenseUrl|widevineLicenseUrl|drmLicenseUrl|licenseServerUri|licenseServer|license_url|licenseUrl|licenceUrl|la_url|laurl)["']?\s*[:=]\s*["']((?:https?:)?//[^"'\s]+|/[^"'\s]+)["']"""
private const val WATCH_LAURL_ELEMENT_PATTERN =
    """<(?:[\w.-]+:)?laurl[^>]*>\s*([^<\s]+)\s*<"""
private const val DASH_PROTECTION_BLOCK_PATTERN =
    """<[\w.-]*:?ContentProtection\b[^>]*(?:/>|>.*?</[\w.-]*:?ContentProtection\s*>)"""
private const val DASH_SCHEME_URI_PATTERN = """schemeIdUri\s*=\s*["']([^"']+)["']"""
private const val HLS_KEY_TAG_PATTERN = """#EXT-X-(?:SESSION-)?KEY:([^\r\n]+)"""
private const val HLS_ATTRIBUTE_PATTERN = """([A-Za-z0-9-]+)=("[^"]*"|[^,]*)"""

private fun java.io.InputStream.readLimited(maxBytes: Int): String {
    val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val chunk = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        if (total >= maxBytes) throw IOException("Addon response exceeds ${maxBytes / 1024 / 1024} MiB limit")
        val read = read(chunk, 0, minOf(chunk.size, maxBytes - total))
        if (read < 0) break
        total += read
        out.write(chunk, 0, read)
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}

private fun JSONArray?.stringList(): List<String> {
    val arr = this ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
}

/** Extra option lists are strings, but some addons ship `{ "name": … }` objects. */
private fun JSONArray?.optionList(): List<String> {
    val arr = this ?: return emptyList()
    return (0 until arr.length()).mapNotNull { index ->
        arr.optString(index).takeIf { it.isNotBlank() }
            ?: arr.optJSONObject(index)?.stringOrNull("value")
            ?: arr.optJSONObject(index)?.stringOrNull("name")?.takeIf { it.isNotBlank() }
    }
}

private fun JSONObject.asMap(): Map<String, Any> = keys().asSequence().associateWith { key ->
    when (val value = opt(key)) {
        null, JSONObject.NULL -> ""
        is JSONObject -> value.asMap()
        is JSONArray -> (0 until value.length()).mapNotNull { index ->
            when (val entry = value.opt(index)) {
                is JSONObject -> entry.asMap()
                is String -> entry
                else -> null
            }
        }
        else -> value
    }
}
