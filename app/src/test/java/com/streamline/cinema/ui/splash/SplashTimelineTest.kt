package com.streamline.cinema.ui.splash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The start screen is a fixed 5 s intro plus a 1 s hold, and once the hold
 * begins nothing on screen may move. These checks pin the arithmetic down so a
 * retuned beat cannot silently shorten the intro or turn the hold into motion.
 */
class SplashTimelineTest {

    private val delta = 0.0001f

    @Test
    fun timelineIsFiveSecondsIntroPlusOneSecondHold() {
        assertEquals(5_000L, SplashTimeline.INTRO_MS)
        assertEquals(1_000L, SplashTimeline.HOLD_MS)
        assertEquals(6_000L, SplashTimeline.TOTAL_MS)
        assertFalse(SplashTimeline.isFinished(5_999L))
        assertTrue(SplashTimeline.isFinished(6_000L))
    }

    @Test
    fun startsBlackAndSettlesBeforeTheHold() {
        val start = SplashTimeline.frame(0)
        assertEquals(0f, start.backdrop, delta)
        assertEquals(0f, start.tileAlpha, delta)
        assertEquals(0f, start.glyph, delta)
        assertEquals(0f, start.wordmarkAlpha, delta)
        assertEquals(-1f, start.sweep, delta)
        assertEquals(0f, start.rule, delta)

        val settled = SplashTimeline.frame(SplashTimeline.INTRO_MS)
        assertEquals(1f, settled.backdrop, delta)
        assertEquals(1f, settled.tileAlpha, delta)
        assertEquals(1f, settled.tileScale, delta)
        assertEquals(1f, settled.glyph, delta)
        assertEquals(1f, settled.wordmarkAlpha, delta)
        assertEquals(0f, settled.wordmarkRise, delta)
        assertEquals(1f, settled.subtitleAlpha, delta)
        assertEquals(0f, settled.subtitleRise, delta)
        assertEquals(-1f, settled.sweep, delta)
        assertEquals(1f, settled.rule, delta)
    }

    @Test
    fun holdFramesAreIdentical() {
        val holdStart = SplashTimeline.frame(SplashTimeline.INTRO_MS)
        listOf(5_250L, 5_500L, 5_999L, 6_000L).forEach { t ->
            assertEquals(holdStart, SplashTimeline.frame(t))
        }
    }

    @Test
    fun elapsedIsClampedOutsideTheTimeline() {
        assertEquals(SplashTimeline.frame(0), SplashTimeline.frame(-250L))
        assertEquals(SplashTimeline.frame(SplashTimeline.TOTAL_MS), SplashTimeline.frame(60_000L))
    }

    @Test
    fun sweepCrossesTheTileExactlyOnce() {
        assertEquals(-1f, SplashTimeline.frame(2_199L).sweep, delta)
        assertEquals(0f, SplashTimeline.frame(2_200L).sweep, delta)
        assertEquals(0.5f, SplashTimeline.frame(2_750L).sweep, delta)
        assertEquals(1f, SplashTimeline.frame(3_300L).sweep, delta)
        assertEquals(-1f, SplashTimeline.frame(3_301L).sweep, delta)
    }

    @Test
    fun wordmarkFadesInMonotonically() {
        var previous = -1f
        var t = 0L
        while (t <= SplashTimeline.INTRO_MS) {
            val alpha = SplashTimeline.frame(t).wordmarkAlpha
            assertTrue("alpha regressed at $t", alpha >= previous)
            previous = alpha
            t += 50L
        }
        assertEquals(1f, previous, delta)
    }
}
