package com.streamline.cinema.ui.player

import kotlin.math.log2
import kotlin.math.roundToInt

/** Winamp-style 0–63 band levels; [EQ_NEUTRAL_LEVEL] is unity gain. */
internal const val EQ_NEUTRAL_LEVEL = 33
internal const val EQ_MAX_LEVEL = 63

/** Full deflection of one band, in dB. */
internal const val EQ_MAX_DB = 12f

/**
 * A fixed ten-band tone curve. Hardware EQs expose their own band centres
 * (commonly five), so a preset is sampled at those centres instead of assuming
 * ten bands.
 */
internal data class WinampEqPreset(
    val name: String,
    val bandsHz: List<Pair<Float, Int>>,
    val preamp: Int = EQ_NEUTRAL_LEVEL
) {
    /** Level at [hz], interpolated on a log-frequency axis and clamped at the ends. */
    fun levelAt(hz: Float): Int {
        if (bandsHz.isEmpty()) return EQ_NEUTRAL_LEVEL
        val first = bandsHz.first()
        val last = bandsHz.last()
        if (hz <= first.first) return first.second
        if (hz >= last.first) return last.second
        for (index in 0 until bandsHz.lastIndex) {
            val (lowHz, lowLevel) = bandsHz[index]
            val (highHz, highLevel) = bandsHz[index + 1]
            if (hz <= highHz) {
                val span = log2(highHz / lowHz)
                val position = if (span == 0f) 0f else log2(hz / lowHz) / span
                return (lowLevel + (highLevel - lowLevel) * position)
                    .roundToInt()
                    .coerceIn(0, EQ_MAX_LEVEL)
            }
        }
        return last.second
    }

    companion object {
        val Cinema = WinampEqPreset(
            name = "Cinema",
            bandsHz = listOf(
                70f to 50, 180f to 45, 320f to 35, 600f to 28, 1000f to 30,
                3000f to 38, 6000f to 45, 12000f to 48, 14000f to 42, 16000f to 38
            ),
            preamp = 31
        )
        val Flat = WinampEqPreset(
            name = "Flat",
            bandsHz = listOf(
                70f to 33, 180f to 33, 320f to 33, 600f to 33, 1000f to 33,
                3000f to 33, 6000f to 33, 12000f to 33, 14000f to 33, 16000f to 33
            )
        )
        val Vocal = WinampEqPreset(
            name = "Vocal",
            bandsHz = listOf(
                70f to 20, 180f to 25, 320f to 33, 600f to 44, 1000f to 50,
                3000f to 48, 6000f to 40, 12000f to 33, 14000f to 28, 16000f to 25
            )
        )
        val Stereo = WinampEqPreset(
            name = "Stereo",
            bandsHz = listOf(
                70f to 36, 180f to 36, 320f to 33, 600f to 33, 1000f to 35,
                3000f to 40, 6000f to 46, 12000f to 48, 14000f to 45, 16000f to 42
            )
        )
        val Classical = WinampEqPreset(
            name = "Classical",
            bandsHz = listOf(
                70f to 33, 180f to 33, 320f to 33, 600f to 33, 1000f to 33,
                3000f to 33, 6000f to 20, 12000f to 20, 14000f to 20, 16000f to 16
            )
        )
        val Live = WinampEqPreset(
            name = "Live",
            bandsHz = listOf(
                70f to 24, 180f to 33, 320f to 39, 600f to 41, 1000f to 42,
                3000f to 42, 6000f to 39, 12000f to 37, 14000f to 37, 16000f to 36
            )
        )

        val AllPresets: List<WinampEqPreset> =
            listOf(Cinema, Flat, Vocal, Stereo, Classical, Live)
    }
}

/** Maps a 0–63 band level to dB, with unity at [EQ_NEUTRAL_LEVEL]. */
internal fun winampLevelToDb(level: Int, maxAbsDb: Float = EQ_MAX_DB): Float {
    val normalized = when {
        level >= EQ_NEUTRAL_LEVEL ->
            (level - EQ_NEUTRAL_LEVEL).toFloat() / (EQ_MAX_LEVEL - EQ_NEUTRAL_LEVEL)
        else -> (level - EQ_NEUTRAL_LEVEL).toFloat() / EQ_NEUTRAL_LEVEL
    }
    return normalized.coerceIn(-1f, 1f) * maxAbsDb
}
