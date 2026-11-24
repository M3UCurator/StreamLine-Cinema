package com.streamline.cinema.data

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Skip-segment lookups against TheIntroDB (theintrodb.org).
 *
 * Anonymous `GET /v3/media?tmdb_id=…&season=…&episode=…`; anime is covered
 * through its TMDB id because the API accepts no MAL/AniDB ids. `duration_ms`
 * is deliberately not sent: it makes the service drop valid credits segments
 * (verified against Attack on Titan S1E1).
 *
 * Responses are cached in memory and on disk for days, and a 429 backs the
 * client off using `X-RateLimit-Reset` instead of retrying into the cap.
 */
class IntroDbClient(cacheRoot: File) {

    private val directory = File(cacheRoot, "introdb")
    private val http = Http.client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val memory = object : LinkedHashMap<String, SkipSegments?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SkipSegments?>?): Boolean =
            size > MEMORY_ENTRIES
    }

    // One lookup at a time: an episode needs its own segment list and one
    // prefetch for its successor, and a serialized gate keeps a retry burst
    // from spending the API's 30-requests-per-10-seconds allowance.
    private val gate = Mutex()

    @Volatile
    private var blockedUntilMs = 0L

    /** Segments for one episode, or null when the database has none. */
    suspend fun segments(tmdbId: Int, season: Int, episode: Int): SkipSegments? {
        if (tmdbId <= 0 || season <= 0 || episode <= 0) return null
        val key = "$tmdbId-$season-$episode"
        return gate.withLock {
            if (memory.containsKey(key)) return@withLock memory[key]

            val now = System.currentTimeMillis()
            val cached = readCache(key)
            val ttl = if (cached?.segments == null) NEGATIVE_TTL_MS else CACHE_TTL_MS
            if (cached != null && now - cached.savedAtMs <= ttl) {
                memory[key] = cached.segments
                return@withLock cached.segments
            }
            if (now < blockedUntilMs) return@withLock cached?.segments

            var completed = false
            var parsed: SkipSegments? = null
            try {
                http.await(request(tmdbId, season, episode)).use { response ->
                    when {
                        response.code == 429 -> {
                            blockedUntilMs = now + rateLimitCooldownMs(response.header("X-RateLimit-Reset"))
                            Log.i(TAG, "IntroDB rate limited until +${blockedUntilMs - now} ms")
                        }
                        response.code == 404 -> completed = true
                        response.isSuccessful -> {
                            parsed = SkipSegments.parse(response.bodyStringCapped(MAX_BODY_BYTES))
                            completed = true
                        }
                    }
                }
            } catch (failure: IOException) {
                Log.i(TAG, "IntroDB $tmdbId S$season E$episode failed: ${failure.message}")
            }

            if (completed) {
                memory[key] = parsed
                writeCache(key, parsed)
                parsed
            } else {
                // A transient failure keeps an older copy in service rather than
                // dropping skip data mid-episode.
                cached?.segments
            }
        }
    }

    private fun request(tmdbId: Int, season: Int, episode: Int): Request =
        Request.Builder()
            .url("$BASE_URL?tmdb_id=$tmdbId&season=$season&episode=$episode")
            .header("User-Agent", Http.BROWSER_USER_AGENT)
            .header("Accept", "application/json")
            .build()

    private data class CacheEntry(val savedAtMs: Long, val segments: SkipSegments?)

    private fun readCache(key: String): CacheEntry? = runCatching {
        val text = AtomicStore.read(File(directory, "$key.json")) ?: return@runCatching null
        val root = JSONObject(text)
        CacheEntry(
            savedAtMs = root.optLong("savedAtMs", 0L),
            segments = root.optJSONObject("segments")?.let { SkipSegments.parse(it.toString()) }
        )
    }.getOrNull()

    private fun writeCache(key: String, segments: SkipSegments?) {
        runCatching {
            val payload = JSONObject()
                .put("savedAtMs", System.currentTimeMillis())
                .put("segments", segments?.toJson() ?: JSONObject.NULL)
            AtomicStore.write(File(directory, "$key.json"), payload.toString(), "skip segments")
            prune()
        }.onFailure { Log.i(TAG, "IntroDB cache write failed: ${it.message}") }
    }

    private fun prune() {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(".json") } ?: return
        if (files.size <= MAX_CACHE_ENTRIES) return
        val excess = files.size - MAX_CACHE_ENTRIES / 2
        files.sortedBy { it.lastModified() }.take(excess).forEach { runCatching { it.delete() } }
    }

    /**
     * `X-RateLimit-Reset` is used with three different meanings across services
     * (epoch millis, epoch seconds, seconds from now); accept all of them and
     * cap the wait so a bad header cannot disable lookups for the session.
     */
    private fun rateLimitCooldownMs(reset: String?): Long {
        val raw = reset?.toLongOrNull()
        val now = System.currentTimeMillis()
        val remaining = when {
            raw == null || raw <= 0L -> DEFAULT_COOLDOWN_MS
            raw >= 100_000_000_000L -> raw - now
            raw >= 100_000_000L -> raw * 1_000L - now
            else -> raw * 1_000L
        }
        return remaining.coerceIn(1_000L, MAX_COOLDOWN_MS)
    }

    private companion object {
        const val BASE_URL = "https://api.theintrodb.org/v3/media"
        const val TAG = "StreamLine:introdb"
        const val MEMORY_ENTRIES = 24
        const val MAX_CACHE_ENTRIES = 300
        const val MAX_BODY_BYTES = 64 * 1024
        const val CACHE_TTL_MS = 14L * 24 * 60 * 60 * 1000
        const val NEGATIVE_TTL_MS = 2L * 24 * 60 * 60 * 1000
        const val DEFAULT_COOLDOWN_MS = 30_000L
        const val MAX_COOLDOWN_MS = 60_000L
    }
}
