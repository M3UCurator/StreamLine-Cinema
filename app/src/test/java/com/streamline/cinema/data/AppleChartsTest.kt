package com.streamline.cinema.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AppleChartsTest {

    private val southAfrica = ChartCountries.find("ZA")!!

    private fun request(genre: ChartGenre = ChartGenres.any) =
        ChartRequest(ChartSource.APPLE, southAfrica, genre)

    private val feed = """
        {"feed":{"title":"Most Played","country":"za","results":[
          {"artistName":"Tyla","id":"111","name":"Water",
           "genres":[{"genreId":"18","name":"Dance"}],
           "url":"https://music.apple.com/za/song/111",
           "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music1/100x100bb.jpg"},
          {"artistName":"A-Reece","id":"222","name":"Rap Song",
           "genres":[{"genreId":"18","name":"Hip-Hop/Rap"}],
           "url":"https://music.apple.com/za/song/222",
           "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music2/100x100bb.jpg"},
          {"artistName":"Afro Kid","id":"333","name":"Afro",
           "genres":[{"genreId":"18","name":"Afro-Pop"}],
           "url":"https://music.apple.com/za/song/333",
           "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music3/100x100bb.jpg"}
        ]}}
    """.trimIndent()

    @Test
    fun urlCarriesStorefrontAndLimit() {
        assertEquals(
            "https://rss.applemarketingtools.com/api/v2/za/music/most-played/50/songs.json",
            appleChartUrl(southAfrica, 50)
        )
        assertEquals(
            "https://rss.applemarketingtools.com/api/v2/za/music/most-played/10/songs.json",
            appleChartUrl(southAfrica, 10)
        )
    }

    @Test
    fun parseMapsRowsAndUpscalesArtwork() {
        val rows = AppleChartJson.parse(feed, request(), 50)
        assertEquals(listOf("apple:111", "apple:222", "apple:333"), rows.map { it.id })

        val water = rows.first()
        assertEquals("Water", water.title)
        assertEquals("Tyla", water.artist)
        assertEquals("Dance", water.genreLabel)
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music1/600x600bb.jpg",
            water.artwork
        )
        assertEquals("https://music.apple.com/za/song/111", water.openUrl)
        assertNull(water.videoId)
        assertEquals(ChartSource.APPLE, water.source)
    }

    @Test
    fun parseFiltersByGenreWordSet() {
        val hipHop = AppleChartJson.parse(feed, request(ChartGenres.find("hiphop")!!), 50)
        assertEquals(listOf("apple:222"), hipHop.map { it.id })

        val afro = AppleChartJson.parse(feed, request(ChartGenres.find("afrobeats")!!), 50)
        assertEquals(listOf("apple:333"), afro.map { it.id })

        // None of the three rows is plain Pop: "Afro-Pop" carries its own word set.
        assertTrue(AppleChartJson.parse(feed, request(ChartGenres.find("pop")!!), 50).isEmpty())
    }

    @Test
    fun parseCapsAtTheLimit() {
        val rows = AppleChartJson.parse(feed, request(), 2)
        assertEquals(listOf("apple:111", "apple:222"), rows.map { it.id })
    }

    @Test(expected = IOException::class)
    fun missingFeedIsAnError() {
        AppleChartJson.parse("""{"feed":{}}""", request(), 50)
    }

    @Test
    fun cacheServesRepeatCallsAndExpiresAfterSixHours() = runBlocking {
        var calls = 0
        var clock = 1_000_000L
        val provider = AppleChartsProvider(
            fetch = { calls++; feed },
            cache = ChartCache(now = { clock })
        )
        val ask = request()

        assertEquals(3, provider.chart(ask).rows.size)
        provider.chart(ask)
        assertEquals(1, calls)

        clock += ChartCache.DEFAULT_TTL_MS - 1
        provider.chart(ask)
        assertEquals(1, calls)

        clock += 1
        provider.chart(ask)
        assertEquals(2, calls)
    }
}
