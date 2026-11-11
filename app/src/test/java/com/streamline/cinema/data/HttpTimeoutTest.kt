package com.streamline.cinema.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Locks the per-client timeout budget documented in README "Playback resilience".
 * Media reads have no call timeout but do keep a socket read bound, so a dead CDN
 * fails fast instead of hanging the player forever.
 */
class HttpTimeoutTest {

    @Test
    fun apiClientKeepsShortGuards() {
        val client = Http.client
        assertEquals(TimeUnit.SECONDS.toMillis(15), client.connectTimeoutMillis.toLong())
        assertEquals(TimeUnit.SECONDS.toMillis(25), client.readTimeoutMillis.toLong())
        assertEquals(TimeUnit.SECONDS.toMillis(35), client.callTimeoutMillis.toLong())
    }

    @Test
    fun progressiveStreamsHaveNoCallTimeoutButBoundedReads() {
        val client = Http.streamingClient
        assertEquals(TimeUnit.SECONDS.toMillis(20), client.connectTimeoutMillis.toLong())
        assertEquals(TimeUnit.SECONDS.toMillis(45), client.readTimeoutMillis.toLong())
        assertEquals(TimeUnit.SECONDS.toMillis(30), client.writeTimeoutMillis.toLong())
        assertEquals(0L, client.callTimeoutMillis.toLong())
    }

    @Test
    fun adaptiveReadsFailFasterThanProgressiveFilms() {
        val client = Http.adaptiveClient
        assertEquals(TimeUnit.SECONDS.toMillis(30), client.readTimeoutMillis.toLong())
        assertEquals(0L, client.callTimeoutMillis.toLong())
    }

    @Test
    fun documentDownloadsHaveNoCallTimeout() {
        val client = Http.documentClient
        assertEquals(TimeUnit.SECONDS.toMillis(30), client.readTimeoutMillis.toLong())
        assertEquals(0L, client.callTimeoutMillis.toLong())
    }
}
