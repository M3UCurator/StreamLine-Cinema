package com.streamline.cinema.ui.player

import com.streamline.cinema.data.PlaybackOption
import com.streamline.cinema.data.QualityLimit
import com.streamline.cinema.data.SourceSizeLimit
import com.streamline.cinema.data.StreamMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityFilterTest {

    private fun option(name: String) = PlaybackOption(name = name, url = "https://example.test/$name")

    private fun sized(name: String, size: String) = PlaybackOption(
        name = name,
        url = "https://example.test/$name",
        meta = StreamMeta(size = size)
    )

    @Test
    fun detectsQualityTokensInSourceLabels() {
        assertEquals("4K", qualityOf(option("Movie 2160p HEVC")))
        assertEquals("4K", qualityOf(option("Movie 4K")))
        assertEquals("8K", qualityOf(option("Movie 4320p")))
        assertEquals("1080p", qualityOf(option("Movie 1080p")))
        assertNull(qualityOf(option("Movie")))
    }

    @Test
    fun fourKSettingKeepsFourKAndDropsAboveOnly() {
        val sources = listOf(
            option("Movie 8K"),
            option("Movie 4K"),
            option("Movie 1440p"),
            option("Movie 1080p"),
            option("Movie 720p"),
            option("Movie 480p")
        )
        val kept = excludeHighQualitySources(sources, QualityLimit.Q4K).map { it.name }
        assertEquals(
            listOf("Movie 4K", "Movie 1440p", "Movie 1080p", "Movie 720p", "Movie 480p"),
            kept
        )
    }

    @Test
    fun oneHundredEightySettingKeeps1080pAndDropsAbove() {
        val sources = listOf(
            option("Movie 4K"),
            option("Movie 1440p"),
            option("Movie 1080p"),
            option("Movie 720p"),
            option("Movie 480p")
        )
        val kept = excludeHighQualitySources(sources, QualityLimit.Q1080).map { it.name }
        assertEquals(listOf("Movie 1080p", "Movie 720p", "Movie 480p"), kept)
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
        val sources = listOf(option("Movie 8K"), option("Movie 4320p HEVC"))
        assertEquals(sources, excludeHighQualitySources(sources, QualityLimit.Q4K))
    }

    @Test
    fun cutoffBoundariesAreExclusive() {
        assertEquals(false, QualityLimit.Q4K.excludes(2160))
        assertEquals(true, QualityLimit.Q4K.excludes(4320))
        assertEquals(false, QualityLimit.Q1080.excludes(1080))
        assertEquals(true, QualityLimit.Q1080.excludes(1440))
        assertEquals(false, QualityLimit.Q1080.excludes(720))
        assertEquals(false, QualityLimit.OFF.excludes(4320))
    }

    @Test
    fun parsesStatedFileSizesToBytes() {
        assertEquals(39_200_000_000L, sourceSizeBytes(sized("Remux", "39.2 GB")))
        assertEquals(700_000_000L, sourceSizeBytes(sized("Small", "700 MB")))
        assertEquals(1_500_000_000_000L, sourceSizeBytes(sized("Huge", "1.5 TB")))
        assertEquals(0L, sourceSizeBytes(option("Movie 1080p")))
    }

    @Test
    fun sizeCapKeepsTheCapAndEverythingBelowIt() {
        val sources = listOf(
            sized("Remux 4K", "62.0 GB"),
            sized("1080p WEB", "8.0 GB"),
            sized("720p WEB", "2.5 GB"),
            option("Unknown size")
        )
        val kept = excludeOversizedSources(sources, SourceSizeLimit.GB8).map { it.name }
        assertEquals(listOf("1080p WEB", "720p WEB", "Unknown size"), kept)
    }

    @Test
    fun sizeCapIsOffByDefault() {
        val sources = listOf(sized("Remux 4K", "62.0 GB"), sized("720p WEB", "2.5 GB"))
        assertEquals(sources, excludeOversizedSources(sources, SourceSizeLimit.OFF))
    }

    @Test
    fun sizeCapFallsBackToTheFullListWhenEverythingIsTooLarge() {
        val sources = listOf(sized("Remux 4K", "62.0 GB"), sized("1080p REMUX", "30.0 GB"))
        assertEquals(sources, excludeOversizedSources(sources, SourceSizeLimit.GB4))
    }

    @Test
    fun sizeBoundariesAreExclusive() {
        assertEquals(false, SourceSizeLimit.GB2.excludes(2_000_000_000L))
        assertEquals(true, SourceSizeLimit.GB2.excludes(2_000_000_001L))
        assertEquals(false, SourceSizeLimit.GB8.excludes(8_000_000_000L))
        assertEquals(true, SourceSizeLimit.GB8.excludes(8_000_000_001L))
        assertEquals(false, SourceSizeLimit.OFF.excludes(62_000_000_000L))
    }
}
