package com.streamline.cinema.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SkipSegmentsTest {

    private val episodeMs = 1_440_000L
    private val longEpisodeMs = 1_600_000L

    @Test
    fun parsesIntroAndOpenEndedCredits() {
        val segments = SkipSegments.parse(
            """{"tmdb_id":1429,"type":"tv","season":1,"episode":1,
               "intro":[{"start_ms":123435,"end_ms":215464}],
               "credits":[{"start_ms":1435007,"end_ms":null}]}"""
        )
        assertNotNull(segments)
        assertEquals(123_435L, segments!!.intro?.startMs)
        assertEquals(215_464L, segments.intro?.endMs)
        assertEquals(1_435_007L, segments.credits?.startMs)
        assertNull(segments.credits?.endMs)
    }

    @Test
    fun recapWinsWhenSegmentsOverlap() {
        val segments = SkipSegments(
            recap = SkipSegment(120_000L, 150_000L),
            intro = SkipSegment(140_000L, 170_000L)
        )
        val offer = segments.offerAt(145_000L, episodeMs)
        assertEquals(SkipKind.RECAP, offer?.kind)
        assertEquals(150_000L, offer?.targetMs)
        assertEquals(SkipKind.INTRO, segments.offerAt(155_000L, episodeMs)?.kind)
    }

    @Test
    fun introOfferTargetsItsEnd() {
        val segments = SkipSegments(intro = SkipSegment(123_435L, 215_464L))
        assertEquals(SkipOffer(SkipKind.INTRO, 215_464L), segments.offerAt(130_000L, episodeMs))
    }

    @Test
    fun skippingCreditsJumpsToTheOpenEnd() {
        val segments = SkipSegments(credits = SkipSegment(1_435_007L, null))
        val offer = segments.offerAt(1_436_000L, longEpisodeMs)
        assertEquals(SkipKind.CREDITS, offer?.kind)
        assertEquals(longEpisodeMs, offer?.targetMs)
    }

    @Test
    fun zeroLengthAndReversedEntriesAreRejected() {
        val segments = SkipSegments.parse(
            """{"intro":[{"start_ms":100000,"end_ms":100000},
                         {"start_ms":900000,"end_ms":800000}],
                "recap":[{"start_ms":40000,"end_ms":42000}],
                "credits":[{"start_ms":0,"end_ms":0}]}"""
        )
        // Only the 2 s recap remains at parse time and it is too short to use.
        assertNull(segments)
    }

    @Test
    fun junkPrefixInCreditsDoesNotDiscardTheRealEntry() {
        val segments = SkipSegments.parse(
            """{"credits":[{"start_ms":0,"end_ms":0},{"start_ms":1420000,"end_ms":null}]}"""
        )
        assertEquals(1_420_000L, segments?.credits?.startMs)
        // 20 s of tail is not a credits marker worth an early hand-off.
        assertNull(segments?.creditsStart(episodeMs))
        assertNull(segments?.offerAt(1_425_000L, episodeMs))
    }

    @Test
    fun creditsStartOnlyCountsWithEnoughTail() {
        val segments = SkipSegments(credits = SkipSegment(1_435_007L, null))
        assertEquals(1_435_007L, segments.creditsStart(longEpisodeMs))
        assertNull(segments.creditsStart(1_450_000L))
        assertNull(SkipSegments(credits = SkipSegment(0L, null)).creditsStart(episodeMs))
    }

    @Test
    fun segmentsBeyondTheKnownDurationAreDropped() {
        val segments = SkipSegments(
            intro = SkipSegment(1_500_000L, 1_560_000L),
            credits = SkipSegment(1_600_000L, null)
        )
        assertNull(segments.offerAt(1_500_000L, episodeMs))
        assertNull(segments.creditsStart(episodeMs))
    }

    @Test
    fun previewStandsInForMissingCredits() {
        val segments = SkipSegments.parse(
            """{"preview":[{"start_ms":1432000,"end_ms":null}]}"""
        )
        assertEquals(1_432_000L, segments?.credits?.startMs)
        assertEquals(1_432_000L, segments?.creditsStart(longEpisodeMs))
    }

    @Test
    fun emptyResponsesParseToNull() {
        assertNull(SkipSegments.parse(""))
        assertNull(SkipSegments.parse("{}"))
        assertNull(SkipSegments.parse("""{"tmdb_id":1396,"type":"tv"}"""))
        assertNull(SkipSegments.parse("not json"))
    }

    @Test
    fun canonicalFormRoundTrips() {
        val original = SkipSegments(
            recap = SkipSegment(20_000L, 60_000L),
            intro = SkipSegment(90_000L, 150_000L),
            credits = SkipSegment(1_300_000L, null)
        )
        val reparsed = SkipSegments.parse(original.toJson().toString())
        assertEquals(original, reparsed)
    }

    @Test
    fun offerDisappearsAtTheEndOfTheWindow() {
        val segments = SkipSegments(intro = SkipSegment(90_000L, 150_000L))
        assertNull(segments.offerAt(88_000L, episodeMs))
        assertNotNull(segments.offerAt(91_000L, episodeMs))
        assertNull(segments.offerAt(148_500L, episodeMs))
    }
}
