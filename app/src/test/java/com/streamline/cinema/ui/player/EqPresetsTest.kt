package com.streamline.cinema.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqPresetsTest {

    @Test
    fun neutralLevelIsUnityGain() {
        assertEquals(0f, winampLevelToDb(EQ_NEUTRAL_LEVEL), 0.001f)
    }

    @Test
    fun levelsSpanFullDbRange() {
        assertEquals(EQ_MAX_DB, winampLevelToDb(EQ_MAX_LEVEL), 0.001f)
        assertEquals(-EQ_MAX_DB, winampLevelToDb(0), 0.001f)
    }

    @Test
    fun flatPresetIsNeutralAtEveryFrequency() {
        val frequencies = listOf(20f, 70f, 250f, 1000f, 6000f, 16000f, 20000f)
        frequencies.forEach { hz ->
            assertEquals(EQ_NEUTRAL_LEVEL, WinampEqPreset.Flat.levelAt(hz))
        }
    }

    @Test
    fun curvesAreSampledAtTheirDefinedFrequencies() {
        assertEquals(50, WinampEqPreset.Cinema.levelAt(70f))
        assertEquals(30, WinampEqPreset.Cinema.levelAt(1000f))
        assertEquals(16, WinampEqPreset.Classical.levelAt(16000f))
    }

    @Test
    fun levelsClampOutsideTheDefinedRange() {
        assertEquals(50, WinampEqPreset.Cinema.levelAt(10f))
        assertEquals(38, WinampEqPreset.Cinema.levelAt(30000f))
    }

    @Test
    fun interpolationStaysInsideTheNeighbouringLevels() {
        // Between 70 Hz (50) and 180 Hz (45), on a log axis.
        val midpoint = WinampEqPreset.Cinema.levelAt(kotlin.math.sqrt(70f * 180f))
        assertTrue("unexpected midpoint $midpoint", midpoint in 47..48)
    }

    @Test
    fun allPresetsExposeTenOrderedBands() {
        WinampEqPreset.AllPresets.forEach { preset ->
            assertEquals(10, preset.bandsHz.size)
            val frequencies = preset.bandsHz.map { it.first }
            assertEquals(frequencies.sorted(), frequencies)
        }
    }
}
