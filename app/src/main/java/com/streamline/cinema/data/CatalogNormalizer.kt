package com.streamline.cinema.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object CatalogNormalizer {

    fun parseJson(text: String, hint: MediaType? = null): List<CatalogItem> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw IOException("The import source returned an empty document")
        val root: Any = runCatching {
            if (trimmed.startsWith("[")) JSONArray(trimmed) as Any else JSONObject(trimmed) as Any
        }.getOrElse { throw IOException("Import source is not valid JSON") }

        val items = mutableListOf<CatalogItem>()
        if (root is JSONArray) {
            items += parseArray(root, hint)
        } else if (root is JSONObject) {
            val arrays = listOf(
                "items" to root.optJSONArray("items"),
                "results" to root.optJSONArray("results"),
                "data" to root.optJSONArray("data")
            )
            var found = false
            arrays.forEach { (_, array) ->
                if (array != null) {
                    found = true
                    items += parseArray(array, hint)
                }
            }
            root.optJSONArray("movies")?.let { found = true; items += parseArray(it, MediaType.MOVIE) }
            root.optJSONArray("shows")?.let { found = true; items += parseArray(it, MediaType.TV) }
            if (!found) {
                parseObject(root, hint)?.let { items += it }
            }
        }
        return items.distinctBy { it.key }
    }

    private fun parseArray(array: JSONArray, hint: MediaType?): List<CatalogItem> =
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { parseObject(it, hint) }
        }

    fun parseObject(o: JSONObject, hint: MediaType? = null): CatalogItem? {
        val rawType = o.stringOrNull("media_type") ?: o.stringOrNull("type") ?: o.stringOrNull("kind")
        if (rawType.equals("person", ignoreCase = true)) return null

        val type = when {
            rawType != null && (rawType.contains("tv", true) || rawType.contains("show", true) ||
                rawType.contains("series", true)) -> MediaType.TV
            rawType != null && rawType.contains("movie", true) -> MediaType.MOVIE
            hint != null -> hint
            o.has("first_air_date") || o.has("name") -> MediaType.TV
            else -> MediaType.MOVIE
        }

        val tmdbId = o.intOrNull("tmdb_id")
            ?: o.intOrNull("tmdbId")
            ?: o.intOrNull("tmdb")
            ?: o.intOrNull("id")
            ?: return null
        if (tmdbId <= 0) return null

        val title = sequenceOf(
            o.stringOrNull("title"),
            o.stringOrNull("name"),
            o.stringOrNull("original_title"),
            o.stringOrNull("original_name")
        ).firstOrNull().orEmpty().ifBlank { "Untitled" }

        val rawDate = o.stringOrNull("release_date") ?: o.stringOrNull("first_air_date")
        val year = o.stringOrNull("year")
            ?: o.stringOrNull("release_year")
            ?: (o.intOrNull("year")?.toString())
            ?: rawDate?.take(4)
            ?: o.stringOrNull("date")?.take(4)

        val poster = o.stringOrNull("posterUrl")
            ?: o.stringOrNull("poster_url")
            ?: o.stringOrNull("poster")
            ?: o.stringOrNull("poster_path")
            ?: o.stringOrNull("image")
        val backdrop = o.stringOrNull("backdropUrl")
            ?: o.stringOrNull("backdrop_url")
            ?: o.stringOrNull("backdrop")
            ?: o.stringOrNull("backdrop_path")

        val genres = parseGenres(o, type)
        val seasons = parseSeasons(o)
        val hasEpisodes = seasons.any { it.episodes.isNotEmpty() }

        return CatalogItem(
            tmdbId = tmdbId,
            type = type,
            title = title,
            year = year?.take(4)?.takeIf { it.length == 4 },
            releaseDate = rawDate ?: o.stringOrNull("date"),
            overview = o.stringOrNull("overview") ?: o.stringOrNull("description")
                ?: o.stringOrNull("synopsis") ?: "",
            tagline = o.stringOrNull("tagline") ?: "",
            posterUrl = TmdbImages.poster(poster),
            backdropUrl = TmdbImages.backdrop(backdrop),
            rating = o.doubleOrNull("rating") ?: o.doubleOrNull("vote_average"),
            voteCount = o.intOrNull("vote_count"),
            certification = o.stringOrNull("certification") ?: o.stringOrNull("rated"),
            genres = genres,
            runtimeMinutes = o.intOrNull("runtime"),
            seasons = seasons,
            streamUrl = o.stringOrNull("stream_url") ?: o.stringOrNull("streamUrl") ?: "",
            tmdbLoaded = o.optBoolean("tmdb_loaded", false) || hasEpisodes,
            source = o.stringOrNull("source") ?: "Import",
            addedAt = System.currentTimeMillis()
        )
    }

    private fun parseGenres(o: JSONObject, type: MediaType): List<String> {
        val array = o.optJSONArray("genres") ?: o.optJSONArray("genre_ids")
        if (array != null) {
            val names = mutableListOf<String>()
            val ids = mutableListOf<Int>()
            for (i in 0 until array.length()) {
                when (val value = array.opt(i)) {
                    is JSONObject -> value.stringOrNull("name")?.let { names += it }
                    is String -> value.takeIf { it.isNotBlank() }?.let { names += it }
                    is Number -> ids += value.toInt()
                }
            }
            if (ids.isNotEmpty()) names += Genres.names(type, ids)
            if (names.isNotEmpty()) return names.distinct()
        }
        val raw = o.stringOrNull("genre")
        if (raw != null) return raw.split(',', '/').map { it.trim() }.filter { it.isNotEmpty() }
        return emptyList()
    }

    private fun parseSeasons(o: JSONObject): List<Season> {
        val array = o.optJSONArray("seasons") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val s = array.optJSONObject(index) ?: return@mapNotNull null
            val number = s.intOrNull("number")
                ?: s.intOrNull("season_number")
                ?: return@mapNotNull null
            val episodes = s.optJSONArray("episodes")?.let { eps ->
                (0 until eps.length()).mapNotNull { i ->
                    val ep = eps.optJSONObject(i) ?: return@mapNotNull null
                    val epNumber = ep.intOrNull("number") ?: ep.intOrNull("episode_number")
                        ?: return@mapNotNull null
                    Episode(
                        number = epNumber,
                        title = ep.stringOrNull("title") ?: ep.stringOrNull("name")
                            ?: "Episode $epNumber",
                        overview = ep.stringOrNull("overview") ?: "",
                        stillUrl = TmdbImages.still(
                            ep.stringOrNull("still_path") ?: ep.stringOrNull("still")
                                ?: ep.stringOrNull("stillUrl")
                        ),
                        airDate = ep.stringOrNull("air_date") ?: ep.stringOrNull("airDate"),
                        runtimeMinutes = ep.intOrNull("runtime"),
                        streamUrl = ep.stringOrNull("stream_url") ?: ep.stringOrNull("streamUrl") ?: ""
                    )
                }.sortedBy { it.number }
            }.orEmpty()
            Season(
                number = number,
                name = s.stringOrNull("name") ?: "Season $number",
                episodeCount = s.intOrNull("episode_count") ?: episodes.size,
                airDate = s.stringOrNull("air_date"),
                posterUrl = TmdbImages.poster(
                    s.stringOrNull("poster_path") ?: s.stringOrNull("poster")
                ),
                episodes = episodes
            )
        }.sortedBy { it.number }
    }
}

/** A stream URL split from the optional `|Header=value&Header2=value` suffix. */
data class ParsedStream(val url: String, val headers: Map<String, String> = emptyMap())

/** Container family a source URL serves, decided before the player opens it. */
enum class StreamKind { HLS, DASH, SMOOTH }

object StreamUrls {

    /**
     * Container family from the strongest evidence available: a real media
     * extension in the path, then the response Content-Type, then the manifest
     * prefix. Token/short-link endpoints (`/play/abc123`, `.php`) carry no
     * extension, and Media3's own heuristic would treat them as progressive
     * media and fail with UnrecognizedInputFormatException.
     */
    fun kindOf(
        url: String?,
        contentType: String? = null,
        manifestPrefix: String? = null
    ): StreamKind? {
        val clean = url?.substringBefore('?')?.substringBefore('#')?.lowercase().orEmpty()
        val leaf = clean.substringAfterLast('/')
        when {
            clean.endsWith(".m3u8") || clean.endsWith(".m3u") -> return StreamKind.HLS
            clean.endsWith(".mpd") -> return StreamKind.DASH
            leaf == "manifest" || clean.endsWith(".ism") || clean.endsWith(".ismc") ->
                return StreamKind.SMOOTH
        }
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        when {
            type.contains("mpegurl") -> return StreamKind.HLS
            type.contains("dash+xml") -> return StreamKind.DASH
            type.contains("smoothstreaming") || type.contains("vnd.ms-sstr") ->
                return StreamKind.SMOOTH
        }
        val prefix = manifestPrefix?.trimStart()?.take(2048).orEmpty()
        return when {
            prefix.startsWith("#EXTM3U") -> StreamKind.HLS
            prefix.startsWith("<?xml") && prefix.contains("<MPD", ignoreCase = true) ->
                StreamKind.DASH
            prefix.contains("<SmoothStreamingMedia", ignoreCase = true) -> StreamKind.SMOOTH
            else -> null
        }
    }

    /**
     * True when neither the path nor [StreamKind] can name the container, so a
     * redirect walk is the only way to learn what the endpoint really serves.
     * Known progressive files (`.mp4`, `.mkv`, …) never need that round trip.
     */
    fun needsKindDiscovery(raw: String): Boolean {
        val url = parse(raw).url
        return kindOf(url) == null && !isDirectMedia(url)
    }

    /**
     * Splits `http://host/live.m3u8|User-Agent=...&Referer=...` into a clean URL
     * and the request headers it carries.
     *
     * The suffix is parsed defensively because it comes from playlists and
     * addons the app does not control:
     *  - fields may be separated by `&` or a second `|`, and only a chunk that
     *    starts like `Name=` starts a new field, so an `&` followed by plain
     *    text stays part of the value (`&` inside a token must be written as
     *    `%26`, as with any URL);
     *  - names and values are trimmed, surrounding quotes are removed and
     *    `%XX` runs are decoded (a literal `+` stays a `+`, as base64 tokens
     *    require);
     *  - anything OkHttp would reject — a non-token name, non-printable value,
     *    or a restricted header such as `Host`/`Connection` — is dropped
     *    instead of turning the whole request into an exception;
     *  - duplicate names differing only in case collapse to the last value.
     *
     * A pipe that yields no usable header is left in the URL, so a playlist
     * entry whose path contains `|` is not silently truncated. Never throws.
     */
    fun parse(raw: String): ParsedStream {
        val trimmed = raw.trim()
        val first = trimmed.indexOf('|')
        if (first <= 0) return ParsedStream(trimmed)
        var index = first
        while (index > 0) {
            val headers = headerFields(trimmed.substring(index + 1))
            if (headers.isNotEmpty()) {
                return ParsedStream(trimmed.substring(0, index).trim(), headers)
            }
            index = trimmed.indexOf('|', index + 1)
        }
        val rest = trimmed.substring(first + 1)
        return ParsedStream(if (rest.isBlank()) trimmed.substring(0, first).trim() else trimmed)
    }

    /** Stream URL without the pipe suffix — safe to show, log or hand to another app. */
    fun clean(raw: String): String = parse(raw).url

    /** Re-attaches pipe headers to a URL that was resolved elsewhere (redirects). */
    fun withHeaders(url: String, headers: Map<String, String>): String {
        val clean = sanitize(headers)
        if (clean.isEmpty()) return url
        val suffix = clean.entries.joinToString("&") { (name, value) ->
            // %20 rather than '+' so parse()/decode() can keep '+' literal.
            name + "=" + java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        }
        return "$url|$suffix"
    }

    fun isDirectMedia(raw: String): Boolean {
        val clean = parse(raw).url.substringBefore('?').substringBefore('#').lowercase()
        return clean.endsWith(".mp4") || clean.endsWith(".mkv") || clean.endsWith(".webm") ||
            clean.endsWith(".m3u8") || clean.endsWith(".mpd") || clean.endsWith(".mov") ||
            clean.endsWith(".avi") || clean.endsWith(".flv") || clean.endsWith(".ts")
    }

    /**
     * Case-insensitive header merge: explicit (pipe/addon) values win over
     * defaults. Both sides are sanitised, so an addon-supplied map cannot hand
     * the player a header OkHttp would throw on either.
     */
    fun mergeHeaders(first: Map<String, String>, second: Map<String, String>): Map<String, String> {
        val merged = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        merged.putAll(sanitize(first))
        merged.putAll(sanitize(second))
        return merged
    }

    /** Drops headers the data source cannot send and repairs the rest. */
    fun sanitize(headers: Map<String, String>): Map<String, String> {
        val clean = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        headers.forEach { (rawName, rawValue) ->
            val name = headerName(rawName) ?: return@forEach
            val value = headerValue(rawValue) ?: return@forEach
            clean[name] = value
        }
        return clean
    }

    private fun headerFields(raw: String): Map<String, String> =
        sanitize(
            buildMap {
                FIELD_BREAK.split(raw).forEach { chunk ->
                    val separator = chunk.indexOfFirst { it == '=' || it == ':' }
                    if (separator > 0) {
                        put(decode(chunk.substring(0, separator)), decode(chunk.substring(separator + 1)))
                    }
                }
            }
        )

    /** RFC 7230 token, i.e. what OkHttp accepts as a header name. */
    private fun headerName(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty() || !HEADER_TOKEN.matches(name)) return null
        if (name.lowercase(java.util.Locale.US) in RESERVED_HEADERS) return null
        return name
    }

    /**
     * OkHttp only allows printable ASCII in a header value; control characters
     * and anything above 0x7E are stripped rather than failing the request.
     */
    private fun headerValue(raw: String): String? =
        raw.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .filter { it in '\u0020'..'\u007e' }
            .trim()
            .ifEmpty { null }

    /**
     * Percent-decodes `%XX` runs and leaves a literal `+` alone — unlike
     * URLDecoder, which would turn base64 padding into spaces.
     */
    private fun decode(raw: String): String {
        if ('%' !in raw) return raw
        return runCatching {
            java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(raw)
    }

    /**
     * A field boundary is `&` or a second `|`, but only where the next chunk
     * starts like `Name=`; `&` inside an unencoded query keeps its place.
     */
    private val FIELD_BREAK = Regex("[&|]\\s*(?=[A-Za-z0-9!#$%'*+.^_~-]+\\s*[=:])")

    private val HEADER_TOKEN = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")

    /** DefaultHttpDataSource throws on these; OkHttp refuses some of them too. */
    private val RESERVED_HEADERS = setOf(
        "host",
        "connection",
        "proxy-connection",
        "upgrade",
        "keep-alive",
        "content-length",
        "transfer-encoding",
        "trailer",
        "te"
    )
}
