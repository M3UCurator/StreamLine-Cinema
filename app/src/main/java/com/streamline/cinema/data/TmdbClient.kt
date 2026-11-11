package com.streamline.cinema.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class TmdbException(message: String) : IOException(message)

class TmdbClient(private val settingsStore: SettingsStore) {

    private val settings get() = settingsStore.settings.value

    private companion object {
        // Search fans out over the first few pages; TMDB serves 20 titles per page.
        const val SEARCH_PAGES = 5
        const val TMDB_PAGE_SIZE = 20
    }

    suspend fun search(query: String, filter: MediaType?, adult: Boolean): List<CatalogItem> {
        val path = when (filter) {
            MediaType.MOVIE -> "search/movie"
            MediaType.TV -> "search/tv"
            null -> "search/multi"
        }
        fun params(page: Int) = mapOf(
            "query" to query,
            "include_adult" to adult.toString(),
            "page" to page.toString()
        )
        val first = requestJson(path, params(1))
        val firstPage = parseResults(first.optJSONArray("results"), filter, "TMDB Search")
        // TMDB caps one page at 20 titles. Pull the next pages concurrently so a
        // search surfaces a browsable result set instead of a single screen;
        // extra pages are best effort and never fail the query as a whole.
        val lastPage = minOf(SEARCH_PAGES, first.optInt("total_pages", 1).coerceAtLeast(1))
        if (lastPage <= 1 || firstPage.size < TMDB_PAGE_SIZE) {
            return firstPage.distinctBy { it.key }
        }
        val extra = coroutineScope {
            (2..lastPage).map { page ->
                async { runCatching { requestJson(path, params(page)) }.getOrNull() }
            }.awaitAll()
        }
        return (firstPage + extra.filterNotNull().flatMap {
            parseResults(it.optJSONArray("results"), filter, "TMDB Search")
        }).distinctBy { it.key }
    }

    suspend fun trending(): List<CatalogItem> {
        val json = requestJson("trending/all/week", emptyMap())
        return parseResults(json.optJSONArray("results"), null, "TMDB Trending")
    }

    suspend fun discover(path: String, type: MediaType, page: Int = 1): List<CatalogItem> {
        val json = requestJson(path, mapOf("page" to page.toString()))
        return parseResults(json.optJSONArray("results"), type, "TMDB")
    }

    /**
     * TMDB's "more like this" list for one title. The path differs per media
     * type, and the response carries `media_type: movie` even for TV
     * recommendations, so the type is forced.
     */
    suspend fun recommendations(tmdbId: Int, type: MediaType): List<CatalogItem> {
        val path = if (type == MediaType.MOVIE) "movie/$tmdbId/recommendations"
        else "tv/$tmdbId/recommendations"
        val json = requestJson(path, mapOf("page" to "1"))
        return parseResults(json.optJSONArray("results"), type, "TMDB Recommendations")
            .distinctBy { it.key }
    }

    suspend fun preset(category: String): List<CatalogItem> {
        val (path, type) = ImportPresets.tmdbPathFor(category)
            ?: throw TmdbException("Unknown TMDB preset: $category")
        return discover(path, type)
    }

    suspend fun movieDetail(id: Int): CatalogItem {
        val o = requestJson(
            "movie/$id",
            mapOf("append_to_response" to "release_dates,external_ids,credits,images")
        )
        return parseMovieDetail(o)
    }

    suspend fun tvDetail(id: Int): CatalogItem {
        val o = requestJson(
            "tv/$id",
            mapOf("append_to_response" to "content_ratings,external_ids,credits,images")
        )
        return parseTvDetail(o)
    }

    suspend fun details(tmdbId: Int, type: MediaType): CatalogItem =
        if (type == MediaType.MOVIE) movieDetail(tmdbId) else tvDetail(tmdbId)

    suspend fun seasonEpisodes(tvId: Int, seasonNumber: Int): List<Episode> {
        val o = requestJson("tv/$tvId/season/$seasonNumber", emptyMap())
        val arr = o.optJSONArray("episodes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { ep ->
                Episode(
                    number = ep.optInt("episode_number"),
                    title = ep.optString("name", "Episode ${ep.optInt("episode_number")}"),
                    overview = ep.optString("overview"),
                    stillUrl = TmdbImages.still(ep.stringOrNull("still_path")),
                    airDate = ep.stringOrNull("air_date"),
                    runtimeMinutes = ep.intOrNull("runtime")
                )
            }
        }.sortedBy { it.number }
    }

    suspend fun list(listId: String, maxPages: Int = 5): List<CatalogItem> {
        val first = requestJson("list/$listId", mapOf("page" to "1"))
        val totalPages = first.optInt("total_pages", 1).coerceAtLeast(1)
        val pages = totalPages.coerceAtMost(maxPages)
        val items = parseResults(first.optJSONArray("items"), null, "TMDB List").toMutableList()
        for (page in 2..pages) {
            val next = requestJson("list/$listId", mapOf("page" to page.toString()))
            items += parseResults(next.optJSONArray("items"), null, "TMDB List")
        }
        return items.distinctBy { it.key }
    }

    suspend fun testConnection(): String {
        val o = requestJson("configuration", emptyMap())
        val imageBase = o.optJSONObject("images")?.optString("secure_base_url").orEmpty()
        if (imageBase.isBlank()) throw TmdbException("TMDB responded, but the configuration payload was empty")
        return "Connected · TMDB API ready"
    }

    private suspend fun requestJson(path: String, params: Map<String, String>): JSONObject {
        val current = settings
        val proxy = current.proxyBaseUrl.trim().trimEnd('/')
        var proxyFailure: Throwable? = null

        if (proxy.isNotEmpty()) {
            try {
                return execute(buildProxyRequest(proxy, path, params, current.tmdbApiKey))
            } catch (t: Throwable) {
                proxyFailure = t
                if (current.tmdbApiKey.isBlank()) {
                    throw TmdbException("Proxy unreachable (${friendly(t)}). Add a TMDB API key as a fallback.")
                }
            }
        }

        if (current.tmdbApiKey.isBlank()) {
            throw TmdbException(
                if (proxyFailure != null) "Proxy unreachable (${friendly(proxyFailure)})."
                else "Add your TMDB API key (or a proxy URL) in Settings."
            )
        }

        return try {
            execute(buildDirectRequest(path, params, current.tmdbApiKey))
        } catch (t: Throwable) {
            throw TmdbException(friendly(t))
        }
    }

    private fun buildDirectRequest(path: String, params: Map<String, String>, apiKey: String): Request {
        val url = "https://api.themoviedb.org/3".toHttpUrl().newBuilder()
        path.trim('/').split('/').filter { it.isNotBlank() }.forEach { url.addPathSegment(it) }
        params.forEach { (key, value) -> url.addQueryParameter(key, value) }
        if (!params.containsKey("language")) url.addQueryParameter("language", settings.tmdbLanguageTag())
        if (!apiKey.startsWith("eyJ")) {
            url.addQueryParameter("api_key", apiKey)
        }
        val builder = Request.Builder()
            .url(url.build())
            .header("Accept", "application/json")
            .header("User-Agent", Http.BROWSER_USER_AGENT)
        if (apiKey.startsWith("eyJ")) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        return builder.build()
    }

    private fun buildProxyRequest(
        proxy: String,
        path: String,
        params: Map<String, String>,
        apiKey: String
    ): Request {
        val url = "$proxy/api/tmdb".toHttpUrl().newBuilder()
        url.addQueryParameter("path", path.trim('/'))
        params.forEach { (key, value) -> url.addQueryParameter(key, value) }
        if (!params.containsKey("language")) url.addQueryParameter("language", settings.tmdbLanguageTag())
        val builder = Request.Builder()
            .url(url.build())
            .header("Accept", "application/json")
            .header("User-Agent", Http.BROWSER_USER_AGENT)
        if (apiKey.isNotBlank()) builder.header("X-TMDB-Key", apiKey)
        return builder.build()
    }

    private suspend fun execute(request: Request): JSONObject {
        var attempt = 0
        while (true) {
            val response = Http.client.await(request)
            val code = response.code
            val body = response.bodyStringCapped(256 * 1024)
            response.close()
            if (code == 429 && attempt < 2) {
                attempt++
                delay(1_200L * attempt)
                continue
            }
            if (code !in 200..299) {
                val message = runCatching { JSONObject(body).optString("status_message") }.getOrNull()
                throw TmdbException(
                    "TMDB HTTP $code" + if (!message.isNullOrBlank()) ": $message" else ""
                )
            }
            if (body.isBlank()) throw TmdbException("Empty response from TMDB")
            return runCatching { JSONObject(body) }
                .getOrElse { throw TmdbException("Malformed TMDB response") }
        }
    }

    private fun parseResults(
        results: JSONArray?,
        forcedType: MediaType?,
        source: String
    ): List<CatalogItem> {
        if (results == null) return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val o = results.optJSONObject(index) ?: return@mapNotNull null
            val mediaType = if (forcedType != null) forcedType else {
                when (o.optString("media_type")) {
                    "movie" -> MediaType.MOVIE
                    "tv" -> MediaType.TV
                    // Some curated lists omit media_type; infer it from the payload shape.
                    else -> when {
                        o.has("title") || o.has("release_date") -> MediaType.MOVIE
                        o.has("name") || o.has("first_air_date") -> MediaType.TV
                        else -> return@mapNotNull null
                    }
                }
            }
            val id = o.optInt("id")
            if (id <= 0) return@mapNotNull null
            val title = if (mediaType == MediaType.MOVIE) o.optString("title")
            else o.optString("name", o.optString("original_name"))
            val date = if (mediaType == MediaType.MOVIE) o.optString("release_date")
            else o.optString("first_air_date")
            CatalogItem(
                tmdbId = id,
                type = mediaType,
                title = title.ifBlank { "Untitled" },
                year = date.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) },
                releaseDate = date.takeIf { it.isNotBlank() },
                overview = o.optString("overview"),
                posterUrl = TmdbImages.poster(o.stringOrNull("poster_path")),
                backdropUrl = TmdbImages.backdrop(o.stringOrNull("backdrop_path")),
                rating = o.doubleOrNull("vote_average")?.takeIf { it > 0 },
                voteCount = o.intOrNull("vote_count"),
                originalLanguage = o.optString("original_language"),
                genres = Genres.names(mediaType, o.optJSONArray("genre_ids")?.let { arr ->
                    (0 until arr.length()).map { arr.optInt(it) }
                }.orEmpty()),
                source = source,
                tmdbLoaded = false
            )
        }
    }

    private fun parseMovieDetail(o: JSONObject): CatalogItem {
        val id = o.optInt("id")
        val genres = Genres.names(MediaType.MOVIE, o.optJSONArray("genres"))
        val cert = movieCertification(o)
        return CatalogItem(
            tmdbId = id,
            type = MediaType.MOVIE,
            title = o.optString("title", "Untitled"),
            year = o.optString("release_date").take(4).takeIf { it.length == 4 },
            releaseDate = o.stringOrNull("release_date"),
            overview = o.optString("overview"),
            tagline = o.optString("tagline"),
            posterUrl = TmdbImages.poster(o.stringOrNull("poster_path")),
            backdropUrl = TmdbImages.backdrop(o.stringOrNull("backdrop_path")),
            imdbId = o.optJSONObject("external_ids")?.optString("imdb_id").orEmpty(),
            rating = o.doubleOrNull("vote_average")?.takeIf { it > 0 },
            voteCount = o.intOrNull("vote_count"),
            certification = cert,
            originalLanguage = o.optString("original_language"),
            genres = genres,
            runtimeMinutes = o.intOrNull("runtime"),
            logoUrl = titleLogo(o),
            cast = topCast(o),
            director = crewNames(o, "Director"),
            tmdbLoaded = true,
            source = "TMDB"
        )
    }

    private fun parseTvDetail(o: JSONObject): CatalogItem {
        val id = o.optInt("id")
        val genres = Genres.names(MediaType.TV, o.optJSONArray("genres"))
        val seasons = o.optJSONArray("seasons")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val s = arr.optJSONObject(i) ?: return@mapNotNull null
                val number = s.optInt("season_number", -1)
                if (number < 0) return@mapNotNull null
                Season(
                    number = number,
                    name = s.optString("name", "Season $number"),
                    episodeCount = s.optInt("episode_count"),
                    airDate = s.stringOrNull("air_date"),
                    posterUrl = TmdbImages.poster(s.stringOrNull("poster_path"))
                )
            }.sortedBy { it.number }
        }.orEmpty()
        val runtime = o.optJSONArray("episode_run_time")?.let { arr ->
            if (arr.length() > 0) arr.optInt(0) else null
        }
        return CatalogItem(
            tmdbId = id,
            type = MediaType.TV,
            title = o.optString("name", "Untitled"),
            year = o.optString("first_air_date").take(4).takeIf { it.length == 4 },
            releaseDate = o.stringOrNull("first_air_date"),
            overview = o.optString("overview"),
            tagline = o.optString("tagline"),
            posterUrl = TmdbImages.poster(o.stringOrNull("poster_path")),
            backdropUrl = TmdbImages.backdrop(o.stringOrNull("backdrop_path")),
            imdbId = o.optJSONObject("external_ids")?.optString("imdb_id").orEmpty(),
            rating = o.doubleOrNull("vote_average")?.takeIf { it > 0 },
            voteCount = o.intOrNull("vote_count"),
            certification = tvCertification(o),
            originalLanguage = o.optString("original_language"),
            genres = genres,
            runtimeMinutes = runtime,
            logoUrl = titleLogo(o),
            cast = topCast(o),
            director = tvCreator(o),
            seasons = seasons,
            tmdbLoaded = true,
            source = "TMDB"
        )
    }

    /** TMDB's `credits.cast` arrives in billing order; the first eight are enough for a detail page. */
    private fun topCast(o: JSONObject, limit: Int = 8): List<String> {
        val cast = o.optJSONObject("credits")?.optJSONArray("cast") ?: return emptyList()
        return (0 until cast.length().coerceAtMost(limit))
            .mapNotNull { i -> cast.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() } }
    }

    private fun crewNames(o: JSONObject, job: String): String {
        val crew = o.optJSONObject("credits")?.optJSONArray("crew") ?: return ""
        val names = mutableListOf<String>()
        for (i in 0 until crew.length()) {
            val member = crew.optJSONObject(i) ?: continue
            if (!member.optString("job").equals(job, ignoreCase = true)) continue
            val name = member.optString("name")
            if (name.isNotBlank() && name !in names) names += name
        }
        return names.take(3).joinToString(", ")
    }

    private fun tvCreator(o: JSONObject): String =
        o.optJSONArray("created_by")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
            }.distinct().take(3).joinToString(", ")
        }.orEmpty()

    /** Prefer a logo in the content language, then English, then the best voted. */
    private fun titleLogo(o: JSONObject): String? {
        val logos = o.optJSONObject("images")?.optJSONArray("logos") ?: return null
        val candidates = (0 until logos.length()).mapNotNull { i ->
            val logo = logos.optJSONObject(i) ?: return@mapNotNull null
            val path = logo.stringOrNull("file_path") ?: return@mapNotNull null
            Triple(path, logo.optString("iso_639_1"), logo.optDouble("vote_average", 0.0))
        }
        val wanted = settings.contentLanguage.ifBlank { "en" }
        val best = candidates.firstOrNull { it.second == wanted && it.third >= 0 }
            ?: candidates.firstOrNull { it.second == "en" && it.third >= 0 }
            ?: candidates.maxByOrNull { it.third }
        return TmdbImages.logo(best?.first)
    }

    /** Prefer the selected content region, then the US rating, then whatever exists. */
    private fun movieCertification(o: JSONObject): String? {
        val results = o.optJSONObject("release_dates")?.optJSONArray("results") ?: return null
        val wanted = settings.contentCountry.ifBlank { "US" }
        val releases = mutableListOf<JSONObject>()
        for (i in 0 until results.length()) {
            val entry = results.optJSONObject(i) ?: continue
            val dates = entry.optJSONArray("release_dates") ?: continue
            for (j in 0 until dates.length()) {
                val date = dates.optJSONObject(j) ?: continue
                val cert = date.optString("certification")
                if (cert.isNotBlank()) {
                    releases += JSONObject()
                        .put("country", entry.optString("iso_3166_1"))
                        .put("cert", cert)
                }
            }
        }
        return releases.firstOrNull { it.optString("country") == wanted }
            ?.optString("cert")
            ?: releases.firstOrNull { it.optString("country") == "US" }
                ?.optString("cert")
            ?: releases.firstOrNull()?.optString("cert")
    }

    private fun tvCertification(o: JSONObject): String? {
        val results = o.optJSONObject("content_ratings")?.optJSONArray("results") ?: return null
        val wanted = settings.contentCountry.ifBlank { "US" }
        val ratings = (0 until results.length()).mapNotNull { i ->
            results.optJSONObject(i)?.let { it.optString("iso_3166_1") to it.optString("rating") }
        }
        return (ratings.firstOrNull { it.first == wanted } ?: ratings.firstOrNull { it.first == "US" })
            ?.second
            ?.takeIf { it.isNotBlank() }
    }

    private fun friendly(t: Throwable): String = when (t) {
        is TmdbException -> t.message ?: "TMDB request failed"
        is UnknownHostException -> "no network or DNS failure"
        is SocketTimeoutException -> "request timed out"
        is IOException -> t.message ?: "network error"
        else -> t.message ?: t.javaClass.simpleName
    }
}
