package com.streamline.cinema.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SeamlessFrameRateTest {

    @Test
    fun passesVideoRatesThroughAtNormalSpeed() {
        assertEquals(23.976f, seamlessRequestRate(23.976f, 1f), 0.001f)
        assertEquals(50f, seamlessRequestRate(50f, 1f), 0.001f)
        assertEquals(59.94f, seamlessRequestRate(59.94f, 1f), 0.001f)
    }

    @Test
    fun followsPlaybackSpeed() {
        assertEquals(47.952f, seamlessRequestRate(23.976f, 2f), 0.001f)
        assertEquals(29.97f, seamlessRequestRate(59.94f, 0.5f), 0.001f)
    }

    @Test
    fun clampsOutOfRangePlaybackSpeed() {
        assertEquals(95.904f, seamlessRequestRate(23.976f, 10f), 0.001f)
        assertEquals(5.994f, seamlessRequestRate(23.976f, 0.1f), 0.001f)
    }

    @Test
    fun unknownRatesReleaseTheRequest() {
        assertEquals(0f, seamlessRequestRate(0f, 1f), 0f)
        assertEquals(0f, seamlessRequestRate(-24f, 1f), 0f)
    }
}
