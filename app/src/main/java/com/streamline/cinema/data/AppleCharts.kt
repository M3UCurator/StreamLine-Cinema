package com.streamline.cinema.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * Apple's public marketing RSS: `{storefront}/music/most-played/{limit}/songs.json`.
 * No key and no quota, and the storefront in the path is a real country filter.
 * Rows carry no video id, so ENTER hands them to a search rather than a deep
 * link. Genres are listed per row and filtered here, since the feed has no
 * genre parameter; a niche genre can therefore return fewer rows than [limit].
 */
class AppleChartsProvider(
    private val fetch: suspend (String) -> String = ::fetchAppleChart,
    private val cache: ChartCache = ChartCache()
) : ChartProvider {

    override val source = ChartSource.APPLE

    override suspend fun chart(request: ChartRequest, limit: Int): ChartResult {
        val capped = limit.coerceIn(1, MAX_CHART_ROWS)
        val key = "apple|${request.country.code}|${request.genre.id}|$capped"
        cache.get(key)?.let { return it }
        val body = fetch(appleChartUrl(request.country, capped))
        val rows = AppleChartJson.parse(body, request, capped)
        return ChartResult(request = request, rows = rows).also { cache.put(key, it) }
    }
}

internal fun appleChartUrl(country: ChartCountry, limit: Int): String =
    "https://rss.applemarketingtools.com/api/v2/${country.code.lowercase(Locale.ROOT)}" +
        "/music/most-played/$limit/songs.json"

internal object AppleChartJson {

    fun parse(body: String, request: ChartRequest, limit: Int): List<ChartTrack> {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: throw IOException("Apple returned an unreadable chart")
        val results = root.optJSONObject("feed")?.optJSONArray("results")
            ?: throw IOException("Apple returned no chart results")
        val rows = ArrayList<ChartTrack>(minOf(results.length(), limit))
        for (index in 0 until results.length()) {
            if (rows.size >= limit) break
            val item = results.optJSONObject(index) ?: continue
            val id = item.stringOrNull("id") ?: continue
            val title = item.stringOrNull("name") ?: continue
            val genres = genreNames(item)
            if (!request.genre.matchesAny(genres)) continue
            rows += ChartTrack(
                source = ChartSource.APPLE,
                id = "apple:$id",
                title = title,
                artist = item.optString("artistName"),
                artwork = appleArtwork(item.stringOrNull("artworkUrl100")),
                openUrl = item.stringOrNull("url")?.takeIf { it.startsWith("http") },
                genreLabel = genres.firstOrNull()
            )
        }
        return rows
    }

    private fun genreNames(item: JSONObject): List<String> {
        val array = item.optJSONArray("genres") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.stringOrNull("name")
        }
    }
}

/** Apple serves one size per URL; the music thumbnails top out at 600 px. */
internal fun appleArtwork(url: String?): String? {
    val clean = url?.trim()?.takeIf { it.startsWith("http") } ?: return null
    return clean.replace("/100x100bb", "/600x600bb")
}

private suspend fun fetchAppleChart(url: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", Http.BROWSER_USER_AGENT)
        .header("Accept", "application/json")
        .build()
    Http.client.await(request).use { response ->
        if (!response.isSuccessful) throw IOException("Apple charts HTTP ${response.code}")
        response.bodyStringCapped(MAX_BODY_BYTES)
    }
}

private const val MAX_BODY_BYTES = 1024 * 1024
