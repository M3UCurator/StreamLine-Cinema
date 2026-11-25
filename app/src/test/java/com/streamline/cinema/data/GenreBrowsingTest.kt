package com.streamline.cinema.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreBrowsingTest {

    @Test
    fun chipsAreTheApprovedVocabularyInOrder() {
        assertEquals(
            listOf(
                "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime",
                "Documentary", "Drama", "Family", "Fantasy", "History", "Horror",
                "Mystery", "Romance", "Sci-Fi", "Sport", "Thriller", "War", "Western"
            ),
            BROWSE_GENRES.map { it.name }
        )
    }

    @Test
    fun everyChipResolvesForBothScopes() {
        BROWSE_GENRES.forEach { genre ->
            assertTrue(genre.name, genre.genreIds(MediaType.MOVIE).isNotEmpty() || genre.needsKeyword(MediaType.MOVIE))
            assertTrue(genre.name, genre.genreIds(MediaType.TV).isNotEmpty() || genre.needsKeyword(MediaType.TV))
        }
    }

    @Test
    fun idsFollowTheChosenScope() {
        assertEquals(setOf(28), browseGenreIds(listOf("Action"), MediaType.MOVIE))
        assertEquals(setOf(10759), browseGenreIds(listOf("Action"), MediaType.TV))
        assertEquals(setOf(878), browseGenreIds(listOf("Sci-Fi"), MediaType.MOVIE))
        assertEquals(setOf(10765), browseGenreIds(listOf("Sci-Fi"), MediaType.TV))
        assertEquals(setOf(10752), browseGenreIds(listOf("War"), MediaType.MOVIE))
        assertEquals(setOf(10768), browseGenreIds(listOf("War"), MediaType.TV))
    }

    @Test
    fun keywordFallbacksCoverWhatTmdbHasNoGenreFor() {
        val all = BROWSE_GENRES.map { it.name }
        assertEquals(
            setOf("biography", "sport"),
            browseKeywordNames(all, MediaType.MOVIE).toSet()
        )
        assertEquals(
            setOf("biography", "sport", "history", "horror", "romance", "thriller"),
            browseKeywordNames(all, MediaType.TV).toSet()
        )
        assertEquals(setOf(28), browseGenreIds(listOf("Action", "Biography"), MediaType.MOVIE))
    }

    @Test
    fun localNamesJoinTheChipLabelsUsedOnCatalogItems() {
        val sciFi = BROWSE_GENRES.first { it.name == "Sci-Fi" }
        assertTrue("Science Fiction" in sciFi.localNames(MediaType.MOVIE))
        assertTrue("Sci-Fi & Fantasy" in sciFi.localNames(MediaType.TV))
        assertTrue("Science Fiction" in sciFi.localNames(null))

        val biography = BROWSE_GENRES.first { it.name == "Biography" }
        assertEquals(setOf("Biography"), biography.localNames(MediaType.MOVIE))
    }
}
