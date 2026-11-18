package com.streamline.cinema.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** One auto-skip range reported by SponsorBlock, in milliseconds. */
data class SponsorSegment(val startMs: Long, val endMs: Long) {
    fun contains(positionMs: Long): Boolean = positionMs >= startMs && positionMs < endMs
}

/**
 * SponsorBlock lookups for Musicbox. Only `music_offtopic` is requested: a
 * music video's talking intro, interlude or outro, never the song itself.
 *
 * Uses the privacy-preserving hash API — the device sends the first 16 bits of
 * SHA-256(videoId), so no watch history leaves the app — and accepts both the
 * addon's `yt:<id>` meta ids and a bare YouTube id.
 */
object SponsorBlock {

    internal const val MUSIC_OFFTOPIC = "music_offtopic"

    private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** `yt:dQw4w9WgXcQ`, `youtube:dQw4w9WgXcQ` and a bare id all name a video. */
    internal fun videoIdOf(trackId: String): String? {
        val candidate = trackId.trim().substringAfterLast(':').trim()
        return candidate.takeIf { YOUTUBE_ID.matches(it) }
    }

    /** First two bytes of SHA-256 as lowercase hex, the API's 4-character prefix. */
    internal fun hashPrefix(videoId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(videoId.toByteArray(Charsets.UTF_8))
            .take(2)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * Parses a `GET /api/skipSegments/{prefix}` body. The response contains
     * every video sharing the prefix, so entries are matched by id and then
     * filtered to skippable off-topic ranges.
     */
    internal fun parse(json: String, videoId: String): List<SponsorSegment> {
        val root = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val found = ArrayList<SponsorSegment>()
        for (videoIndex in 0 until root.length()) {
            val video = root.optJSONObject(videoIndex) ?: continue
            if (video.optString("videoID") != videoId) continue
            val segments = video.optJSONArray("segments") ?: continue
            for (segmentIndex in 0 until segments.length()) {
                val entry = segments.optJSONObject(segmentIndex) ?: continue
                if (!entry.optString("category").equals(MUSIC_OFFTOPIC, ignoreCase = true)) continue
                if (!entry.optString("actionType", "skip").equals("skip", ignoreCase = true)) continue
                val range = entry.optJSONArray("segment") ?: continue
                if (range.length() < 2) continue
                // The API reports seconds with decimal fractions.
                val startMs = (range.optDouble(0, -1.0) * 1000.0).toLong()
                val endMs = (range.optDouble(1, -1.0) * 1000.0).toLong()
                if (startMs < 0 || endMs <= startMs || endMs > MAX_MS) continue
                found += SponsorSegment(startMs, endMs)
            }
        }
        return found.sortedBy { it.startMs }
    }

    private const val MAX_MS = 6L * 60 * 60 * 1000
}

/** Caches SponsorBlock answers in memory; failures are retried, hits are not. */
class SponsorBlockClient(
    private val http: OkHttpClient = defaultHttp()
) {

    private data class Entry(val fetchedAtMs: Long, val segments: List<SponsorSegment>)

    private val gate = Mutex()

    private val memory = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MEMORY_ENTRIES
    }

    /** Off-topic segments for a video, empty when there are none or the API is down. */
    suspend fun segments(videoId: String): List<SponsorSegment> {
        val id = SponsorBlock.videoIdOf(videoId) ?: return emptyList()
        cached(id)?.let { return it }
        return gate.withLock {
            val known = cached(id)
            if (known != null) return@withLock known
            val fetched = fetch(id) ?: return@withLock emptyList()
            synchronized(memory) { memory[id] = Entry(System.currentTimeMillis(), fetched) }
            fetched
        }
    }

    private fun cached(id: String): List<SponsorSegment>? = synchronized(memory) {
        val entry = memory[id] ?: return null
        if (System.currentTimeMillis() - entry.fetchedAtMs > CACHE_TTL_MS) {
            memory.remove(id)
            return null
        }
        entry.segments
    }

    /** Null when the lookup failed, so a transient outage is not cached. */
    private suspend fun fetch(videoId: String): List<SponsorSegment>? {
        val base = BASE_URL.toHttpUrlOrNull() ?: return null
        val url = base.newBuilder()
            .addPathSegment(SponsorBlock.hashPrefix(videoId))
            .addQueryParameter("categories", """["${SponsorBlock.MUSIC_OFFTOPIC}"]""")
            .addQueryParameter("actionTypes", """["skip"]""")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        return try {
            http.await(request).use { response ->
                when {
                    response.code == 404 -> emptyList()
                    response.isSuccessful ->
                        SponsorBlock.parse(response.bodyStringCapped(MAX_BODY_BYTES), videoId)
                    else -> null
                }
            }
        } catch (_: IOException) {
            null
        }
    }

    private companion object {
        const val BASE_URL = "https://sponsor.ajay.app/api/skipSegments"
        const val USER_AGENT = "StreamLine Musicbox (Android TV)"
        const val MEMORY_ENTRIES = 64
        const val MAX_BODY_BYTES = 64 * 1024
        const val CACHE_TTL_MS = 6L * 60 * 60 * 1000

        fun defaultHttp(): OkHttpClient = Http.client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
