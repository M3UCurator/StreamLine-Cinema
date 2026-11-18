package com.streamline.cinema.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MusicChartsTest {

    @Test
    fun countryCatalogueIsWellFormed() {
        val codes = ChartCountries.all.map { it.code }
        assertEquals(codes.size, codes.distinct().size)
        assertTrue(codes.all { it.matches(Regex("[A-Z]{2}")) })
        assertTrue(ChartCountries.all.all { it.name.isNotBlank() })
        assertEquals(ChartCountries.all.sortedBy { it.name }, ChartCountries.all)
        assertTrue(ChartCountries.all.any { it.code == "ZA" })
        assertTrue(ChartCountries.all.any { it.code == "US" })
    }

    @Test
    fun countryLookupIgnoresCaseAndRejectsUnknownCodes() {
        assertEquals("South Africa", ChartCountries.find("za")?.name)
        assertNull(ChartCountries.find("ZZ"))
        assertNull(ChartCountries.find(""))
        assertNull(ChartCountries.find(null))
    }

    @Test
    fun defaultCountryFollowsTheDeviceLocale() {
        assertEquals("ZA", ChartCountries.defaultFor(Locale("en", "ZA")).code)
        assertEquals("US", ChartCountries.defaultFor(Locale("en", "XX")).code)
        assertEquals("US", ChartCountries.defaultFor(Locale.ROOT).code)
    }

    @Test
    fun genreCatalogueIsUniqueAndLeadsWithAll() {
        assertSame(ChartGenres.any, ChartGenres.all.first())
        assertEquals(ANY_GENRE_ID, ChartGenres.any.id)
        val ids = ChartGenres.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertEquals(ChartGenres.all.drop(1).sortedBy { it.label }, ChartGenres.all.drop(1))
    }

    @Test
    fun genreMatchingToleratesSpellingAndOrder() {
        val hipHop = ChartGenres.find("hiphop")!!
        assertTrue(hipHop.matches("Hip-Hop/Rap"))
        assertTrue(hipHop.matches("Rap/Hip Hop"))
        assertTrue(hipHop.matches("Rap"))

        val rnb = ChartGenres.find("rnb")!!
        assertTrue(rnb.matches("R&B/Soul"))
        assertTrue(rnb.matches("Soul & Funk"))

        val afro = ChartGenres.find("afrobeats")!!
        assertTrue(afro.matches("Afro-Pop"))
        assertTrue(afro.matches("African Music"))

        assertFalse(ChartGenres.find("pop")!!.matches("Afro-Pop"))
        assertFalse(ChartGenres.find("pop")!!.matches("K-Pop"))
        assertTrue(ChartGenres.any.matches("Anything At All"))
    }

    @Test
    fun capabilityTableMatchesWhatTheApisPublish() {
        assertTrue(ChartSource.APPLE.supportsCountry)
        assertTrue(ChartSource.APPLE.supportsGenre)
        assertFalse(ChartSource.APPLE.requiresKey)

        assertFalse(ChartSource.DEEZER.supportsCountry)
        assertTrue(ChartSource.DEEZER.supportsGenre)
        assertFalse(ChartSource.DEEZER.requiresKey)

        assertTrue(ChartSource.YOUTUBE.requiresKey)
        assertTrue(ChartSource.YOUTUBE.supportsCountry)
        assertFalse(ChartSource.YOUTUBE.supportsGenre)
    }

    @Test
    fun describeDropsDimensionsTheSourceCannotHonour() {
        val za = ChartCountries.find("ZA")!!
        val hipHop = ChartGenres.find("hiphop")!!

        assertEquals(
            "APPLE MUSIC · South Africa · Hip-Hop/Rap",
            ChartRequest(ChartSource.APPLE, za, hipHop).describe()
        )
        assertEquals(
            "DEEZER · Global · Hip-Hop/Rap",
            ChartRequest(ChartSource.DEEZER, za, hipHop).describe()
        )
        assertEquals(
            "YOUTUBE · South Africa",
            ChartRequest(ChartSource.YOUTUBE, za, hipHop).describe()
        )
    }
}
