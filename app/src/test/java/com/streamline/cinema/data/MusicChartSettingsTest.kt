package com.streamline.cinema.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicChartSettingsTest {

    @Test
    fun chartSettingsSurviveSettingsJson() {
        val saved = AppSettings(
            musicCharts = false,
            musicChartSource = ChartSource.DEEZER.name,
            musicChartCountry = "GB",
            musicChartGenre = "kpop",
            youtubeApiKey = "AIza-test"
        )
        val restored = AppSettings.fromJson(saved.toJson())
        assertFalse(restored.musicCharts)
        assertEquals(ChartSource.DEEZER.name, restored.musicChartSource)
        assertEquals("GB", restored.musicChartCountry)
        assertEquals("kpop", restored.musicChartGenre)
        assertEquals("AIza-test", restored.youtubeApiKey)
    }

    @Test
    fun settingsWrittenBeforeChartsDefaultToApple() {
        val restored = AppSettings.fromJson(JSONObject())
        assertTrue(restored.musicCharts)
        assertEquals(ChartSource.APPLE.name, restored.musicChartSource)
        assertEquals("", restored.musicChartCountry)
        assertEquals(ANY_GENRE_ID, restored.musicChartGenre)
        assertEquals("", restored.youtubeApiKey)
    }

    @Test
    fun normalizedRepairsUnknownChartValues() {
        val repaired = AppSettings(
            musicChartSource = "deezer",
            musicChartCountry = " gb ",
            musicChartGenre = "bogus",
            youtubeApiKey = "  AIza-test  "
        ).normalized()
        assertEquals(ChartSource.DEEZER.name, repaired.musicChartSource)
        assertEquals("GB", repaired.musicChartCountry)
        assertEquals(ANY_GENRE_ID, repaired.musicChartGenre)
        assertEquals("AIza-test", repaired.youtubeApiKey)
    }

    @Test
    fun normalizedRejectsUnknownSourcesAndCountries() {
        val repaired = AppSettings(
            musicChartSource = "SPOTIFY",
            musicChartCountry = "ZZ"
        ).normalized()
        assertEquals(ChartSource.APPLE.name, repaired.musicChartSource)
        assertEquals("", repaired.musicChartCountry)
    }

    @Test
    fun requestFallsBackToAppleWithoutAYouTubeKey() {
        val request = ChartSelection.request("YOUTUBE", "GB", "pop", "")
        assertEquals(ChartSource.APPLE, request.source)
        assertEquals("GB", request.country.code)
        assertEquals("pop", request.genre.id)
    }

    @Test
    fun requestUsesYouTubeOnceAKeyExists() {
        val request = ChartSelection.request("YOUTUBE", "GB", "pop", "AIza-test")
        assertEquals(ChartSource.YOUTUBE, request.source)
        assertEquals("GB", request.country.code)
    }

    @Test
    fun requestFallsBackToTheDeviceStorefrontAndAnyGenre() {
        val request = ChartSelection.request(null, null, null, "")
        assertEquals(ChartSource.APPLE, request.source)
        assertEquals(ChartCountries.defaultFor(), request.country)
        assertEquals(ChartGenres.any, request.genre)
    }

    @Test
    fun availableHidesYouTubeUntilAKeyExists() {
        assertEquals(listOf(ChartSource.APPLE, ChartSource.DEEZER), ChartSelection.available(""))
        assertEquals(listOf(ChartSource.APPLE, ChartSource.DEEZER), ChartSelection.available("   "))
        assertTrue(ChartSelection.available("AIza-test").contains(ChartSource.YOUTUBE))
    }

    @Test
    fun describeLeavesOutDimensionsTheSourceCannotHonour() {
        val deezer = ChartSelection.request("DEEZER", "GB", "rock", "")
        assertFalse(deezer.describe().contains("United Kingdom"))
        assertTrue(deezer.describe().contains("Global"))
        assertTrue(deezer.describe().contains("Rock"))

        val youtube = ChartSelection.request("YOUTUBE", "GB", "rock", "AIza-test")
        assertTrue(youtube.describe().contains("United Kingdom"))
        assertFalse(youtube.describe().contains("Rock"))
    }
}
