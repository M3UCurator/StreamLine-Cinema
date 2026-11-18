package com.streamline.cinema.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicHistoryTest {

    private fun track(id: String, title: String = "Track $id"): MusicTrack = MusicTrack(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        durationMs = 210_000L,
        artwork = "https://img.example/$id.jpg",
        addonName = "Musicbox",
        addonUrl = "https://addon.example",
        type = "music"
    )

    @Test
    fun trackRoundTripsThroughJson() {
        val original = track("yt:dQw4w9WgXcQ", "Never Gonna Give You Up")
        assertEquals(original, MusicTrack.fromJson(original.toJson()))
    }

    @Test
    fun trackJsonKeepsOptionalFieldsOptional() {
        val sparse = MusicTrack(id = "id", title = "Title")
        val parsed = MusicTrack.fromJson(sparse.toJson())
        assertNull(parsed?.artwork)
        assertEquals(0L, parsed?.durationMs)
    }

    @Test
    fun junkTrackEntriesAreDropped() {
        assertNull(MusicTrack.fromJson(JSONObject("""{"id":"x"}""")))
        assertNull(MusicTrack.fromJson(JSONObject("""{"title":"x"}""")))
        assertNull(MusicTrack.fromJson(JSONObject("""{"id":"","title":""}""")))
    }

    @Test
    fun shortQueuesKeepTheirIndex() {
        val queue = (1..10).map { track("$it") }
        val (trimmed, index) = trimMusicQueue(queue, 7)
        assertEquals(queue, trimmed)
        assertEquals(7, index)
    }

    @Test
    fun longQueuesKeepThePlayingTrackInTheWindow() {
        val queue = (0 until 500).map { track("$it") }
        val (trimmed, index) = trimMusicQueue(queue, 400)
        assertEquals(200, trimmed.size)
        assertEquals("400", trimmed[index].id)
        // The window is centred, not pinned to the tail.
        assertEquals("300", trimmed.first().id)
    }

    @Test
    fun queueEdgesStayInsideTheList() {
        val queue = (0 until 300).map { track("$it") }
        val (head, headIndex) = trimMusicQueue(queue, 0)
        assertEquals("0", head[headIndex].id)
        assertEquals("0", head.first().id)

        val (tail, tailIndex) = trimMusicQueue(queue, 299)
        assertEquals("299", tail[tailIndex].id)
        assertEquals("299", tail.last().id)
    }

    @Test
    fun outOfRangeIndexIsClamped() {
        val queue = (1..5).map { track("$it") }
        assertEquals(4, trimMusicQueue(queue, 99).second)
        assertEquals(0, trimMusicQueue(queue, -3).second)
        assertEquals(0, trimMusicQueue(emptyList(), 4).second)
    }

    @Test
    fun resumeNeedsEnoughPlayedAndEnoughLeft() {
        val queue = listOf(track("a"))
        assertFalse(MusicResume(queue, 0, 3_000L).isResumable)
        assertTrue(MusicResume(queue, 0, 60_000L).isResumable)
        assertFalse(MusicResume(queue, 0, 208_000L).isResumable)
        assertFalse(MusicResume(emptyList(), 0, 60_000L).isResumable)
        assertFalse(MusicResume(queue, 3, 60_000L).isResumable)
    }

    @Test
    fun unknownDurationStillResumes() {
        val queue = listOf(track("a").copy(durationMs = 0L))
        assertTrue(MusicResume(queue, 0, 60_000L).isResumable)
        assertFalse(MusicResume(queue, 0, 0L).isResumable)
    }
}
