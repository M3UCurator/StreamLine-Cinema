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

object StreamUrls {

    /**
     * Splits `http://host/live.m3u8|User-Agent=...&Referer=...` into a clean URL and
     * the request headers it carries. Values are URL-decoded, so tokens that need
     * `%26` or spaces survive the pipe form.
     */
    fun parse(raw: String): ParsedStream {
        val trimmed = raw.trim()
        val pipe = trimmed.indexOf('|')
        if (pipe <= 0) return ParsedStream(trimmed)
        val url = trimmed.substring(0, pipe).trim()
        val headers = LinkedHashMap<String, String>()
        trimmed.substring(pipe + 1)
            .split('&')
            .forEach { chunk ->
                val eq = chunk.indexOf('=')
                if (eq <= 0) return@forEach
                val name = decode(chunk.substring(0, eq).trim())
                val value = decode(chunk.substring(eq + 1).trim())
                if (name.isNotBlank() && value.isNotBlank()) headers[name] = value
            }
        return ParsedStream(url, headers)
    }

    /** Stream URL without the pipe suffix — safe to show, log or hand to another app. */
    fun clean(raw: String): String = parse(raw).url

    /** Re-attaches pipe headers to a URL that was resolved elsewhere (redirects). */
    fun withHeaders(url: String, headers: Map<String, String>): String {
        if (headers.isEmpty()) return url
        val suffix = headers.entries.joinToString("&") { (name, value) ->
            name + "=" + java.net.URLEncoder.encode(value, "UTF-8")
        }
        return "$url|$suffix"
    }

    fun isDirectMedia(raw: String): Boolean {
        val clean = parse(raw).url.substringBefore('?').substringBefore('#').lowercase()
        return clean.endsWith(".mp4") || clean.endsWith(".mkv") || clean.endsWith(".webm") ||
            clean.endsWith(".m3u8") || clean.endsWith(".mpd") || clean.endsWith(".mov") ||
            clean.endsWith(".avi") || clean.endsWith(".flv") || clean.endsWith(".ts")
    }

    /** Case-insensitive header merge: explicit (pipe/addon) values win over defaults. */
    fun mergeHeaders(first: Map<String, String>, second: Map<String, String>): Map<String, String> {
        val merged = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        merged.putAll(first)
        merged.putAll(second)
        return merged
    }

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
