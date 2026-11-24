package com.streamline.cinema.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateMatchingTest {

    private fun option(id: Int, width: Int = 1920, height: Int = 1080, rate: Float) =
        DisplayModeOption(id, width, height, rate)

    @Test
    fun matchesFilmRatesInsideTheirOwnResolution() {
        val current = option(1, rate = 60f)
        val modes = listOf(
            current,
            option(2, rate = 50f),
            option(3, rate = 23.976f),
            option(4, rate = 24f)
        )
        assertEquals(3, chooseDisplayMode(23.976f, current, modes)?.modeId)
        assertEquals(4, chooseDisplayMode(24f, current, modes)?.modeId)
    }

    @Test
    fun matchesFiftyHertzMaterial() {
        val current = option(1, rate = 60f)
        val modes = listOf(current, option(2, rate = 50f), option(3, rate = 25f))
        assertEquals(2, chooseDisplayMode(50f, current, modes)?.modeId)
        assertEquals(3, chooseDisplayMode(25f, current, modes)?.modeId)
    }

    @Test
    fun sixtyHertzVariantsMatchTheSixtyHertzMode() {
        val current = option(1, rate = 50f)
        val modes = listOf(current, option(2, rate = 59.94f), option(3, rate = 60f))
        assertEquals(2, chooseDisplayMode(59.94f, current, modes)?.modeId)
        assertEquals(3, chooseDisplayMode(60f, current, modes)?.modeId)
    }

    @Test
    fun leavesTheDisplayAloneWhenNoModeIsClose() {
        val current = option(1, rate = 60f)
        val modes = listOf(current, option(2, rate = 50f))
        assertNull(chooseDisplayMode(23.976f, current, modes))
        assertNull(chooseDisplayMode(30f, current, modes))
    }

    @Test
    fun neverTradesResolutionForRefreshRate() {
        val current = option(1, width = 3840, height = 2160, rate = 60f)
        val modes = listOf(current, option(2, width = 1920, height = 1080, rate = 24f))
        assertNull(chooseDisplayMode(24f, current, modes))
    }

    @Test
    fun skipsTheModeAlreadyInUse() {
        val current = option(1, rate = 24f)
        val modes = listOf(current, option(2, rate = 60f))
        assertNull(chooseDisplayMode(24f, current, modes))
    }

    @Test
    fun ignoresUnknownFrameRates() {
        val current = option(1, rate = 60f)
        assertNull(chooseDisplayMode(0f, current, listOf(current, option(2, rate = 24f))))
        assertNull(chooseDisplayMode(-24f, current, listOf(current, option(2, rate = 24f))))
    }
}
