package com.streamline.cinema.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextUpEpisodeTest {
    private val today = "2026-11-07"

    private fun season(number: Int, episodes: Int, airDate: String? = "2026-01-01") = Season(
        number = number,
        episodes = (1..episodes).map { Episode(number = it, airDate = airDate) }
    )

    private val seasons = listOf(season(1, 5), season(2, 3))

    @Test
    fun noHistoryStartsAtFirstAiredEpisode() {
        assertEquals(1 to 1, nextUpEpisode(seasons, emptySet(), null, useFurthest = true, today = today))
    }

    @Test
    fun furthestWatchedIgnoresRewatchProgress() {
        // Finished S1E4, then rewound to S1E2 mid-episode.
        val watched = setOf(1 to 1, 1 to 2, 1 to 3, 1 to 4)
        assertEquals(
            1 to 5,
            nextUpEpisode(seasons, watched, recent = 1 to 2, useFurthest = true, today = today)
        )
    }

    @Test
    fun disabledToggleFollowsMostRecentEpisode() {
        val watched = setOf(1 to 1, 1 to 2, 1 to 3, 1 to 4)
        assertEquals(
            1 to 3,
            nextUpEpisode(seasons, watched, recent = 1 to 2, useFurthest = false, today = today)
        )
    }

    @Test
    fun furthestRollsIntoTheNextSeason() {
        assertEquals(
            2 to 1,
            nextUpEpisode(seasons, setOf(1 to 5), recent = 1 to 5, useFurthest = true, today = today)
        )
    }

    @Test
    fun caughtUpReturnsNull() {
        val watched = (1..5).map { 1 to it }.toSet() + (1..3).map { 2 to it }.toSet()
        assertNull(nextUpEpisode(seasons, watched, recent = 2 to 3, useFurthest = true, today = today))
    }

    @Test
    fun unairedEpisodesAreSkipped() {
        val partiallyAired = listOf(
            Season(
                number = 1,
                episodes = listOf(
                    Episode(number = 1, airDate = "2026-01-01"),
                    Episode(number = 2, airDate = "2027-01-01")
                )
            )
        )
        assertNull(
            nextUpEpisode(partiallyAired, setOf(1 to 1), recent = 1 to 1, useFurthest = true, today = today)
        )
    }
}
