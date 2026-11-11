package com.streamline.cinema.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smart Search & Play preferences must survive a settings round-trip and must
 * never leave the app filtering by a language that is not set.
 */
class ContentPreferencesTest {

    @Test
    fun defaultsMatchThePreSmartBehaviour() {
        val settings = AppSettings()
        assertEquals("", settings.contentLanguage)
        assertEquals("", settings.contentCountry)
        assertEquals(LanguageRule.PREFER, settings.languageRule)
        assertTrue(settings.preferLanguageSources)
    }

    @Test
    fun settingsRoundTripKeepsEveryContentPreference() {
        val stored = AppSettings()
            .copy(
                contentLanguage = "hi",
                contentCountry = "IN",
                languageRule = LanguageRule.ONLY,
                preferLanguageSources = false
            )
            .normalized()

        val restored = AppSettings.fromJson(stored.toJson())

        assertEquals("hi", restored.contentLanguage)
        assertEquals("IN", restored.contentCountry)
        assertEquals(LanguageRule.ONLY, restored.languageRule)
        assertFalse(restored.preferLanguageSources)
    }

    @Test
    fun unknownLanguageAndCountryFallBackToNeutralValues() {
        val restored = AppSettings.fromJson(
            AppSettings().toJson()
                .put("contentLanguage", "xx")
                .put("contentCountry", "ZZ")
        )

        assertEquals("", restored.contentLanguage)
        assertEquals("", restored.contentCountry)
        assertEquals(LanguageRule.PREFER, restored.languageRule)
    }

    @Test
    fun onlyRuleWithoutLanguageDegradesToPrefer() {
        val stored = AppSettings()
            .copy(languageRule = LanguageRule.ONLY)
            .normalized()

        assertEquals("", stored.contentLanguage)
        assertEquals(LanguageRule.PREFER, stored.languageRule)
    }

    @Test
    fun catalogItemKeepsOriginalLanguageThroughJson() {
        val item = CatalogItem(
            tmdbId = 20453,
            type = MediaType.MOVIE,
            title = "3 Idiots",
            originalLanguage = "hi"
        )

        assertEquals("hi", CatalogItem.fromJson(item.toJson()).originalLanguage)
        assertEquals("", CatalogItem.fromJson(item.copy(originalLanguage = "").toJson()).originalLanguage)
    }

    @Test
    fun legacyJsonWithoutContentKeysLoadsNeutralDefaults() {
        val legacy = AppSettings().toJson().apply {
            remove("contentLanguage")
            remove("contentCountry")
            remove("languageRule")
            remove("preferLanguageSources")
        }

        val restored = AppSettings.fromJson(legacy)

        assertEquals("", restored.contentLanguage)
        assertEquals("", restored.contentCountry)
        assertEquals(LanguageRule.PREFER, restored.languageRule)
        assertTrue(restored.preferLanguageSources)
    }

    @Test
    fun languageTagDefaultsToEnglishUsAndFollowsBothPreferences() {
        assertEquals("en-US", AppSettings().tmdbLanguageTag())
        assertEquals("hi-IN", AppSettings().copy(contentLanguage = "hi", contentCountry = "IN").tmdbLanguageTag())
        assertEquals("hi-US", AppSettings().copy(contentLanguage = "hi").tmdbLanguageTag())
        assertEquals("en-IN", AppSettings().copy(contentCountry = "IN").tmdbLanguageTag())
    }

    @Test
    fun discoverLanguageFilterOnlyAppliesToTheOnlyRule() {
        val prefer = AppSettings().copy(contentLanguage = "hi")
        val only = prefer.copy(languageRule = LanguageRule.ONLY)

        assertEquals(null, prefer.discoverLanguageFilter())
        assertEquals("hi", only.discoverLanguageFilter())
        assertEquals(null, AppSettings().copy(languageRule = LanguageRule.ONLY).discoverLanguageFilter())
    }

    @Test
    fun originCountryFilterAppliesWheneverACountryIsChosen() {
        assertEquals(null, AppSettings().discoverCountryFilter())
        assertEquals(
            "IN",
            AppSettings().copy(contentCountry = "IN", languageRule = LanguageRule.PREFER)
                .discoverCountryFilter()
        )
        assertEquals(
            "NP",
            AppSettings().copy(contentCountry = "NP", languageRule = LanguageRule.ONLY)
                .discoverCountryFilter()
        )
    }
}
