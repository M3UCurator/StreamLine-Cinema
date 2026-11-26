package com.streamline.cinema.ui

import com.streamline.cinema.data.LiveChannel
import com.streamline.cinema.ui.screens.visibleLiveChannels
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveChannelFilterTest {

    private val addon = "https://live.example/manifest.json"

    private fun channel(id: String, name: String) =
        LiveChannel(id = id, name = name, type = "tv", addonUrl = addon)

    private val channels = listOf(
        channel("1", "BBC One HD"),
        channel("2", "Sky Sports F1"),
        channel("3", "Discovery Channel"),
        channel("4", "Sports News 24")
    )

    private fun visible(
        query: String = "",
        searching: Boolean = false,
        favoritesOnly: Boolean = false,
        favorites: Set<String> = emptySet(),
        isKids: Boolean = false,
        approved: Set<String> = emptySet(),
        hidden: Set<String> = emptySet()
    ) = visibleLiveChannels(
        channels = channels,
        query = query,
        searching = searching,
        favoritesOnly = favoritesOnly,
        favorites = favorites,
        isKids = isKids,
        approved = approved,
        hidden = hidden
    ).map { it.name }

    @Test
    fun `search matches channel names case-insensitively`() {
        assertEquals(
            listOf("Sky Sports F1", "Sports News 24"),
            visible(query = "SPORTS", searching = true)
        )
    }

    @Test
    fun `search trims surrounding whitespace`() {
        assertEquals(
            listOf("Discovery Channel"),
            visible(query = "  discovery ", searching = true)
        )
    }

    @Test
    fun `blank query keeps the whole loaded playlist`() {
        assertEquals(channels.map { it.name }, visible(query = "   ", searching = true))
    }

    @Test
    fun `query is ignored when search is not active`() {
        assertEquals(channels.map { it.name }, visible(query = "sports", searching = false))
    }

    @Test
    fun `search applies on top of favorites`() {
        val favourites = setOf(channels[1].key, channels[3].key)
        assertEquals(
            listOf("Sports News 24"),
            visible(
                query = "24",
                searching = true,
                favoritesOnly = true,
                favorites = favourites
            )
        )
    }

    @Test
    fun `kids approval filters before the search runs`() {
        val approved = setOf(channels[0].key)
        assertEquals(
            listOf("BBC One HD"),
            visible(query = "b", searching = true, isKids = true, approved = approved)
        )
    }

    @Test
    fun `no match yields an empty list`() {
        assertEquals(emptyList<String>(), visible(query = "zzz", searching = true))
    }

    @Test
    fun `hidden channels drop out of the plain list`() {
        assertEquals(
            listOf("BBC One HD", "Discovery Channel", "Sports News 24"),
            visible(hidden = setOf(channels[1].key))
        )
    }

    @Test
    fun `hidden channels stay out of search and favorites`() {
        val favourites = setOf(channels[1].key, channels[3].key)
        val hidden = setOf(channels[3].key)
        assertEquals(
            listOf("Sky Sports F1"),
            visible(searching = true, query = "s", favoritesOnly = true, favorites = favourites, hidden = hidden)
        )
    }

    @Test
    fun `hiding every channel yields an empty list`() {
        assertEquals(emptyList<String>(), visible(hidden = channels.map { it.key }.toSet()))
    }
}
