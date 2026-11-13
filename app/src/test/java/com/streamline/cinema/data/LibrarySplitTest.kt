package com.streamline.cinema.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySplitTest {

    private val kids = CinemaProfile(id = "kids", name = "Kids", maturity = MaturityLevel.KIDS)
    private val teen = CinemaProfile(id = "teen", name = "Teen", maturity = MaturityLevel.TEEN)
    private val adult = CinemaProfile.default()

    private fun item(id: Int, certification: String? = null) = CatalogItem(
        tmdbId = id,
        type = MediaType.MOVIE,
        title = "Title $id",
        certification = certification
    )

    @Test
    fun `approved title belongs to the kids library only`() {
        val approved = setOf(item(1).key)
        assertTrue(item(1).visibleTo(kids, approved))
        assertFalse(item(1).visibleTo(adult, approved))
        assertFalse(item(1).visibleTo(teen, approved))
    }

    @Test
    fun `unapproved title stays out of the kids library`() {
        val approved = setOf(item(1).key)
        assertTrue(item(2).visibleTo(adult, approved))
        assertFalse(item(2).visibleTo(kids, approved))
    }

    @Test
    fun `unapproving a title returns it to the adult library`() {
        assertFalse(item(1).visibleTo(adult, setOf(item(1).key)))
        assertTrue(item(1).visibleTo(adult, emptySet()))
    }

    @Test
    fun `teen profiles still filter by rating`() {
        val rated = item(3, certification = "R")
        assertFalse(rated.visibleTo(teen, emptySet()))
        assertTrue(rated.visibleTo(adult, emptySet()))
    }

    @Test
    fun `catalog keys are stable so approval survives metadata refresh`() {
        val stored = item(4)
        val refreshed = stored.copy(title = "Renamed", addedAt = stored.addedAt + 5_000)
        assertTrue(refreshed.visibleTo(kids, setOf(stored.key)))
        assertFalse(refreshed.visibleTo(adult, setOf(stored.key)))
    }
}
