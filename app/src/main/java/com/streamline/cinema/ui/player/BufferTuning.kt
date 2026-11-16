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

// Hard RAM ceilings for the low-RAM load control. Seconds are not a memory
// bound: the same window holds four times the bytes at 4K, so the loader is
// capped by bytes and the resolver below clamps the preset to the device heap.
internal const val LOW_RAM_ADAPTIVE_BUFFER_BYTES = 16L * 1024 * 1024
internal const val LOW_RAM_PROGRESSIVE_BUFFER_BYTES = 24L * 1024 * 1024

/**
 * The window the "High-capacity buffering" switch pins on MP4/MKV: a 15 s
 * fill before playback may start, 50 s of forward buffer to ride out a line
 * that dips, and the default 2.5 s / 5 s start and re-buffer gates.
 */
internal val HIGH_CAPACITY_VOD_WINDOW = BufferWindow(
    minBufferMs = 15_000,
    maxBufferMs = 50_000,
    bufferForPlaybackMs = START_MS,
    bufferForPlaybackAfterRebufferMs = REBUFFER_MS,
    backBufferMs = 120_000
)

/**
 * BALANCED must stay byte-for-byte the pre-tier behaviour so existing installs
 * keep their tuned window; LEAN and GENEROUS only move the forward window and
 * the rewind retention around it.
 *
 * [highCapacityVod] pins the progressive MP4/MKV window to
 * [HIGH_CAPACITY_VOD_WINDOW] whatever the tier says, including on the low-RAM
 * profile: those boxes still enforce their byte budget through
 * `setTargetBufferBytes`, so the pin widens the time window without letting the
 * loader grow past the RAM the device reports.
 */
internal fun bufferWindow(
    tier: BufferTier,
    adaptive: Boolean,
    lowLatency: Boolean,
    lowRam: Boolean,
    highCapacityVod: Boolean = false
): BufferWindow {
    if (!adaptive && highCapacityVod) return HIGH_CAPACITY_VOD_WINDOW
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
 * The high-capacity switch deepens both caches for progressive VOD too.
 */
internal data class NativeCache(
    val mpvCacheSeconds: Int,
    val vlcCachingMs: Int
)

internal fun nativeCache(tier: BufferTier, highCapacityVod: Boolean = false): NativeCache =
    if (highCapacityVod) {
        NativeCache(mpvCacheSeconds = 30, vlcCachingMs = 5_000)
    } else {
        when (tier) {
            BufferTier.LEAN -> NativeCache(mpvCacheSeconds = 4, vlcCachingMs = 800)
            BufferTier.BALANCED -> NativeCache(mpvCacheSeconds = 10, vlcCachingMs = 1_500)
            BufferTier.GENEROUS -> NativeCache(mpvCacheSeconds = 20, vlcCachingMs = 4_000)
        }
    }

/** One-line summary for Settings, e.g. "10 s MPV · 1.5 s VLC". */
internal fun describeNativeCache(tier: BufferTier, highCapacityVod: Boolean = false): String {
    val cache = nativeCache(tier, highCapacityVod)
    return "${cache.mpvCacheSeconds} s MPV · ${seconds(cache.vlcCachingMs)} s VLC"
}

internal const val BYTE_BUDGET_HEAP_DIVISOR = 16

/**
 * The loader's byte ceiling. A preset is a wish, not a promise: the budget is
 * capped at a sixteenth of the process heap, because Media3 counts every
 * allocation against it and a preset larger than that share leaves a 32-bit
 * box without room for codecs and UI. The effective value is what the load
 * control receives; [clamped] records that the preset was not honoured.
 */
internal data class ByteBudget(
    val presetBytes: Long,
    val effectiveBytes: Long,
    val heapLimitBytes: Long
) {
    val clamped: Boolean get() = effectiveBytes < presetBytes
}

internal fun byteBudget(
    presetBytes: Long,
    maxMemoryBytes: Long = Runtime.getRuntime().maxMemory()
): ByteBudget {
    val limit = (maxMemoryBytes / BYTE_BUDGET_HEAP_DIVISOR).coerceAtLeast(1L)
    return ByteBudget(
        presetBytes = presetBytes,
        effectiveBytes = minOf(presetBytes, limit),
        heapLimitBytes = limit
    )
}

/** One-line budget summary for the build log, e.g. "24 MiB preset clamped to 16 MiB (heap 256 MiB)". */
internal fun describeByteBudget(budget: ByteBudget): String {
    val preset = mebibytes(budget.presetBytes)
    val effective = mebibytes(budget.effectiveBytes)
    val heap = mebibytes(budget.heapLimitBytes * BYTE_BUDGET_HEAP_DIVISOR)
    return if (budget.clamped) "$preset MiB preset clamped to $effective MiB (heap $heap MiB)"
    else "$effective MiB preset (heap $heap MiB)"
}

private fun mebibytes(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / 1_048_576f)

/** Compact form for dense rows, e.g. "15–50 s". */
internal fun describeWindow(window: BufferWindow): String =
    if (window.minBufferMs == window.maxBufferMs) {
        "window ${seconds(window.minBufferMs)} s"
    } else {
        "${seconds(window.minBufferMs)}–${seconds(window.maxBufferMs)} s"
    }

internal fun seconds(ms: Int): String =
    if (ms % 1000 == 0) (ms / 1000).toString()
    else String.format(Locale.US, "%.1f", ms / 1000f)
