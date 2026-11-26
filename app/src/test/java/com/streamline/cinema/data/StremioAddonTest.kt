package com.streamline.cinema.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline rules for addon URLs, content ids and label parsing. Live checks against
 * the Hyper addon live in StremioAddonLiveTest under the `integrationTest` task.
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
    fun tmdbIdsLeadAndTheTranslatedImdbFollows() {
        val movie = CatalogItem(
            tmdbId = 414906,
            type = MediaType.MOVIE,
            title = "The Batman",
            imdbId = "tt1877830"
        )
        assertEquals(
            listOf("tmdb:414906", "tt1877830"),
            StremioAddons.contentIds(movie, null, null)
        )
        val episode = movie.copy(type = MediaType.TV, imdbId = "tt0944947")
        assertEquals(
            listOf("tmdb:414906:2:5", "tt0944947:2:5"),
            StremioAddons.contentIds(episode, 2, 5)
        )
        // Items enriched without an IMDb id still query the TMDB id.
        assertEquals(
            listOf("tmdb:414906"),
            StremioAddons.contentIds(movie.copy(imdbId = ""), null, null)
        )
    }

    @Test
    fun subtitleFallbackKeepsTheImdbIdForTtOnlyAddons() {
        // OpenSubtitles matches `tt…` only, so the TMDB id alone would miss it.
        val item = CatalogItem(
            tmdbId = 414906, type = MediaType.MOVIE, title = "The Batman", imdbId = "tt1877830"
        )
        assertEquals(listOf("tmdb:414906", "tt1877830"), StremioAddons.contentIds(item, null, null))
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

    @Test
    fun subtitleSourcesAlwaysIncludeTheBuiltInDefault() {
        assertEquals(
            listOf(StremioAddons.DEFAULT_SUBTITLE_ADDON),
            StremioAddons.subtitleSources(emptyList())
        )
        assertEquals(
            listOf("https://subs.example/manifest.json", StremioAddons.DEFAULT_SUBTITLE_ADDON),
            StremioAddons.subtitleSources(listOf("https://subs.example/manifest.json"))
        )
    }

    @Test
    fun subtitleSourcesDoNotFetchOpenSubtitlesTwice() {
        val sources = StremioAddons.subtitleSources(
            listOf(" https://opensubtitles-v3.strem.io/manifest.json/ ", "")
        )
        assertEquals(listOf(StremioAddons.DEFAULT_SUBTITLE_ADDON.trimEnd('/')), sources.map { it.trimEnd('/') })
        assertEquals(1, sources.size)
    }
}
