package com.streamline.cinema.ui.splash

/**
 * Timing for the start screen: five seconds of intro, then one second of hold
 * on the settled lockup. Kept as pure arithmetic so the animation can be tested
 * without a frame clock and so every value on screen is a function of elapsed
 * time — no animation spec can drift from the documented beats.
 */
object SplashTimeline {
    const val INTRO_MS = 5_000L
    const val HOLD_MS = 1_000L
    const val TOTAL_MS = INTRO_MS + HOLD_MS

    /** A single sampled frame. All alphas are 0..1, rises are 0 (settled)..1 (start). */
    data class Frame(
        val backdrop: Float,
        val tileAlpha: Float,
        val tileScale: Float,
        val glyph: Float,
        val wordmarkAlpha: Float,
        val wordmarkRise: Float,
        val subtitleAlpha: Float,
        val subtitleRise: Float,
        val sweep: Float,
        val rule: Float
    )

    // Beat sheet, all inside the intro. The last motion settles at 4.9 s so the
    // lockup is completely still for the intro's final beat and the whole hold.
    private const val BACKDROP_START = 0L
    private const val BACKDROP_END = 700L
    private const val TILE_START = 150L
    private const val TILE_FADE_END = 900L
    private const val TILE_SCALE_END = 1_000L
    private const val GLYPH_START = 650L
    private const val GLYPH_END = 1_350L
    private const val WORDMARK_START = 1_100L
    private const val WORDMARK_END = 2_300L
    private const val SUBTITLE_START = 1_700L
    private const val SUBTITLE_END = 2_600L
    private const val SWEEP_START = 2_200L
    private const val SWEEP_END = 3_300L
    private const val RULE_START = 3_000L
    private const val RULE_END = 4_900L

    fun frame(elapsedMs: Long): Frame {
        val t = elapsedMs.coerceIn(0L, TOTAL_MS)
        return Frame(
            backdrop = smooth(BACKDROP_START, BACKDROP_END, t),
            tileAlpha = smooth(TILE_START, TILE_FADE_END, t),
            tileScale = 0.88f + 0.12f * easeOutCubic(progress(TILE_START, TILE_SCALE_END, t)),
            glyph = smooth(GLYPH_START, GLYPH_END, t),
            wordmarkAlpha = smooth(WORDMARK_START, WORDMARK_END, t),
            wordmarkRise = 1f - easeOutCubic(progress(WORDMARK_START, WORDMARK_END, t)),
            subtitleAlpha = smooth(SUBTITLE_START, SUBTITLE_END, t),
            subtitleRise = 1f - easeOutCubic(progress(SUBTITLE_START, SUBTITLE_END, t)),
            sweep = sweep(t),
            rule = easeOutCubic(progress(RULE_START, RULE_END, t))
        )
    }

    fun isFinished(elapsedMs: Long): Boolean = elapsedMs >= TOTAL_MS

    /** The tile glint crosses once; -1 means "not crossing". */
    private fun sweep(t: Long): Float = when {
        t < SWEEP_START || t > SWEEP_END -> -1f
        else -> progress(SWEEP_START, SWEEP_END, t)
    }

    private fun progress(start: Long, end: Long, t: Long): Float =
        ((t - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)

    private fun smooth(start: Long, end: Long, t: Long): Float {
        val p = progress(start, end, t)
        return p * p * (3f - 2f * p)
    }

    private fun easeOutCubic(p: Float): Float {
        val inv = 1f - p
        return 1f - inv * inv * inv
    }
}
