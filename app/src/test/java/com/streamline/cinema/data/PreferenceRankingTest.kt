package com.streamline.cinema.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smart ordering is a stable partition, never a filter, unless the caller asks
 * for filtering explicitly. Source ordering only shuffles addon streams.
 */
class PreferenceRankingTest {

    private fun item(id: Int, language: String) = CatalogItem(
        tmdbId = id,
        type = MediaType.MOVIE,
        title = "Title $id",
        originalLanguage = language
    )

    private fun source(name: String, languages: String) = PlaybackOption(
        name = name,
        url = "https://cdn.test/$name.mp4",
        meta = StreamMeta(languages = languages)
    )

    @Test
    fun blankPreferenceLeavesOrderUntouched() {
        val items = listOf(item(1, "en"), item(2, "hi"), item(3, ""))

        assertEquals(items, rankByLanguage(items, ""))
        assertEquals(items, rankByLanguage(items, "   "))
    }

    @Test
    fun matchedThenUnknownThenOtherKeepsOriginalOrderInsideBands() {
        val items = listOf(
            item(1, "en"),
            item(2, "hi"),
            item(3, ""),
            item(4, "hi"),
            item(5, "fr")
        )

        assertEquals(
            listOf(2, 4, 3, 1, 5),
            rankByLanguage(items, "HI").map { it.tmdbId }
        )
    }

    @Test
    fun onlyRuleHidesKnownOtherLanguagesButKeepsUnknown() {
        val items = listOf(item(1, "en"), item(2, "hi"), item(3, ""))

        assertEquals(
            listOf(2, 3),
            filterKnownOtherLanguages(items, "hi").map { it.tmdbId }
        )
        assertEquals(items, filterKnownOtherLanguages(items, ""))
    }

    @Test
    fun streamTokensMapToEveryOfferedLanguage() {
        assertEquals(0, streamLanguageTier("FR · MULTI", "fr"))
        assertEquals(0, streamLanguageTier("HINDI", "hi"))
        assertEquals(0, streamLanguageTier("Nepali", "ne"))
        assertEquals(0, streamLanguageTier("TRUEFRENCH", "fr"))
        assertEquals(1, streamLanguageTier("MULTI", "hi"))
        assertEquals(2, streamLanguageTier("", "hi"))
        assertEquals(3, streamLanguageTier("EN / FR", "hi"))
        // VOSTFR is original audio with French subtitles, not French audio.
        assertEquals(3, streamLanguageTier("VOSTFR", "fr"))
    }

    @Test
    fun rankSourcesMovesTaggedAddonsAheadAndKeepsTheirOrder() {
        val options = listOf(
            source("foreign", "FR"),
            source("tagged", "EN"),
            source("untagged", ""),
            source("multi", "MULTI")
        )

        // Untagged stays ahead of a known foreign tag: it may still be the preferred language.
        assertEquals(
            listOf("tagged", "multi", "untagged", "foreign"),
            rankSources(options, "en").map { it.name }
        )
    }

    @Test
    fun rankSourcesLeavesOrderAloneWhenNothingMatches() {
        val options = listOf(source("fr", "FR"), source("de", "DE"), source("none", ""))

        assertEquals(options, rankSources(options, "hi"))
    }

    @Test
    fun rankSourcesIsANoOpWithoutAPreferenceOrWithOneSource() {
        val options = listOf(source("fr", "FR"), source("en", "EN"))

        assertEquals(options, rankSources(options, ""))
        assertEquals(
            listOf("fr"),
            rankSources(listOf(source("fr", "FR")), "en").map { it.name }
        )
    }

    @Test
    fun rankingNeverDropsATitle() {
        val items = listOf(item(1, "en"), item(2, "hi"), item(3, ""))

        assertEquals(items.size, rankByLanguage(items, "hi").size)
        assertEquals(items.map { it.key }.toSet(), rankByLanguage(items, "hi").map { it.key }.toSet())
        assertTrue(rankByLanguage(items, "hi").first().originalLanguage == "hi")
    }
}
