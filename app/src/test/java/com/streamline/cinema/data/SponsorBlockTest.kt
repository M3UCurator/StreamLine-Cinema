package com.streamline.cinema.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorBlockTest {

    private val videoId = "dQw4w9WgXcQ"

    @Test
    fun youtubeIdsAreFoundInEveryMetaShape() {
        assertEquals(videoId, SponsorBlock.videoIdOf("yt:$videoId"))
        assertEquals(videoId, SponsorBlock.videoIdOf("youtube:$videoId"))
        assertEquals(videoId, SponsorBlock.videoIdOf(videoId))
        assertEquals(videoId, SponsorBlock.videoIdOf("  yt:$videoId  "))
    }

    @Test
    fun nonYoutubeIdsAreIgnored() {
        assertNull(SponsorBlock.videoIdOf("tt1234567"))
        assertNull(SponsorBlock.videoIdOf("yt:abc"))
        assertNull(SponsorBlock.videoIdOf("yt:${videoId}extra"))
        assertNull(SponsorBlock.videoIdOf(""))
        assertNull(SponsorBlock.videoIdOf("yt:"))
    }

    @Test
    fun hashPrefixMatchesThePublishedApiExample() {
        assertEquals("5f6b", SponsorBlock.hashPrefix("dQw4w9WgXcQ"))
        assertEquals("2691", SponsorBlock.hashPrefix("9bZkp7q19f0"))
    }

    @Test
    fun onlySkippableOffTopicSegmentsForTheRequestedVideoSurvive() {
        val body = """
            [
              {
                "videoID": "dQw4w9WgXcQ",
                "segments": [
                  {"segment": [0.0, 4.5], "category": "intro", "actionType": "skip"},
                  {"segment": [33.2, 47.8], "category": "music_offtopic", "actionType": "skip"},
                  {"segment": [60.0, 62.0], "category": "music_offtopic", "actionType": "mute"},
                  {"segment": [70.0, 61.0], "category": "music_offtopic", "actionType": "skip"}
                ]
              },
              {
                "videoID": "9bZkp7q19f0",
                "segments": [
                  {"segment": [10.0, 20.0], "category": "music_offtopic", "actionType": "skip"}
                ]
              }
            ]
        """.trimIndent()

        val segments = SponsorBlock.parse(body, videoId)
        assertEquals(listOf(SponsorSegment(33_200L, 47_800L)), segments)
    }

    @Test
    fun actionTypeDefaultsToSkipAndSegmentsAreSorted() {
        val body = """
            [
              {"videoID": "$videoId",
               "segments": [
                 {"segment": [120.5, 130.0], "category": "music_offtopic"},
                 {"segment": [5.0, 9.25], "category": "MUSIC_OFFTOPIC", "actionType": "skip"}
               ]}
            ]
        """.trimIndent()

        val segments = SponsorBlock.parse(body, videoId)
        assertEquals(
            listOf(
                SponsorSegment(5_000L, 9_250L),
                SponsorSegment(120_500L, 130_000L)
            ),
            segments
        )
    }

    @Test
    fun malformedAndEmptyBodiesYieldNoSegments() {
        assertTrue(SponsorBlock.parse("not json", videoId).isEmpty())
        assertTrue(SponsorBlock.parse("[]", videoId).isEmpty())
        assertTrue(SponsorBlock.parse("""[{"videoID":"$videoId"}]""", videoId).isEmpty())
    }

    @Test
    fun segmentCoversItsStartAndStopsAtItsEnd() {
        val segment = SponsorSegment(10_000L, 20_000L)
        assertTrue(segment.contains(10_000L))
        assertTrue(segment.contains(19_999L))
        assertFalse(segment.contains(20_000L))
    }
}
