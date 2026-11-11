package com.streamline.cinema.data

import java.util.Locale

/**
 * Language and country rules for Smart Search & Play. All of it is derived
 * from [AppSettings] on every call, so changing a setting takes effect on the
 * next fetch without any cached decision to invalidate.
 */
internal fun AppSettings.tmdbLanguageTag(): String {
    val language = contentLanguage.ifBlank { "en" }
    val country = contentCountry.ifBlank { "US" }
    return "$language-$country"
}

/** Discover-only language filter; search filtering happens on the parsed results. */
internal fun AppSettings.discoverLanguageFilter(): String? =
    contentLanguage.takeIf { it.isNotBlank() && languageRule == LanguageRule.ONLY }

/** Origin-country filter for discover shelves; blank keeps every country. */
internal fun AppSettings.discoverCountryFilter(): String? =
    contentCountry.takeIf { it.isNotBlank() }

/** Language used for ranking; blank means the feature is off. */
internal fun AppSettings.rankingLanguage(): String = contentLanguage

/**
 * Stable partition of a TMDB-ordered list: preferred-language titles first,
 * then records with no language, then everything known to be another
 * language. Items that were already in order stay in order inside each band.
 */
internal fun rankByLanguage(items: List<CatalogItem>, language: String): List<CatalogItem> {
    if (language.isBlank() || items.size < 2) return items
    val wanted = language.lowercase(Locale.US)
    return items.sortedBy {
        when {
            it.originalLanguage.isBlank() -> 1
            it.originalLanguage.equals(wanted, ignoreCase = true) -> 0
            else -> 2
        }
    }
}

/**
 * Drops titles TMDB reports in another language. Records without a language
 * stay: imported lists and the user's own library never carried one, and
 * hiding them would make the library look empty.
 */
internal fun filterKnownOtherLanguages(items: List<CatalogItem>, language: String): List<CatalogItem> {
    if (language.isBlank()) return items
    return items.filter {
        it.originalLanguage.isBlank() || it.originalLanguage.equals(language, ignoreCase = true)
    }
}

private val LANGUAGE_TOKENS: Map<String, Set<String>> = mapOf(
    "en" to setOf("EN", "ENG", "ENGLISH"),
    "hi" to setOf("HI", "HIN", "HINDI"),
    "ne" to setOf("NE", "NEP", "NEPALI"),
    "es" to setOf("ES", "SPA", "SPANISH", "CASTELLANO", "LATINO"),
    "fr" to setOf("FR", "FRA", "FRE", "FRENCH", "TRUEFRENCH", "VFF", "VFQ", "VF2"),
    "de" to setOf("DE", "GER", "DEU", "GERMAN"),
    "pt" to setOf("PT", "POR", "PORTUGUESE", "DUBLADO"),
    "ar" to setOf("AR", "ARA", "ARABIC"),
    "ja" to setOf("JA", "JPN", "JAPANESE"),
    "ko" to setOf("KO", "KOR", "KOREAN"),
    "ru" to setOf("RU", "RUS", "RUSSIAN")
)

/**
 * How well one addon stream advertises the preferred language:
 * 0 = named, 1 = MULTI, 2 = no language tag, 3 = only another language.
 * Tags are free text (`FR · MULTI`, `VOSTFR`), so this only reorders sources;
 * it never hides one.
 */
internal fun streamLanguageTier(languages: String, preferred: String): Int {
    val tokens = languages.uppercase(Locale.US)
        .split(Regex("[^A-Z]+"))
        .filter { it.isNotBlank() }
    if (tokens.isEmpty()) return 2
    val wanted = LANGUAGE_TOKENS[preferred.lowercase(Locale.US)].orEmpty() +
        preferred.uppercase(Locale.US)
    return when {
        tokens.any { it in wanted } -> 0
        tokens.any { it == "MULTI" } -> 1
        else -> 3
    }
}

/**
 * Moves addon sources tagged with the preferred language ahead of untagged
 * and foreign ones. If nothing is tagged for the language, the original order
 * is kept untouched.
 */
internal fun rankSources(options: List<PlaybackOption>, language: String): List<PlaybackOption> {
    if (language.isBlank() || options.size < 2) return options
    val tiers = options.map { streamLanguageTier(it.meta.languages, language) }
    if (tiers.none { it <= 1 }) return options
    return options.withIndex()
        .sortedWith(compareBy({ tiers[it.index] }, { it.index }))
        .map { it.value }
}
