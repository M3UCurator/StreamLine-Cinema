package com.streamline.cinema.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DeezerChartsTest {

    private val southAfrica = ChartCountries.find("ZA")!!

    private val genresBody = """
        {"data":[
          {"id":0,"name":"All"},
          {"id":132,"name":"Pop"},
          {"id":116,"name":"Rap/Hip Hop"},
          {"id":113,"name":"Dance"},
          {"id":165,"name":"R&B"},
          {"id":186,"name":"Christian"},
          {"id":2,"name":"African Music"},
          {"id":81,"name":"Indian Music"},
          {"id":197,"name":"Latin Music"}
        ]}
    """.trimIndent()

    private val tracksBody = """
        {"data":[
          {"id":2758844701,"title":"Stargazing",
           "link":"https://www.deezer.com/track/2758844701","duration":172,
           "artist":{"id":1,"name":"Myles Smith"},
           "album":{"id":2,"title":"A Minute of Your Time",
                    "cover_big":"https://cdn-images.dzcdn.net/cover/500x500-000000-80-0-0.jpg"}},
          {"id":2758844702,"title":"Broken",
           "link":"https://www.deezer.com/track/2758844702","duration":200,
           "artist":{"name":"Someone"},
           "album":{"title":"LP","cover_big":"https://cdn-images.dzcdn.net/cover2.jpg"}},
          {"title":"No id, dropped"}
        ],"total":3}
    """.trimIndent()

    private fun request(genre: ChartGenre) =
        ChartRequest(ChartSource.DEEZER, southAfrica, genre)

    private inner class FakeDeezer {
        var genreCalls = 0
        val chartCalls = mutableListOf<String>()

        val fetch: suspend (String) -> String = { url ->
            when {
                url == DEEZER_GENRE_URL -> {
                    genreCalls++
                    genresBody
                }
                url.contains("/chart/") -> {
                    chartCalls += url
                    tracksBody
                }
                else -> throw AssertionError("unexpected request $url")
            }
        }
    }

    @Test
    fun chartUrlCarriesGenreAndLimit() {
        assertEquals(
            "https://api.deezer.com/chart/116/tracks?limit=50",
            deezerChartUrl(116, 50)
        )
    }

    @Test
    fun genresKeepTheirFeedOrderAndIds() {
        val genres = DeezerChartJson.parseGenres(genresBody)
        assertEquals(
            listOf("All", "Pop", "Rap/Hip Hop", "Dance", "R&B", "Christian", "African Music"),
            genres.keys.take(7)
        )
        assertEquals(116, genres["Rap/Hip Hop"])
        assertEquals(DEEZER_ALL_GENRE, genres["All"])
    }

    @Test
    fun tracksMapArtistAlbumArtworkAndDuration() {
        val rows = DeezerChartJson.parseTracks(tracksBody, 50)
        assertEquals(listOf("deezer:2758844701", "deezer:2758844702"), rows.map { it.id })

        val first = rows.first()
        assertEquals("Stargazing", first.title)
        assertEquals("Myles Smith", first.artist)
        assertEquals("A Minute of Your Time", first.album)
        assertEquals(
            "https://cdn-images.dzcdn.net/cover/500x500-000000-80-0-0.jpg",
            first.artwork
        )
        assertEquals(172_000L, first.durationMs)
        assertEquals("https://www.deezer.com/track/2758844701", first.openUrl)
        assertEquals(ChartSource.DEEZER, first.source)
    }

    @Test
    fun tracksRespectTheLimitAndDropRowlessEntries() {
        assertEquals(1, DeezerChartJson.parseTracks(tracksBody, 1).size)
        val all = DeezerChartJson.parseTracks(tracksBody, 50)
        assertTrue(all.none { it.title == "No id, dropped" })
    }

    @Test(expected = IOException::class)
    fun errorPayloadIsAnError() {
        DeezerChartJson.parseTracks(
            """{"error":{"type":"DataException","message":"Invalid genre"}}""",
            50
        )
    }

    @Test
    fun providerResolvesTheCatalogueNameToDeezerId() = runBlocking {
        val fake = FakeDeezer()
        val provider = DeezerChartsProvider(fetch = fake.fetch)

        val result = provider.chart(request(ChartGenres.find("hiphop")!!))
        assertEquals(listOf("https://api.deezer.com/chart/116/tracks?limit=50"), fake.chartCalls)
        assertEquals(2, result.rows.size)
        assertEquals(ChartSource.DEEZER.caveat, result.note)
        assertEquals(1, fake.genreCalls)

        provider.chart(request(ChartGenres.find("pop")!!))
        assertEquals(1, fake.genreCalls)
        assertEquals("https://api.deezer.com/chart/132/tracks?limit=50", fake.chartCalls.last())
    }

    @Test
    fun allGenreSkipsTheGenreCatalogue() = runBlocking {
        val fake = FakeDeezer()
        val provider = DeezerChartsProvider(fetch = fake.fetch)

        provider.chart(request(ChartGenres.any))
        assertEquals(0, fake.genreCalls)
        assertEquals(listOf("https://api.deezer.com/chart/0/tracks?limit=50"), fake.chartCalls)
    }

    @Test
    fun unpublishedGenreIsNamedInsteadOfShownEmpty() = runBlocking {
        val fake = FakeDeezer()
        val provider = DeezerChartsProvider(fetch = fake.fetch)

        val result = provider.chart(request(ChartGenres.find("kpop")!!))
        assertTrue(result.rows.isEmpty())
        assertTrue(result.note!!.contains("K-Pop"))
        assertTrue(fake.chartCalls.isEmpty())
    }

    @Test
    fun repeatedSelectionIsServedFromTheCache() = runBlocking {
        val fake = FakeDeezer()
        val provider = DeezerChartsProvider(fetch = fake.fetch)
        val ask = request(ChartGenres.find("rnb")!!)

        provider.chart(ask)
        provider.chart(ask)
        assertEquals(1, fake.chartCalls.size)
        assertEquals("https://api.deezer.com/chart/165/tracks?limit=50", fake.chartCalls.single())
    }
}
