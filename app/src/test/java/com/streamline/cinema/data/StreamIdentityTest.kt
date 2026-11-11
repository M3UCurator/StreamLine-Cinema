package com.streamline.cinema.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identity rules that keep a stream search on the title and episode the viewer
 * actually selected. Every case here maps to a way the old pipeline could query
 * (or accept) another title's source.
 */
class StreamIdentityTest {

    private fun series(tmdbId: Int = 1399, imdbId: String = "tt0944947") = CatalogItem(
        tmdbId = tmdbId,
        type = MediaType.TV,
        title = "Game of Thrones",
        imdbId = imdbId,
        tmdbLoaded = true
    )

    private fun movie(tmdbId: Int = 550, imdbId: String = "tt0137523") = CatalogItem(
        tmdbId = tmdbId,
        type = MediaType.MOVIE,
        title = "Fight Club",
        imdbId = imdbId,
        tmdbLoaded = true
    )

    private fun stream(title: String?, name: String? = "1080p") = StreamItem(
        name = name,
        title = title,
        url = "https://cdn.test/v.mp4",
        externalUrl = null,
        behaviorHints = null
    )

    @Test
    fun imdbLeadsAndTmdbIsTheFallback() {
        assertEquals(
            listOf("tt0137523", "tmdb:550"),
            StremioAddons.contentIds(movie(), null, null)
        )
        assertEquals(
            listOf("tt0944947:3:4", "tmdb:1399:3:4"),
            StremioAddons.contentIds(series(), 3, 4)
        )
    }

    @Test
    fun seriesWithoutAnEpisodeHasNoSearchIds() {
        assertTrue(StremioAddons.contentIds(series(), null, null).isEmpty())
        assertTrue(StremioAddons.contentIds(series(), 3, null).isEmpty())
        assertTrue(StremioAddons.contentIds(series(), null, 4).isEmpty())
        assertTrue(StremioAddons.contentIds(series(), 3, 0).isEmpty())
        assertEquals("", StremioAddons.contentId(series(), 3, null))
    }

    @Test
    fun specialsSeasonIsSearchable() {
        assertEquals(
            listOf("tt0944947:0:1", "tmdb:1399:0:1"),
            StremioAddons.contentIds(series(), 0, 1)
        )
    }

    @Test
    fun malformedStoredImdbIdsNeverLeadTheSearch() {
        val stored = listOf(
            "tt",
            "tt12",
            "tt12345678901",
            "tt-not-an-id",
            "x0137523",
            "tt0137523:1:1",
            " tt0137523 "
        )
        stored.forEach { imdb ->
            val ids = StremioAddons.contentIds(movie(imdbId = imdb), null, null)
            if (imdb.trim() == "tt0137523") {
                // Surrounding whitespace is harmless; the id is normalised.
                assertEquals(listOf("tt0137523", "tmdb:550"), ids)
            } else {
                assertEquals("rejected: $imdb", listOf("tmdb:550"), ids)
            }
        }
    }

    @Test
    fun uppercaseImdbIdIsNormalised() {
        assertEquals(
            listOf("tt0944947:1:1", "tmdb:1399:1:1"),
            StremioAddons.contentIds(series(imdbId = "TT0944947"), 1, 1)
        )
    }

    @Test
    fun episodeMarkersAreReadFromEveryCommonLabel() {
        listOf(
            "Show.S02E05.1080p" to (2 to 5),
            "Show S02 E05" to (2 to 5),
            "Show s2e5 720p" to (2 to 5),
            "Season 2 Episode 5" to (2 to 5),
            "Show 2x05" to (2 to 5),
            "Show S12E100" to (12 to 100)
        ).forEach { (label, expected) ->
            assertEquals(label, expected, stream(label).advertisedEpisode())
        }
        assertNull(stream("1080p · HDR").advertisedEpisode())
        assertNull(stream("Show S01E1234").advertisedEpisode())
        assertNull(stream("Show 1234x1").advertisedEpisode())
    }

    @Test
    fun seasonOnlyLabelsAreRead() {
        assertEquals(2, stream("Show Season 2 Complete").advertisedSeason())
        assertNull(stream("Show S02 1080p").advertisedSeason())
    }

    @Test
    fun matchingAndUnlabelledSourcesPass() {
        assertTrue(StremioAddons.streamMatchesRequest(stream("Show S03E04"), true, 3, 4))
        assertTrue(StremioAddons.streamMatchesRequest(stream("1080p · BluRay"), true, 3, 4))
        assertTrue(StremioAddons.streamMatchesRequest(stream("Show Season 3"), true, 3, 4))
        assertTrue(StremioAddons.streamMatchesRequest(stream("Fight Club (1999)"), false, null, null))
    }

    @Test
    fun anotherEpisodesSourceIsRejected() {
        assertFalse(StremioAddons.streamMatchesRequest(stream("Show S03E05"), true, 3, 4))
        assertFalse(StremioAddons.streamMatchesRequest(stream("Show S02E04"), true, 3, 4))
        assertFalse(StremioAddons.streamMatchesRequest(stream("Show Season 2"), true, 3, 4))
        // Without episode context nothing can confirm the source is the right one.
        assertFalse(StremioAddons.streamMatchesRequest(stream("Show S03E04"), true, null, null))
    }

    @Test
    fun moviesRejectEpisodeLabelledSources() {
        assertFalse(StremioAddons.streamMatchesRequest(stream("Show S02E09"), false, null, null))
        assertFalse(StremioAddons.streamMatchesRequest(stream("Show Season 2"), false, null, null))
    }

    @Test
    fun episodeRangesCoverEveryEpisodeTheyName() {
        assertEquals(EpisodeLabel(3, 4..5), stream("Show S03E04-E05").advertisedEpisodes())
        assertEquals(EpisodeLabel(3, 4..5), stream("Show S03E04-05").advertisedEpisodes())
        assertEquals(EpisodeLabel(3, 4..5), stream("Show S03E04E05").advertisedEpisodes())
        assertTrue(StremioAddons.streamMatchesRequest(stream("Show S03E04-E06"), true, 3, 5))
        assertFalse(StremioAddons.streamMatchesRequest(stream("Show S03E04-E05"), true, 3, 6))
        // Repeated markers are two separate episodes, not one bogus range.
        assertEquals(EpisodeLabel(3, 3..3), stream("Show S03E03 720p").advertisedEpisodes())
    }

    @Test
    fun aspectRatioLabelsAreNotEpisodeLabels() {
        listOf("Show 16x9", "Show 4x3", "Show 21x9 1080p").forEach { label ->
            assertNull(label, stream(label).advertisedEpisodes())
            assertTrue(label, StremioAddons.streamMatchesRequest(stream(label), true, 1, 1))
        }
    }

    @Test
    fun unparsableEpisodeLabelsAreRejectedNotPassed() {
        // No season number to place the episode in: neither a match nor a pack.
        val loose = stream("Episode 7 1080p")
        assertNull(loose.advertisedEpisodes())
        assertNull(loose.advertisedSeason())
        assertTrue(loose.hasUnparsedEpisodeMarker())
        assertFalse(StremioAddons.streamMatchesRequest(loose, true, 1, 7))
    }

    @Test
    fun onlyStoredImdbIdsConfirmedByTmdbLeadTheSearch() {
        // Scraped/imported ids are not trusted until TMDB resolves the title.
        assertTrue(StremioAddons.canQueryStoredImdb("tt0137523", true, "TMDB"))
        assertFalse(StremioAddons.canQueryStoredImdb("tt0137523", false, "TMDB"))
        assertFalse(StremioAddons.canQueryStoredImdb("tt0137523", true, "MDBList"))
        assertFalse(StremioAddons.canQueryStoredImdb("tt-0137", true, "TMDB"))
        assertFalse(StremioAddons.canQueryStoredImdb("", true, "TMDB"))
    }

    @Test
    fun itemsWithoutATmdbIdNeverProduceAQuery() {
        assertTrue(StremioAddons.contentIds(series(tmdbId = 0), 1, 1).isEmpty())
        assertTrue(StremioAddons.contentIds(movie(tmdbId = 0), null, null).isEmpty())
        assertEquals("", StremioAddons.contentId(series(tmdbId = 0), 1, 1))
        assertEquals("", StremioAddons.contentId(movie(tmdbId = 0), null, null))
    }
}
