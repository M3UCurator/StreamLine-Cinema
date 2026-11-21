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

// Playback gates. The start gate waits for 3.5 s of media before the first
// frame, which is what separates a steady open from a stuttering one at 1080p.
// The re-buffer gate waits for 8 s once playback has stalled, so a link that
// dips keeps a continuous picture instead of oscillating in and out of
// buffering. Media3 1.11's own defaults are 1 s / 2 s; both stay well inside
// the 15 s balanced window. Kept as literals so the mapping is testable without
// loading Media3 on the JVM.
private const val START_MS = 3_500
private const val REBUFFER_MS = 8_000

// Live adaptive streams keep lower gates: long enough to survive one missed
// segment fetch, short enough that a zap or a stall recovery is not left
// waiting. The 8 s VOD gate would add 5 s to every channel recovery.
private const val LIVE_START_MS = 1_500
private const val LIVE_REBUFFER_MS = 3_000

// Hard RAM ceilings for the low-RAM load control. Seconds are not a memory
// bound: the same window holds four times the bytes at 4K, so the loader is
// capped by bytes and the resolver below clamps the preset to the device heap.
internal const val LOW_RAM_ADAPTIVE_BUFFER_BYTES = 16L * 1024 * 1024
internal const val LOW_RAM_PROGRESSIVE_BUFFER_BYTES = 24L * 1024 * 1024

// Every device gets a byte ceiling, not just the low-RAM profile. Media3 leaves
// the target byte size unset, so its loader is bounded only by time and by its
// ~200 MiB allocation chunk; on a 32-bit TV box that chunk is most of the heap
// even when the device does not declare itself low-RAM. These presets only bite
// when the heap share is smaller than the preset, so a roomy box keeps the
// time-prioritised behaviour it had before.
internal const val NORMAL_ADAPTIVE_BUFFER_BYTES = 128L * 1024 * 1024
internal const val NORMAL_PROGRESSIVE_BUFFER_BYTES = 192L * 1024 * 1024
internal const val LOW_RAM_HEAP_DIVISOR = 16
internal const val NORMAL_HEAP_DIVISOR = 4

/**
 * The window the "High-capacity buffering" switch requests on MP4/MKV: a 15 s
 * fill before playback may start, 50 s of forward buffer to ride out a line
 * that dips, and the shared 3.5 s / 8 s start and re-buffer gates.
 */
internal val HIGH_CAPACITY_VOD_WINDOW = BufferWindow(
    minBufferMs = 15_000,
    maxBufferMs = 50_000,
    bufferForPlaybackMs = START_MS,
    bufferForPlaybackAfterRebufferMs = REBUFFER_MS,
    backBufferMs = 120_000
)

/**
 * The tier moves the forward window and the rewind retention; the start and
 * re-buffer gates come from [START_MS] and [REBUFFER_MS], except on the live
 * fast-start window, where recovery latency matters more than headroom.
 *
 * [live] splits the adaptive fast-start branch: HLS/DASH VOD opens with the
 * file-class gates and the 8 s window that carries them, while live keeps the
 * low-latency pair so a zap does not pay the VOD start delay.
 *
 * [highCapacityVod] requests the progressive MP4/MKV window
 * [HIGH_CAPACITY_VOD_WINDOW] whatever the tier says, including on the low-RAM
 * profile. It is a request, not a promise: the loader stops at
 * `setTargetBufferBytes`, so on a box whose budget holds fewer than 50 s the
 * window would never fill. `planBuffer` shortens it to the seconds the budget
 * actually holds and drops the back buffer, and the build log says so.
 */
internal fun bufferWindow(
    tier: BufferTier,
    adaptive: Boolean,
    lowLatency: Boolean,
    lowRam: Boolean,
    highCapacityVod: Boolean = false,
    live: Boolean = false
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
        // A lean fixed window can be shorter than the 8 s re-buffer gate, and
        // DefaultLoadControl rejects a gate above minBufferMs, so both gates are
        // clamped to the window the budget can actually hold.
        return BufferWindow(
            fixed,
            fixed,
            minOf(START_MS, fixed),
            minOf(REBUFFER_MS, fixed),
            back
        )
    }
    if (adaptive) {
        return when (tier) {
            BufferTier.LEAN -> BufferWindow(2_000, 15_000, 1_000, 2_000, 30_000)
            BufferTier.BALANCED -> if (lowLatency) {
                if (live) {
                    // Live keeps the fast-start trade: one missed segment fetch
                    // must not cost five extra seconds on every zap.
                    BufferWindow(3_000, 30_000, LIVE_START_MS, LIVE_REBUFFER_MS, 60_000)
                } else {
                    // HLS/DASH VOD opens with the file-class gates. The window
                    // floor is raised to 8 s because DefaultLoadControl rejects
                    // a re-buffer gate above minBufferMs.
                    BufferWindow(8_000, 30_000, START_MS, REBUFFER_MS, 60_000)
                }
            } else {
                // Relaxed adaptive gets the same class of headroom as files.
                // minBufferMs is raised too: it may not sit below the gate.
                BufferWindow(6_000, 30_000, START_MS, 6_000, 60_000)
            }
            BufferTier.GENEROUS -> BufferWindow(8_000, 60_000, START_MS, REBUFFER_MS, 90_000)
        }
    }
    return when (tier) {
        BufferTier.LEAN -> BufferWindow(10_000, 30_000, 3_000, 6_000, 60_000)
        BufferTier.BALANCED -> BufferWindow(15_000, 50_000, START_MS, REBUFFER_MS, 120_000)
        BufferTier.GENEROUS -> BufferWindow(20_000, 90_000, START_MS, REBUFFER_MS, 180_000)
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
 * capped at a share of the process heap, because Media3 counts every
 * allocation against it and a preset larger than that share leaves a 32-bit
 * box without room for codecs and UI. The effective value is what the load
 * control receives; [clamped] records that the preset was not honoured.
 */
internal data class ByteBudget(
    val presetBytes: Long,
    val effectiveBytes: Long,
    val heapLimitBytes: Long,
    val heapDivisor: Int = BYTE_BUDGET_HEAP_DIVISOR
) {
    val clamped: Boolean get() = effectiveBytes < presetBytes
}

internal fun byteBudget(
    presetBytes: Long,
    maxMemoryBytes: Long = Runtime.getRuntime().maxMemory(),
    heapDivisor: Int = BYTE_BUDGET_HEAP_DIVISOR
): ByteBudget {
    val limit = (maxMemoryBytes / heapDivisor).coerceAtLeast(1L)
    return ByteBudget(
        presetBytes = presetBytes,
        effectiveBytes = minOf(presetBytes, limit),
        heapLimitBytes = limit,
        heapDivisor = heapDivisor
    )
}

/**
 * The nominal bitrate the budget arithmetic assumes when the user set no cap.
 * It is not a guess at the stream: it converts bytes into a time bound. 20 Mbps
 * is a 1080p rendition at the top of its range; a higher tier reaches the byte
 * cap sooner, which shortens the planned window rather than overstating how
 * much memory the loader may hold.
 */
internal const val REFERENCE_BITRATE_BPS = 20_000_000L

/**
 * A time window paired with the byte budget that will enforce it.
 *
 * Media3 counts the back buffer against the same allocation the forward fill
 * uses, so a 120 s rewind pin on a 16 MiB budget leaves nothing for the
 * forward window. [trimmedBackBufferMs] records what had to go;
 * [windowExceedsBudget] means the requested forward window did not fit the
 * budget, in which case [window] was shortened to the seconds the budget holds
 * and the back buffer was dropped.
 */
internal data class BufferPlan(
    val window: BufferWindow,
    val budget: ByteBudget?,
    val trimmedBackBufferMs: Int,
    val windowExceedsBudget: Boolean
)

internal fun planBuffer(
    window: BufferWindow,
    budget: ByteBudget?,
    bandwidthCapBps: Int
): BufferPlan {
    if (budget == null) return BufferPlan(window, null, 0, false)
    val bitrateBps = bandwidthCapBps.takeIf { it > 0 }?.toLong() ?: REFERENCE_BITRATE_BPS
    val bytesPerMs = (bitrateBps / 8_000).coerceAtLeast(1L)
    val forwardBytes = window.maxBufferMs * bytesPerMs
    val available = budget.effectiveBytes - forwardBytes
    if (available < 0) {
        // The loader stops at the byte cap, so a window larger than the budget
        // is a request it can never honour: shorten it to the seconds that fit,
        // start gates included, instead of reporting a span the buffer can't hold.
        val fittingMs = (budget.effectiveBytes / bytesPerMs).coerceAtLeast(1L).toInt()
        val fitted = window.copy(
            minBufferMs = minOf(window.minBufferMs, fittingMs),
            maxBufferMs = minOf(window.maxBufferMs, fittingMs),
            bufferForPlaybackMs = minOf(window.bufferForPlaybackMs, fittingMs),
            bufferForPlaybackAfterRebufferMs =
                minOf(window.bufferForPlaybackAfterRebufferMs, fittingMs),
            backBufferMs = 0
        )
        return BufferPlan(
            window = fitted,
            budget = budget,
            trimmedBackBufferMs = window.backBufferMs,
            windowExceedsBudget = true
        )
    }
    val retained = minOf(window.backBufferMs.toLong(), available / bytesPerMs).toInt()
    return BufferPlan(
        window = window.copy(backBufferMs = retained),
        budget = budget,
        trimmedBackBufferMs = window.backBufferMs - retained,
        windowExceedsBudget = false
    )
}

/** One-line budget summary for the build log, e.g. "24 MiB preset clamped to 16 MiB (heap 256 MiB)". */
internal fun describeByteBudget(budget: ByteBudget): String {
    val preset = mebibytes(budget.presetBytes)
    val effective = mebibytes(budget.effectiveBytes)
    val heap = mebibytes(budget.heapLimitBytes * budget.heapDivisor)
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
