package com.streamline.cinema.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException

/**
 * Live checks against the Hyper addon (https://hyper.stravo.site/local/manifest.json).
 * They skip themselves when the addon is unreachable, so an offline machine still builds.
 */
class StremioAddonTest {

    @Test
    fun normalizeAcceptsBaseAndManifestUrls() {
        val expected = "https://hyper.stravo.site/local/manifest.json"
        assertEquals(expected, AddonSource.normalize("https://hyper.stravo.site/local"))
        assertEquals(expected, AddonSource.normalize("https://hyper.stravo.site/local/"))
        assertEquals(expected, AddonSource.normalize("https://hyper.stravo.site/local/manifest.json"))
        assertEquals(expected, AddonSource(url = expected).manifestUrl)
        assertEquals(expected, AddonSource(url = "https://hyper.stravo.site/local").manifestUrl)
        assertEquals(null, AddonSource.normalize("hyper.stravo.site/local"))
    }

    @Test
    fun manifestIsValidAndSupportsStreams() {
        if (!reachable) return
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
        if (!reachable) return
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
        if (!reachable) return
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
        if (!reachable) return
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

    @Test
    fun contentIdsPreferImdbForAddonLookups() {
        val movie = CatalogItem(
            tmdbId = 414906,
            type = MediaType.MOVIE,
            title = "The Batman",
            imdbId = "tt1877830"
        )
        assertEquals(
            listOf("tt1877830", "tmdb:414906"),
            StremioAddons.contentIds(movie, null, null)
        )
        val episode = movie.copy(type = MediaType.TV, imdbId = "tt0944947")
        assertEquals(
            listOf("tt0944947:2:5", "tmdb:414906:2:5"),
            StremioAddons.contentIds(episode, 2, 5)
        )
        // Items enriched without an IMDb id still query the TMDB id.
        assertEquals(
            listOf("tmdb:414906"),
            StremioAddons.contentIds(movie.copy(imdbId = ""), null, null)
        )
    }

    @Test
    fun subtitleAddonStopsAtTheIdThatAnswers() {
        // OpenSubtitles matches `tt…` only; the TMDB id must not be queried first.
        val item = CatalogItem(
            tmdbId = 414906, type = MediaType.MOVIE, title = "The Batman", imdbId = "tt1877830"
        )
        assertEquals("tt1877830", StremioAddons.contentIds(item, null, null).first())
    }

    @Test
    fun seriesWithoutEpisodeIsNeverSearchedAsS1E1() {
        val series = CatalogItem(tmdbId = 1399, type = MediaType.TV, title = "Game of Thrones")
        assertTrue(StremioAddons.contentIds(series, null, null).isEmpty())
        assertTrue(StremioAddons.contentIds(series, 3, null).isEmpty())
        assertEquals(listOf("tmdb:1399:3:4"), StremioAddons.contentIds(series, 3, 4))
    }

    @Test
    fun malformedImdbIdsAreNotQueried() {
        val item = CatalogItem(
            tmdbId = 550, type = MediaType.MOVIE, title = "Fight Club", imdbId = "tt-not-an-id"
        )
        assertEquals(listOf("tmdb:550"), StremioAddons.contentIds(item, null, null))
    }

    @Test
    fun streamsNamingAnotherEpisodeAreRejected() {
        val series = CatalogItem(tmdbId = 1399, type = MediaType.TV, title = "Game of Thrones")
        val wrong = StreamItem(
            name = "1080p", title = "Game of Thrones S02E01", url = "https://x/a.m3u8",
            externalUrl = null, behaviorHints = null
        )
        val right = wrong.copy(title = "Game of Thrones S03E04")
        val unlabelled = wrong.copy(title = "1080p · BluRay")
        assertFalse(StremioAddons.streamMatchesRequest(wrong, true, 3, 4))
        assertTrue(StremioAddons.streamMatchesRequest(right, true, 3, 4))
        assertTrue(StremioAddons.streamMatchesRequest(unlabelled, true, 3, 4))
        // A movie lookup must not accept an episode-labelled source.
        assertFalse(StremioAddons.streamMatchesRequest(wrong, false, null, null))
    }

    @Test
    fun advertisedEpisodeReadsCommonLabels() {
        fun stream(label: String) = StreamItem(
            name = null, title = label, url = "https://x/a.m3u8",
            externalUrl = null, behaviorHints = null
        )
        assertEquals(2 to 5, stream("Show.S02E05.1080p").advertisedEpisode())
        assertEquals(2 to 5, stream("Season 2 Episode 5").advertisedEpisode())
        assertEquals(2 to 5, stream("Show 2x05").advertisedEpisode())
        assertNull(stream("1080p · HDR").advertisedEpisode())
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
