package com.streamline.cinema.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException

/**
 * Live checks against the Hyper addon (https://hyper.stravo.site/local/manifest.json).
 * These are excluded from the offline unit suite and run under the `integrationTest`
 * Gradle task; each test skips itself when the addon is unreachable.
 */
class StremioAddonLiveTest {

    @Test
    fun manifestIsValidAndSupportsStreams() {
        assumeTrue("Hyper addon unreachable", reachable)
        val manifest = runBlocking { StremioAddons.loadManifest(ADDON) }
        println("manifest: id=${manifest.id} name=${manifest.name} v=${manifest.version} " +
            "types=${manifest.types} idPrefixes=${manifest.idPrefixes} resources=${manifest.resources}")
        assertEquals("org.fayallc.hyper", manifest.id)
        assertEquals("Hyper", manifest.name)
        assertTrue(manifest.supportsStreams)
        assertTrue(manifest.types.containsAll(listOf("movie", "series")))
        assertNotNull(manifest.idPrefixes?.firstOrNull { it == "tmdb:" })
    }

    @Test
    fun movieStreamsCarryProxyHeaders() {
        assumeTrue("Hyper addon unreachable", reachable)
        val streams = runBlocking { StremioAddons.fetchStreams(ADDON, "movie", "tmdb:550") }
        streams.forEach { println("movie stream: title=${it.title} url=${it.url} ext=${it.externalUrl}") }
        val direct = streams.filter { !it.url.isNullOrBlank() }
        assertTrue("expected direct urls", direct.isNotEmpty())
        assertTrue("expected an adaptive manifest", direct.any { it.url!!.contains(".mpd") })
        val withReferer = direct.count { it.requestHeaders().containsKey("Referer") }
        val withToken = direct.count { it.requestHeaders().containsKey("X-MB-Token") }
        println("direct=${direct.size} withReferer=$withReferer withToken=$withToken")
        assertTrue("expected Referer hints", withReferer > 0)
        assertTrue("DASH needs its CDN token", withToken > 0)
        assertTrue("titles must be available for picker labels", direct.all { !it.title.isNullOrBlank() })
    }

    @Test
    fun playerOptionsSkipExternalEntriesAndKeepLabels() {
        assumeTrue("Hyper addon unreachable", reachable)
        val streams = runBlocking { StremioAddons.fetchStreams(ADDON, "movie", "tmdb:550") }
        val options = streams.mapNotNull { it.toPlaybackOption("Hyper") }
            .distinctBy { it.url }
            .take(16)
        options.forEach { println("option: ${it.name} -> ${it.url.take(80)} headers=${it.headers.keys}") }
        // Provider inventories change; only the shape and labelling are ours to assert.
        assertTrue("expected several options", options.size >= 2)
        assertTrue(options.all { it.name.startsWith("Hyper · ") })
        // The same quality can be offered over two transports (DASH and MP4), so
        // labels may repeat; the stream URLs must not.
        assertEquals(options.size, options.map { it.url }.distinct().size)
        assertTrue(options.any { it.headers.containsKey("X-MB-Token") })
    }

    @Test
    fun seriesEpisodeResolves() {
        assumeTrue("Hyper addon unreachable", reachable)
        val id = StremioAddons.contentId(
            CatalogItem(tmdbId = 94605, type = MediaType.TV, title = "Arcane"),
            season = 1,
            episode = 1
        )
        assertEquals("tmdb:94605:1:1", id)
        val streams = runBlocking { StremioAddons.fetchStreams(ADDON, "series", id) }
        streams.forEach { println("series stream: title=${it.title} url=${it.url} ext=${it.externalUrl}") }
        assertTrue("expected series streams", streams.any { !it.url.isNullOrBlank() })
    }

    companion object {
        private const val ADDON = "https://hyper.stravo.site/local/manifest.json"

        private var reachable = false

        @BeforeClass
        @JvmStatic
        fun probe() {
            reachable = try {
                runBlocking { StremioAddons.loadManifest(ADDON) }
                true
            } catch (e: IOException) {
                println("addon unreachable, skipping live checks: ${e.message}")
                false
            }
        }
    }
}
