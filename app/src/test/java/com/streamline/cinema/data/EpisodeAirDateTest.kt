package com.streamline.cinema.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class EpisodeAirDateTest {
    private fun date(raw: String) = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw)!!

    @Test
    fun formatsPastDate() {
        assertEquals("Aired Sep 12, 2024", formatEpisodeAirDate("2024-09-12", date("2026-11-01")))
    }

    @Test
    fun formatsFutureDate() {
        assertEquals("Airs Sep 12, 2027", formatEpisodeAirDate("2027-09-12", date("2026-11-01")))
    }

    @Test
    fun airingTodayReadsAsAired() {
        assertEquals("Aired Nov 1, 2026", formatEpisodeAirDate("2026-11-01", date("2026-11-01")))
    }

    @Test
    fun blanksAndGarbageReturnNull() {
        assertNull(formatEpisodeAirDate(null))
        assertNull(formatEpisodeAirDate(""))
        assertNull(formatEpisodeAirDate("TBA"))
        assertNull(formatEpisodeAirDate("2024-13-40"))
    }
}
