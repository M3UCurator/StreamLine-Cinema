package com.streamline.cinema.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Session memory for refused stream URLs: a 404/410 keeps a link out of the
 * picker and the failover pool, and a 429 parks the exact token for its
 * backoff so nothing re-probes it while the provider counts sessions.
 */
class SessionUrlMemoryTest {

    private val now = 1_000_000L

    @Before
    fun reset() = SessionUrlMemory.clear()

    @After
    fun cleanUp() = SessionUrlMemory.clear()

    @Test
    fun deadLinkIsUnavailableForTheSession() {
        SessionUrlMemory.markUnavailable("https://cdn/gone.m3u8")
        assertTrue(SessionUrlMemory.isUnavailable("https://cdn/gone.m3u8"))
        assertEquals("unavailable this session", SessionUrlMemory.blockReason("https://cdn/gone.m3u8"))
        assertFalse(SessionUrlMemory.isLimited("https://cdn/gone.m3u8"))
        assertNull(SessionUrlMemory.blockReason("https://cdn/other.m3u8"))
    }

    @Test
    fun limitHoldsForItsBackoffAndThenLifts() {
        SessionUrlMemory.markLimited("https://cdn/token.m3u8", retryAfterMs = 30_000L, nowMs = now)
        assertEquals(30_000L, SessionUrlMemory.limitedFor("https://cdn/token.m3u8", nowMs = now))
        assertTrue(SessionUrlMemory.limitedFor("https://cdn/token.m3u8", nowMs = now + 1L) > 0L)
        assertTrue(SessionUrlMemory.limitedFor("https://cdn/token.m3u8", nowMs = now + 29_999L) > 0L)
        assertEquals(0L, SessionUrlMemory.limitedFor("https://cdn/token.m3u8", nowMs = now + 30_000L))
        assertFalse(SessionUrlMemory.isLimited("https://cdn/token.m3u8"))
    }

    @Test
    fun missingHintParksTheTokenForAMinute() {
        SessionUrlMemory.markLimited("https://cdn/token.m3u8", nowMs = now)
        assertTrue(SessionUrlMemory.limitedFor("https://cdn/token.m3u8", nowMs = now) > 0L)
        assertEquals(0L, SessionUrlMemory.limitedFor("https://cdn/token.m3u8", nowMs = now + 60_000L))
    }

    @Test
    fun backoffIsClampedToAUsefulWindow() {
        SessionUrlMemory.markLimited("https://cdn/short.m3u8", retryAfterMs = 1_000L, nowMs = now)
        assertTrue(SessionUrlMemory.limitedFor("https://cdn/short.m3u8", nowMs = now + 4_999L) > 0L)

        SessionUrlMemory.markLimited("https://cdn/long.m3u8", retryAfterMs = 3_600_000L, nowMs = now)
        assertTrue(SessionUrlMemory.limitedFor("https://cdn/long.m3u8", nowMs = now + 179_000L) > 0L)
        assertEquals(0L, SessionUrlMemory.limitedFor("https://cdn/long.m3u8", nowMs = now + 180_000L))
    }

    @Test
    fun goneVerdictReplacesALimit() {
        SessionUrlMemory.markLimited("https://cdn/token.m3u8", retryAfterMs = 30_000L, nowMs = now)
        SessionUrlMemory.markUnavailable("https://cdn/token.m3u8")
        assertTrue(SessionUrlMemory.isUnavailable("https://cdn/token.m3u8"))
        assertEquals(0L, SessionUrlMemory.limitedFor("https://cdn/token.m3u8"))
        assertEquals("unavailable this session", SessionUrlMemory.blockReason("https://cdn/token.m3u8"))
    }

    @Test
    fun fragmentsDoNotSplitAnEntry() {
        SessionUrlMemory.markUnavailable("https://cdn/gone.m3u8#t=10")
        assertTrue(SessionUrlMemory.isUnavailable("https://cdn/gone.m3u8"))
        assertTrue(SessionUrlMemory.isUnavailable("  https://cdn/gone.m3u8  "))
        assertNull(SessionUrlMemory.blockReason("   "))
    }

    @Test
    fun oldestEntriesAreEvictedBeyondTheCap() {
        repeat(513) { index -> SessionUrlMemory.markUnavailable("https://cdn/$index.m3u8") }
        assertFalse(SessionUrlMemory.isUnavailable("https://cdn/0.m3u8"))
        assertTrue(SessionUrlMemory.isUnavailable("https://cdn/1.m3u8"))
        assertTrue(SessionUrlMemory.isUnavailable("https://cdn/512.m3u8"))
    }

    @Test
    fun clearForgetsEverything() {
        SessionUrlMemory.markUnavailable("https://cdn/gone.m3u8")
        SessionUrlMemory.markLimited("https://cdn/token.m3u8", retryAfterMs = 30_000L, nowMs = now)
        SessionUrlMemory.clear()
        assertFalse(SessionUrlMemory.isUnavailable("https://cdn/gone.m3u8"))
        assertEquals(0L, SessionUrlMemory.limitedFor("https://cdn/token.m3u8", nowMs = now))
    }

    @Test
    fun retryAfterAcceptsSecondsAndHttpDates() {
        assertEquals(120_000L, parseRetryAfterMs("120", 0L))
        assertEquals(0L, parseRetryAfterMs("0", 0L))
        assertEquals(0L, parseRetryAfterMs("-5", 0L))
        assertEquals(1_445_412_480_000L, parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT", 0L))
        assertEquals(30_000L, parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT", 1_445_412_450_000L))
        assertNull(parseRetryAfterMs(null, 0L))
        assertNull(parseRetryAfterMs("  ", 0L))
        assertNull(parseRetryAfterMs("soon", 0L))
    }
}
