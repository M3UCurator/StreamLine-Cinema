package com.streamline.cinema.ui.player

import com.streamline.cinema.data.BufferTier
import java.util.Locale

/**
 * The five DefaultLoadControl numbers a playback start needs. Low-RAM devices
 * use a fixed window (min == max) and a byte budget instead of a long one.
 */
internal data class BufferWindow(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val backBufferMs: Int
)

// DefaultLoadControl's own start values, kept as literals so the mapping is
// testable without loading Media3 on the JVM.
private const val START_MS = 2_500
private const val REBUFFER_MS = 5_000

/**
 * BALANCED must stay byte-for-byte the pre-tier behaviour so existing installs
 * keep their tuned window; LEAN and GENEROUS only move the forward window and
 * the rewind retention around it.
 */
internal fun bufferWindow(
    tier: BufferTier,
    adaptive: Boolean,
    lowLatency: Boolean,
    lowRam: Boolean
): BufferWindow {
    if (lowRam) {
        val fixed = when {
            adaptive -> when (tier) {
                BufferTier.LEAN -> 5_000
                BufferTier.BALANCED -> if (lowLatency) 8_000 else 12_000
                BufferTier.GENEROUS -> 16_000
            }
            else -> when (tier) {
                BufferTier.LEAN -> 10_000
                BufferTier.BALANCED -> 20_000
                BufferTier.GENEROUS -> 30_000
            }
        }
        val back = when {
            adaptive -> when (tier) {
                BufferTier.LEAN -> 10_000
                BufferTier.BALANCED -> 20_000
                BufferTier.GENEROUS -> 30_000
            }
            else -> when (tier) {
                BufferTier.LEAN -> 20_000
                BufferTier.BALANCED -> 40_000
                BufferTier.GENEROUS -> 60_000
            }
        }
        return BufferWindow(fixed, fixed, START_MS, REBUFFER_MS, back)
    }
    if (adaptive) {
        return when (tier) {
            BufferTier.LEAN -> BufferWindow(2_000, 15_000, 1_000, 2_000, 30_000)
            BufferTier.BALANCED -> BufferWindow(
                if (lowLatency) 3_000 else 5_000,
                30_000,
                if (lowLatency) 1_200 else 2_000,
                if (lowLatency) 2_500 else 4_000,
                60_000
            )
            BufferTier.GENEROUS -> BufferWindow(8_000, 60_000, 3_000, 6_000, 90_000)
        }
    }
    return when (tier) {
        BufferTier.LEAN -> BufferWindow(10_000, 30_000, 2_000, 4_000, 60_000)
        BufferTier.BALANCED -> BufferWindow(15_000, 50_000, START_MS, REBUFFER_MS, 120_000)
        BufferTier.GENEROUS -> BufferWindow(20_000, 90_000, 3_000, 6_000, 180_000)
    }
}

/**
 * The native engines keep their own caches instead of DefaultLoadControl, so
 * the tier has to be mapped onto them as well: MPV holds `cache-secs`, libVLC
 * its network/live pre-roll. BALANCED keeps the values both engines shipped
 * with before the tier existed, so existing installs play exactly as before.
 */
internal data class NativeCache(
    val mpvCacheSeconds: Int,
    val vlcCachingMs: Int
)

internal fun nativeCache(tier: BufferTier): NativeCache = when (tier) {
    BufferTier.LEAN -> NativeCache(mpvCacheSeconds = 4, vlcCachingMs = 800)
    BufferTier.BALANCED -> NativeCache(mpvCacheSeconds = 10, vlcCachingMs = 1_500)
    BufferTier.GENEROUS -> NativeCache(mpvCacheSeconds = 20, vlcCachingMs = 4_000)
}

/** One-line summary for Settings, e.g. "10 s MPV · 1.5 s VLC". */
internal fun describeNativeCache(tier: BufferTier): String {
    val cache = nativeCache(tier)
    return "${cache.mpvCacheSeconds} s MPV · ${seconds(cache.vlcCachingMs)} s VLC"
}

/** One-line summary for Settings, e.g. "min 3 s · max 30 s · start 1.2 s". */
internal fun describeBuffer(
    tier: BufferTier,
    adaptive: Boolean,
    lowLatency: Boolean,
    lowRam: Boolean
): String {
    val window = bufferWindow(tier, adaptive, lowLatency, lowRam)
    val forward = if (window.minBufferMs == window.maxBufferMs) {
        "window ${seconds(window.minBufferMs)} s"
    } else {
        "min ${seconds(window.minBufferMs)} s · max ${seconds(window.maxBufferMs)} s"
    }
    return "$forward · start ${seconds(window.bufferForPlaybackMs)} s · " +
        "re-buffer ${seconds(window.bufferForPlaybackAfterRebufferMs)} s"
}

private fun seconds(ms: Int): String =
    if (ms % 1000 == 0) (ms / 1000).toString()
    else String.format(Locale.US, "%.1f", ms / 1000f)
