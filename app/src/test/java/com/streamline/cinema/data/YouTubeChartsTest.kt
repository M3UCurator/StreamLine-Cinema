package com.streamline.cinema.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class YouTubeChartsTest {

    private val southAfrica = ChartCountries.find("ZA")!!

    private fun provider(
        key: String = "test-key",
        fetch: suspend (String) -> String = { chartBody },
        probe: suspend (String) -> YouTubeProbe = { YouTubeProbe(200) },
        cache: ChartCache = ChartCache()
    ) = YouTubeChartsProvider(keyProvider = { key }, fetch = fetch, probe = probe, cache = cache)

    private val chartBody = """
        {"kind":"youtube#videoListResponse","items":[
          {"id":"dQw4w9WgXcQ",
           "snippet":{"title":"Never Gonna Give You Up","channelTitle":"Rick Astley",
                      "thumbnails":{"default":{"url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg"},
                                    "high":{"url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"}}},
           "contentDetails":{"duration":"PT3M33S"}},
          {"id":"aaaaaaaaaaa",
           "snippet":{"title":"Live Set","channelTitle":"DJ Somebody",
                      "thumbnails":{"default":{"url":"https://i.ytimg.com/vi/aaaaaaaaaaa/default.jpg"}}},
           "contentDetails":{"duration":"P0D"}}
        ]}
    """.trimIndent()

    private val searchBody = """
        {"kind":"youtube#searchListResponse","items":[
          {"id":{"kind":"youtube#video","videoId":"dQw4w9WgXcQ"},
           "snippet":{"title":"Never Gonna Give You Up (Official Video)","channelTitle":"Rick Astley",
                      "thumbnails":{"medium":{"url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg"}}}},
          {"id":{"kind":"youtube#channel","channelId":"UCuAXFkgsw1L7xaCfnd5JJOw"},
           "snippet":{"title":"Rick Astley - Topic","channelTitle":"Rick Astley"}}
        ]}
    """.trimIndent()

    @Test
    fun chartUrlCarriesCountryCategoryAndKey() {
        val url = youtubeChartUrl(southAfrica, 50, "k3y")
        assertTrue(url.startsWith("https://www.googleapis.com/youtube/v3/videos?"))
        assertTrue(url.contains("chart=mostPopular"))
        assertTrue(url.contains("videoCategoryId=10"))
        assertTrue(url.contains("regionCode=ZA"))
        assertTrue(url.contains("maxResults=50"))
        assertTrue(url.contains("key=k3y"))
        assertTrue(url.contains("part=snippet,contentDetails"))
    }

    @Test
    fun searchUrlEncodesTheQuery() {
        val url = youtubeSearchUrl("Tyla Water", southAfrica, 10, "k3y")
        assertTrue(url.contains("q=Tyla+Water"))
        assertTrue(url.contains("type=video"))
        assertTrue(url.contains("regionCode=ZA"))
    }

    @Test
    fun parseVideosMapsRowsAndDurations() = runBlocking {
        val result = provider().chart(ChartRequest(ChartSource.YOUTUBE, southAfrica))

        assertEquals(2, result.rows.size)
        val first = result.rows.first()
        assertEquals("youtube:dQw4w9WgXcQ", first.id)
        assertEquals("Never Gonna Give You Up", first.title)
        assertEquals("Rick Astley", first.artist)
        assertEquals("dQw4w9WgXcQ", first.videoId)
        assertEquals(213_000L, first.durationMs)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", first.artwork)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", first.openUrl)
        assertNull(first.genreLabel)
        assertEquals(ChartSource.YOUTUBE.caveat, result.note)

        val live = result.rows[1]
        assertEquals(0L, live.durationMs)
        assertEquals("https://i.ytimg.com/vi/aaaaaaaaaaa/default.jpg", live.artwork)
    }

    @Test
    fun parseVideosDropsIdsThatCannotBeDeepLinked() {
        val body = """{"items":[
            {"id":"short","snippet":{"title":"Bad id"}},
            {"id":"dQw4w9WgXcQ","snippet":{"title":"Good id"}}
        ]}"""
        assertEquals(listOf("dQw4w9WgXcQ"), YouTubeJson.parseVideos(body, 50).map { it.videoId })
    }

    @Test
    fun parseSearchReadsTheNestedVideoId() = runBlocking {
        val rows = provider(fetch = { searchBody }).search("Rick Astley", southAfrica)
        assertEquals(1, rows.size)
        assertEquals("dQw4w9WgXcQ", rows.first().videoId)
        assertEquals("Rick Astley", rows.first().artist)
        assertTrue(rows.first().title.endsWith("(Official Video)"))
    }

    @Test
    fun durationsFollowIso8601() {
        val hour = YouTubeJson.durationMs(org.json.JSONObject("""{"duration":"PT1H2M3S"}"""))
        assertEquals(3_723_000L, hour)
        assertEquals(
            45_000L,
            YouTubeJson.durationMs(org.json.JSONObject("""{"duration":"PT45S"}"""))
        )
        assertEquals(0L, YouTubeJson.durationMs(org.json.JSONObject("""{"duration":"P0D"}""")))
        assertEquals(0L, YouTubeJson.durationMs(null))
    }

    @Test
    fun aBlankKeyIsNamedInTheError() = runBlocking {
        try {
            provider(key = "  ").chart(ChartRequest(ChartSource.YOUTUBE, southAfrica))
            fail("expected a missing-key error")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("Settings → Music"))
        }
    }

    @Test
    fun apiErrorsSurfaceTheGoogleMessage() = runBlocking {
        val body = """{"error":{"message":"The request cannot be completed because you have exceeded your quota.","errors":[{"reason":"quotaExceeded"}]}}"""
        assertEquals("quotaExceeded", YouTubeJson.errorReason(body))
        try {
            provider(fetch = { body }).chart(ChartRequest(ChartSource.YOUTUBE, southAfrica))
            fail("expected a quota error")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("exceeded your quota"))
        }
    }

    @Test
    fun searchStopsAtTheSessionBudget() = runBlocking {
        var calls = 0
        val subject = provider(fetch = { calls++; searchBody })
        repeat(MAX_SEARCH_CALLS_PER_SESSION) { index ->
            subject.search("query $index", southAfrica)
        }
        assertEquals(MAX_SEARCH_CALLS_PER_SESSION, calls)
        assertEquals(0, subject.searchBudgetLeft())
        val refused = runCatching { subject.search("one more", southAfrica) }.exceptionOrNull()
        assertTrue(refused is IOException && refused.message!!.contains("budget used up"))
        assertEquals(MAX_SEARCH_CALLS_PER_SESSION, calls)
    }

    @Test
    fun aRepeatedSearchIsFreeAndCached() = runBlocking {
        var calls = 0
        val subject = provider(fetch = { calls++; searchBody })
        subject.search("Tyla Water", southAfrica)
        subject.search("tyla water ", southAfrica)
        assertEquals(1, calls)
        assertEquals(MAX_SEARCH_CALLS_PER_SESSION - 1, subject.searchBudgetLeft())
    }

    @Test
    fun validateKeyReadsTheRefusalReason() = runBlocking {
        suspend fun check(code: Int, body: String = ""): KeyCheck =
            provider(probe = { YouTubeProbe(code, body) }).validateKey()

        assertEquals(KeyCheck.Valid, check(200))
        assertEquals(
            KeyCheck.Invalid,
            check(400, """{"error":{"errors":[{"reason":"keyInvalid"}]}}""")
        )
        val spent = check(403, """{"error":{"errors":[{"reason":"quotaExceeded"}]}}""")
        assertTrue(spent is KeyCheck.Failed && spent.message.contains("quota"))
        val offline = check(0)
        assertTrue(offline is KeyCheck.Failed)
        assertEquals(KeyCheck.Missing, provider(key = "").validateKey())
    }

    @Test
    fun handoffPrefersTheOfficialAppAndFallsBackToURLs() {
        val track = ChartTrack(
            source = ChartSource.YOUTUBE,
            id = "youtube:dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            videoId = "dQw4w9WgXcQ"
        )
        assertEquals("vnd.youtube:dQw4w9WgXcQ", ChartHandoff.openTarget(track))
        assertEquals("Rick Astley Never Gonna Give You Up", ChartHandoff.queryFor(track))
        assertEquals(
            "https://www.youtube.com/results?search_query=Rick+Astley+Never+Gonna+Give+You+Up",
            ChartHandoff.searchUrl(ChartHandoff.queryFor(track))
        )

        val noId = track.copy(videoId = null, openUrl = null)
        assertNull(ChartHandoff.openTarget(noId))

        val badIdWithUrl = track.copy(videoId = "nope", openUrl = "https://www.youtube.com/watch?v=x")
        assertEquals("https://www.youtube.com/watch?v=x", ChartHandoff.openTarget(badIdWithUrl))
    }
}
