package com.streamline.cinema.ui.player

import com.streamline.cinema.data.PlaybackOption
import com.streamline.cinema.data.QualityLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityFilterTest {

    private fun option(name: String) = PlaybackOption(name = name, url = "https://example.test/$name")

    @Test
    fun detectsQualityTokensInSourceLabels() {
        assertEquals("4K", qualityOf(option("Movie 2160p HEVC")))
        assertEquals("4K", qualityOf(option("Movie 4K")))
        assertEquals("8K", qualityOf(option("Movie 4320p")))
        assertEquals("1080p", qualityOf(option("Movie 1080p")))
        assertNull(qualityOf(option("Movie")))
    }

    @Test
    fun fourKSettingDropsFourKAndAboveOnly() {
        val sources = listOf(
            option("Movie 8K"),
            option("Movie 4K"),
            option("Movie 1440p"),
            option("Movie 1080p"),
            option("Movie 720p"),
            option("Movie 480p")
        )
        val kept = excludeHighQualitySources(sources, QualityLimit.Q4K).map { it.name }
        assertEquals(listOf("Movie 1440p", "Movie 1080p", "Movie 720p", "Movie 480p"), kept)
    }

    @Test
    fun oneHundredEightySettingDrops1080AndAbove() {
        val sources = listOf(
            option("Movie 4K"),
            option("Movie 1080p"),
            option("Movie 720p"),
            option("Movie 480p")
        )
        val kept = excludeHighQualitySources(sources, QualityLimit.Q1080).map { it.name }
        assertEquals(listOf("Movie 720p", "Movie 480p"), kept)
    }

    @Test
    fun untaggedSourcesSurviveEverySetting() {
        val sources = listOf(option("Auto"), option("Provider stream"), option("Movie 720p"))
        val kept = excludeHighQualitySources(sources, QualityLimit.Q480).map { it.name }
        assertEquals(listOf("Auto", "Provider stream"), kept)
    }

    @Test
    fun offKeepsEverySource() {
        val sources = listOf(option("Movie 8K"), option("Movie 4K"), option("Movie 720p"))
        assertEquals(sources, excludeHighQualitySources(sources, QualityLimit.OFF))
    }

    @Test
    fun fallsBackToTheFullListWhenNothingIsBelowTheCutoff() {
        val sources = listOf(option("Movie 8K"), option("Movie 4K"))
        assertEquals(sources, excludeHighQualitySources(sources, QualityLimit.Q4K))
    }

    @Test
    fun cutoffBoundariesAreInclusive() {
        assertEquals(true, QualityLimit.Q4K.excludes(2160))
        assertEquals(false, QualityLimit.Q4K.excludes(2159))
        assertEquals(true, QualityLimit.Q1080.excludes(1440))
        assertEquals(false, QualityLimit.Q1080.excludes(720))
        assertEquals(false, QualityLimit.OFF.excludes(4320))
    }
}
