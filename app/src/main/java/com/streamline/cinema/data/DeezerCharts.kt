package com.streamline.cinema.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Deezer's public chart: `/chart/{genre_id}/tracks`, with the genre ids read
 * from `/genre` once per session so the app's labels resolve against the
 * source's own catalogue instead of a hard-coded table. There is no country
 * parameter — the chart follows the requester's IP — so the result always
 * carries the GLOBAL caveat. Genres Deezer does not publish (K-Pop, Country)
 * answer with a named note instead of an empty-looking chart.
 */
class DeezerChartsProvider(
    private val fetch: suspend (String) -> String = ::fetchDeezer,
    private val cache: ChartCache = ChartCache()
) : ChartProvider {

    override val source = ChartSource.DEEZER

    @Volatile
    private var genreIndex: Map<String, Int>? = null

    override suspend fun chart(request: ChartRequest, limit: Int): ChartResult {
        val capped = limit.coerceIn(1, MAX_CHART_ROWS)
        val key = "deezer|${request.genre.id}|$capped"
        cache.get(key)?.let { return it }

        val genreId = if (request.genre.id == ANY_GENRE_ID) {
            DEEZER_ALL_GENRE
        } else {
            resolveGenreId(request.genre)
        }
        if (genreId == null) {
            // Not cached: the genre index may have been fetched while Deezer was
            // mid-deploy, and a six-hour "no chart" would outlive the glitch.
            return ChartResult(
                request = request,
                rows = emptyList(),
                note = "Deezer has no \"${request.genre.label}\" chart in its genre list."
            )
        }

        val body = fetch(deezerChartUrl(genreId, capped))
        val result = ChartResult(
            request = request,
            rows = DeezerChartJson.parseTracks(body, capped),
            note = ChartSource.DEEZER.caveat
        )
        cache.put(key, result)
        return result
    }

    private suspend fun resolveGenreId(genre: ChartGenre): Int? {
        val index = genreIndex ?: loadGenreIndex().also { genreIndex = it }
        return index.entries.firstOrNull { genre.matches(it.key) }?.value
    }

    private suspend fun loadGenreIndex(): Map<String, Int> =
        DeezerChartJson.parseGenres(fetch(DEEZER_GENRE_URL))
}

internal fun deezerChartUrl(genreId: Int, limit: Int): String =
    "https://api.deezer.com/chart/$genreId/tracks?limit=$limit"

/** Genre 0 is Deezer's own "All". */
internal const val DEEZER_ALL_GENRE = 0

internal const val DEEZER_GENRE_URL = "https://api.deezer.com/genre"

internal object DeezerChartJson {

    /** Insertion order matters: the first matching catalogue name wins. */
    fun parseGenres(body: String): Map<String, Int> {
        val root = read(body, "Deezer returned an unreadable genre list")
        val data = root.optJSONArray("data")
            ?: throw IOException("Deezer returned no genre list")
        val genres = LinkedHashMap<String, Int>()
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val name = item.stringOrNull("name") ?: continue
            val id = item.optInt("id", -1).takeIf { it >= 0 } ?: continue
            genres[name] = id
        }
        if (genres.isEmpty()) throw IOException("Deezer returned an empty genre list")
        return genres
    }

    fun parseTracks(body: String, limit: Int): List<ChartTrack> {
        val root = read(body, "Deezer returned an unreadable chart")
        val data = root.optJSONArray("data")
            ?: throw IOException("Deezer returned no chart tracks")
        return (0 until data.length())
            .asSequence()
            .mapNotNull { index -> data.optJSONObject(index)?.let(::track) }
            .distinctBy { it.id }
            .take(limit)
            .toList()
    }

    private fun track(item: JSONObject): ChartTrack? {
        val id = item.optLong("id", 0L).takeIf { it > 0L } ?: return null
        val title = item.stringOrNull("title") ?: return null
        val album = item.optJSONObject("album")
        return ChartTrack(
            source = ChartSource.DEEZER,
            id = "deezer:$id",
            title = title,
            artist = item.optJSONObject("artist")?.stringOrNull("name").orEmpty(),
            album = album?.stringOrNull("title").orEmpty(),
            artwork = album?.stringOrNull("cover_big")?.takeIf { it.startsWith("http") },
            durationMs = item.optLong("duration", 0L).coerceAtLeast(0L) * 1000L,
            openUrl = item.stringOrNull("link")?.takeIf { it.startsWith("http") }
        )
    }

    /** Deezer answers failures as 200 + `{"error":{…}}`, so status alone is not enough. */
    private fun read(body: String, unreadable: String): JSONObject {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: throw IOException(unreadable)
        val error = root.optJSONObject("error") ?: return root
        val message = error.stringOrNull("message") ?: "unknown error"
        throw IOException("Deezer: $message")
    }
}

private suspend fun fetchDeezer(url: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", Http.BROWSER_USER_AGENT)
        .header("Accept", "application/json")
        .build()
    Http.client.await(request).use { response ->
        if (!response.isSuccessful) throw IOException("Deezer charts HTTP ${response.code}")
        response.bodyStringCapped(MAX_BODY_BYTES)
    }
}

private const val MAX_BODY_BYTES = 1024 * 1024
