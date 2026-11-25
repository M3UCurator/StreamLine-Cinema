package com.streamline.cinema.data

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Per-process memory of URLs the provider has definitively refused. A 404/410
 * marks that exact link gone for the rest of the session, and a 429 parks the
 * token until its backoff elapses. The addon client and the loopback proxy both
 * consult it, so a dead or rate-limited link is not re-dialled while another
 * source can be tried instead.
 *
 * Only the exact request URL is remembered: an addon that 404s one id can still
 * answer another, and a CDN segment's 404 says nothing about its playlist.
 */
object SessionUrlMemory {
    private const val MAX_ENTRIES = 512
    private const val MIN_LIMIT_BACKOFF_MS = 5_000L
    private const val MAX_LIMIT_BACKOFF_MS = 180_000L
    private const val DEFAULT_LIMIT_BACKOFF_MS = 60_000L

    private val lock = Any()
    private val dead = LinkedHashSet<String>()
    private val limited = HashMap<String, Long>()

    /** The link answered 404/410: it is unavailable for this session. */
    fun markUnavailable(url: String) {
        val key = key(url) ?: return
        synchronized(lock) {
            limited.remove(key)
            dead.add(key)
            trimLocked()
        }
    }

    fun isUnavailable(url: String): Boolean {
        val key = key(url) ?: return false
        return synchronized(lock) { dead.contains(key) }
    }

    /**
     * The provider answered 429. The URL is parked for the Retry-After window
     * (or a minute when the provider gave no hint) so nothing re-probes it in
     * the meantime.
     */
    fun markLimited(
        url: String,
        retryAfterMs: Long? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val key = key(url) ?: return
        val wait = (retryAfterMs ?: DEFAULT_LIMIT_BACKOFF_MS)
            .coerceIn(MIN_LIMIT_BACKOFF_MS, MAX_LIMIT_BACKOFF_MS)
        synchronized(lock) {
            limited[key] = nowMs + wait
            pruneLimitedLocked(nowMs)
            trimLocked()
        }
    }

    /** Remaining backoff for [url], or 0 when it may be dialled again. */
    fun limitedFor(url: String, nowMs: Long = System.currentTimeMillis()): Long {
        val key = key(url) ?: return 0L
        return synchronized(lock) {
            val until = limited[key] ?: return 0L
            if (until <= nowMs) {
                limited.remove(key)
                0L
            } else {
                until - nowMs
            }
        }
    }

    fun isLimited(url: String): Boolean = limitedFor(url) > 0L

    /** Why [url] must not be dialled right now, or null when it may be. */
    fun blockReason(url: String): String? = when {
        isUnavailable(url) -> "unavailable this session"
        isLimited(url) -> "rate-limited by the provider"
        else -> null
    }

    fun clear() {
        synchronized(lock) {
            dead.clear()
            limited.clear()
        }
    }

    /** Fragments never reach the wire, so they must not split an entry. */
    private fun key(url: String): String? =
        url.substringBefore('#').trim().takeIf { it.isNotEmpty() }

    private fun pruneLimitedLocked(nowMs: Long) {
        limited.entries.removeAll { it.value <= nowMs }
    }

    private fun trimLocked() {
        while (dead.size > MAX_ENTRIES) {
            val oldest = dead.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
        while (limited.size > MAX_ENTRIES) {
            val oldest = limited.entries.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
    }
}

/**
 * Retry-After as a delay in milliseconds, accepting delay-seconds and the
 * HTTP-date form. Unclamped: callers decide how long they are willing to wait.
 */
internal fun parseRetryAfterMs(raw: String?, nowMs: Long): Long? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    value.toLongOrNull()?.let { return (it * 1_000L).coerceAtLeast(0L) }
    return runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .parse(value)!!.time - nowMs
    }.getOrNull()?.coerceAtLeast(0L)
}
