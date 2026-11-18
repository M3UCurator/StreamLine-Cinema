package com.streamline.cinema.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * The official YouTube Data API v3, used for metadata only.
 *
 * `videos.list?chart=mostPopular` with the Music category is a real per-country
 * chart and costs one unit per call. `search.list` costs a hundred units, so it
 * is reserved for a hand-off the user actually asked for and capped per session
 * (see [MAX_SEARCH_CALLS_PER_SESSION]). Every call needs a user-supplied key;
 * there is no shared or built-in key anywhere in this app.
 */
class YouTubeChartsProvider(
    private val keyProvider: () -> String,
    private val fetch: suspend (String) -> String = ::fetchYouTube,
    private val probe: suspend (String) -> YouTubeProbe = ::probeYouTube,
    private val cache: ChartCache = ChartCache()
) : ChartProvider, ChartSearchProvider {

    override val source = ChartSource.YOUTUBE

    private val searchesUsed = AtomicInteger(0)

    override suspend fun chart(request: ChartRequest, limit: Int): ChartResult {
        val capped = limit.coerceIn(1, MAX_CHART_ROWS)
        val key = "youtube|chart|${request.country.code}|$capped"
        cache.get(key)?.let { return it }

        val body = fetch(youtubeChartUrl(request.country, capped, requireKey()))
        val rows = YouTubeJson.parseVideos(body, capped)
        return ChartResult(request, rows, ChartSource.YOUTUBE.caveat).also { cache.put(key, it) }
    }

    override suspend fun search(query: String, country: ChartCountry, limit: Int): List<ChartTrack> {
        val clean = query.trim()
        if (clean.isEmpty()) return emptyList()
        val capped = limit.coerceIn(1, MAX_CHART_ROWS)
        val key = "youtube|search|${country.code}|${clean.lowercase(Locale.ROOT)}|$capped"
        cache.get(key)?.let { return it.rows }

        // Resolve the key first: a missing key must not spend a search slot.
        val apiKey = requireKey()
        reserveSearch()
        val body = fetch(youtubeSearchUrl(clean, country, capped, apiKey))
        val rows = YouTubeJson.parseSearch(body, capped)
        cache.put(key, ChartResult(ChartRequest(ChartSource.YOUTUBE, country), rows))
        return rows
    }

    fun searchBudgetLeft(): Int = MAX_SEARCH_CALLS_PER_SESSION - searchesUsed.get()

    /**
     * A search costs 1% of the default daily quota, so the app stops offering
     * them rather than silently burning the rest of the day's allowance.
     */
    private fun reserveSearch() {
        while (true) {
            val used = searchesUsed.get()
            if (used >= MAX_SEARCH_CALLS_PER_SESSION) {
                throw IOException(
                    "YouTube search budget used up for this session " +
                        "($MAX_SEARCH_CALLS_PER_SESSION searches); restart the app to reset it."
                )
            }
            if (searchesUsed.compareAndSet(used, used + 1)) return
        }
    }

    /** One unit against `videoCategories.list`, the cheapest call that proves a key. */
    suspend fun validateKey(apiKey: String = keyProvider().trim()): KeyCheck {
        if (apiKey.isEmpty()) return KeyCheck.Missing
        val answer = probe(youtubeCategoryProbeUrl(apiKey))
        if (answer.code == 0) {
            return KeyCheck.Failed("The device could not reach YouTube.")
        }
        if (answer.code in 200..299) return KeyCheck.Valid
        val reason = YouTubeJson.errorReason(answer.body)
        return when (reason) {
            "quotaExceeded" -> KeyCheck.Failed("The key works, but its daily quota is used up.")
            "accessNotConfigured", "SERVICE_DISABLED" ->
                KeyCheck.Failed("The YouTube Data API is not enabled for this key.")
            "keyInvalid", "API_KEY_INVALID", "badRequest", "ipRefererBlocked", "ipLocation" ->
                KeyCheck.Invalid
            else -> KeyCheck.Failed("YouTube answered HTTP ${answer.code}.")
        }
    }

    private fun requireKey(): String {
        val key = keyProvider().trim()
        if (key.isEmpty()) throw IOException(MISSING_KEY_MESSAGE)
        return key
    }
}

internal const val MISSING_KEY_MESSAGE = "Add a YouTube Data API key in Settings → Music."

/** videos.list answers 50 items per page; the chart band never shows more. */
internal const val YOUTUBE_MUSIC_CATEGORY = 10

/** 20 searches is 2 000 of the 10 000 units a default key gets per day. */
internal const val MAX_SEARCH_CALLS_PER_SESSION = 20

sealed interface KeyCheck {
    data object Valid : KeyCheck
    data object Invalid : KeyCheck
    data object Missing : KeyCheck
    data class Failed(val message: String) : KeyCheck
}

/** Status and body together, because a 403 may mean a bad key or an empty quota. */
data class YouTubeProbe(val code: Int, val body: String = "")

internal fun youtubeChartUrl(country: ChartCountry, limit: Int, key: String): String =
    "https://www.googleapis.com/youtube/v3/videos" +
        "?part=snippet,contentDetails&chart=mostPopular" +
        "&videoCategoryId=$YOUTUBE_MUSIC_CATEGORY" +
        "&regionCode=${country.code}&maxResults=$limit&key=${ChartHandoff.encode(key)}"

internal fun youtubeSearchUrl(query: String, country: ChartCountry, limit: Int, key: String): String =
    "https://www.googleapis.com/youtube/v3/search" +
        "?part=snippet&type=video&videoCategoryId=$YOUTUBE_MUSIC_CATEGORY" +
        "&regionCode=${country.code}&maxResults=$limit" +
        "&q=${ChartHandoff.encode(query)}&key=${ChartHandoff.encode(key)}"

internal fun youtubeCategoryProbeUrl(key: String): String =
    "https://www.googleapis.com/youtube/v3/videoCategories" +
        "?part=snippet&regionCode=US&key=${ChartHandoff.encode(key)}"

internal object YouTubeJson {

    fun parseVideos(body: String, limit: Int): List<ChartTrack> {
        val root = read(body, "YouTube returned an unreadable chart")
        val items = root.optJSONArray("items") ?: return emptyList()
        return (0 until items.length())
            .asSequence()
            .mapNotNull { index -> items.optJSONObject(index)?.let(::videoRow) }
            .distinctBy { it.id }
            .take(limit)
            .toList()
    }

    fun parseSearch(body: String, limit: Int): List<ChartTrack> {
        val root = read(body, "YouTube returned an unreadable search result")
        val items = root.optJSONArray("items") ?: return emptyList()
        return (0 until items.length())
            .asSequence()
            .mapNotNull { index -> items.optJSONObject(index)?.let(::searchRow) }
            .distinctBy { it.videoId }
            .take(limit)
            .toList()
    }

    fun errorReason(body: String): String? = runCatching {
        JSONObject(body).optJSONObject("error")?.optJSONArray("errors")
            ?.optJSONObject(0)?.stringOrNull("reason")
    }.getOrNull()

    private fun videoRow(item: JSONObject): ChartTrack? {
        val id = ChartHandoff.validVideoId(item.stringOrNull("id")) ?: return null
        val snippet = item.optJSONObject("snippet") ?: return null
        val title = snippet.stringOrNull("title") ?: return null
        return row(
            videoId = id,
            snippet = snippet,
            title = title,
            durationMs = YouTubeJson.durationMs(item.optJSONObject("contentDetails"))
        )
    }

    private fun searchRow(item: JSONObject): ChartTrack? {
        val id = ChartHandoff.validVideoId(item.optJSONObject("id")?.stringOrNull("videoId"))
            ?: return null
        val snippet = item.optJSONObject("snippet") ?: return null
        val title = snippet.stringOrNull("title") ?: return null
        return row(videoId = id, snippet = snippet, title = title, durationMs = 0L)
    }

    private fun row(videoId: String, snippet: JSONObject, title: String, durationMs: Long) =
        ChartTrack(
            source = ChartSource.YOUTUBE,
            id = "youtube:$videoId",
            title = title,
            artist = snippet.stringOrNull("channelTitle").orEmpty(),
            artwork = thumbnailUrl(snippet.optJSONObject("thumbnails")),
            durationMs = durationMs,
            videoId = videoId,
            openUrl = ChartHandoff.watchUrl(videoId)
        )

    private fun thumbnailUrl(thumbnails: JSONObject?): String? {
        if (thumbnails == null) return null
        for (name in listOf("maxres", "high", "medium", "default")) {
            val url = thumbnails.optJSONObject(name)?.stringOrNull("url") ?: continue
            if (url.startsWith("http")) return url
        }
        return null
    }

    /** ISO 8601 (`PT1H2M3S`); a live broadcast reports `P0D` and no length. */
    fun durationMs(contentDetails: JSONObject?): Long {
        val text = contentDetails?.stringOrNull("duration") ?: return 0L
        val match = durationPattern.matchEntire(text) ?: return 0L
        fun group(index: Int): Long =
            match.groupValues[index].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
        return ((group(1) * 3600L) + (group(2) * 60L) + group(3)) * 1000L
    }

    private val durationPattern = Regex("""^P(?:\d+D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$""")

    private fun read(body: String, unreadable: String): JSONObject {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: throw IOException(unreadable)
        val error = root.optJSONObject("error") ?: return root
        throw IOException("YouTube: " + (error.stringOrNull("message") ?: "request refused"))
    }
}

private suspend fun fetchYouTube(url: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", Http.BROWSER_USER_AGENT)
        .header("Accept", "application/json")
        .build()
    Http.client.await(request).use { response ->
        val body = response.bodyStringCapped(MAX_BODY_BYTES)
        // Google puts the reason in the body, so read it before the status check.
        if (!response.isSuccessful) throw IOException("YouTube HTTP ${response.code}: ${snippet(body)}")
        body
    }
}

private suspend fun probeYouTube(url: String): YouTubeProbe = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", Http.BROWSER_USER_AGENT)
        .header("Accept", "application/json")
        .build()
    runCatching {
        Http.client.await(request).use { response ->
            YouTubeProbe(response.code, response.bodyStringCapped(MAX_BODY_BYTES))
        }
    }.getOrDefault(YouTubeProbe(0))
}

private fun snippet(body: String): String =
    YouTubeJson.errorReason(body)?.let { "($it)" } ?: body.take(160)

private const val MAX_BODY_BYTES = 1024 * 1024
