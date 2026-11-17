package com.streamline.cinema.ui.player

import com.streamline.cinema.data.AppSettings
import com.streamline.cinema.data.BandwidthCap
import com.streamline.cinema.data.BufferTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferTuningTest {

    /** BALANCED must reproduce the pre-tier defaults so upgrades keep their window. */
    @Test
    fun balancedMatchesTheExistingProfiles() {
        val adaptive = bufferWindow(BufferTier.BALANCED, adaptive = true, lowLatency = true, lowRam = false)
        assertEquals(3_000, adaptive.minBufferMs)
        assertEquals(30_000, adaptive.maxBufferMs)
        assertEquals(1_200, adaptive.bufferForPlaybackMs)
        assertEquals(2_500, adaptive.bufferForPlaybackAfterRebufferMs)
        assertEquals(60_000, adaptive.backBufferMs)

        val relaxed = bufferWindow(BufferTier.BALANCED, adaptive = true, lowLatency = false, lowRam = false)
        assertEquals(5_000, relaxed.minBufferMs)
        assertEquals(2_000, relaxed.bufferForPlaybackMs)
        assertEquals(4_000, relaxed.bufferForPlaybackAfterRebufferMs)

        val progressive = bufferWindow(BufferTier.BALANCED, adaptive = false, lowLatency = true, lowRam = false)
        assertEquals(15_000, progressive.minBufferMs)
        assertEquals(50_000, progressive.maxBufferMs)
        assertEquals(2_500, progressive.bufferForPlaybackMs)
        assertEquals(5_000, progressive.bufferForPlaybackAfterRebufferMs)
        assertEquals(120_000, progressive.backBufferMs)
    }

    @Test
    fun tiersOrderTheForwardWindow() {
        val lean = bufferWindow(BufferTier.LEAN, adaptive = true, lowLatency = true, lowRam = false)
        val balanced = bufferWindow(BufferTier.BALANCED, adaptive = true, lowLatency = true, lowRam = false)
        val generous = bufferWindow(BufferTier.GENEROUS, adaptive = true, lowLatency = true, lowRam = false)
        assertTrue(lean.maxBufferMs < balanced.maxBufferMs)
        assertTrue(balanced.maxBufferMs < generous.maxBufferMs)
        assertTrue(lean.backBufferMs < balanced.backBufferMs)
        assertTrue(balanced.backBufferMs < generous.backBufferMs)

        val leanFile = bufferWindow(BufferTier.LEAN, adaptive = false, lowLatency = true, lowRam = false)
        val generousFile = bufferWindow(BufferTier.GENEROUS, adaptive = false, lowLatency = true, lowRam = false)
        assertTrue(leanFile.maxBufferMs < generousFile.maxBufferMs)
    }

    @Test
    fun lowRamKeepsAFixedWindowAndBackBuffer() {
        val balanced = bufferWindow(BufferTier.BALANCED, adaptive = true, lowLatency = true, lowRam = true)
        assertEquals(balanced.minBufferMs, balanced.maxBufferMs)
        assertEquals(8_000, balanced.maxBufferMs)
        assertEquals(20_000, balanced.backBufferMs)

        val noLowLatency = bufferWindow(BufferTier.BALANCED, adaptive = true, lowLatency = false, lowRam = true)
        assertEquals(12_000, noLowLatency.maxBufferMs)

        val file = bufferWindow(BufferTier.BALANCED, adaptive = false, lowLatency = true, lowRam = true)
        assertEquals(20_000, file.maxBufferMs)
        assertEquals(40_000, file.backBufferMs)

        BufferTier.entries.forEach { tier ->
            val window = bufferWindow(tier, adaptive = true, lowLatency = true, lowRam = true)
            assertEquals(window.minBufferMs, window.maxBufferMs)
        }
    }

    @Test
    fun nativeCachesFollowTheTierAndKeepTheShippedDefaults() {
        // BALANCED must reproduce cache-secs=10 and the 1.5 s VLC pre-roll.
        val balanced = nativeCache(BufferTier.BALANCED)
        assertEquals(10, balanced.mpvCacheSeconds)
        assertEquals(1_500, balanced.vlcCachingMs)

        val lean = nativeCache(BufferTier.LEAN)
        val generous = nativeCache(BufferTier.GENEROUS)
        assertTrue(lean.mpvCacheSeconds < balanced.mpvCacheSeconds)
        assertTrue(balanced.mpvCacheSeconds < generous.mpvCacheSeconds)
        assertTrue(lean.vlcCachingMs < balanced.vlcCachingMs)
        assertTrue(balanced.vlcCachingMs < generous.vlcCachingMs)

        assertEquals("10 s MPV · 1.5 s VLC", describeNativeCache(BufferTier.BALANCED))
        assertEquals("0.8 s VLC", describeNativeCache(BufferTier.LEAN).substringAfter("· "))
    }

    @Test
    fun bandwidthTiersAreOrderedAndAutoIsUncapped() {
        assertEquals(0, BandwidthCap.AUTO.bitsPerSecond)
        val capped = BandwidthCap.entries.filter { it.bitsPerSecond > 0 }
        assertEquals(capped.sortedBy { it.bitsPerSecond }, capped)
        assertEquals(BandwidthCap.AUTO, BandwidthCap.from(null))
        assertEquals(BandwidthCap.MBPS10, BandwidthCap.from(BandwidthCap.MBPS10.name))
        assertEquals("AUTO", BandwidthCap.AUTO.display)
        assertEquals("5 MBPS · 720p", BandwidthCap.MBPS5.display)
        assertEquals("20 MBPS · 4K", BandwidthCap.MBPS20.display)
    }

    @Test
    fun weakNetworkPresetSetsAndRestoresEveryKnob() {
        val plain = AppSettings()
        assertTrue(!plain.weakNetworkPreset)

        val weak = plain.withWeakNetworkPreset(true)
        assertEquals(BufferTier.LEAN, weak.bufferTier)
        assertEquals(BandwidthCap.MBPS2, weak.bandwidthCap)
        assertTrue(!weak.lowLatency)
        assertTrue(weak.weakNetworkPreset)
        // The tier carries the preset onto the native caches as well.
        assertEquals(4, nativeCache(weak.bufferTier).mpvCacheSeconds)
        assertEquals(800, nativeCache(weak.bufferTier).vlcCachingMs)

        val restored = weak.withWeakNetworkPreset(false)
        assertEquals(BufferTier.BALANCED, restored.bufferTier)
        assertEquals(BandwidthCap.AUTO, restored.bandwidthCap)
        assertTrue(restored.lowLatency)
        assertTrue(!restored.weakNetworkPreset)

        // Changing one knob by hand turns the derived toggle off instead of lying.
        assertTrue(!weak.copy(bufferTier = BufferTier.BALANCED).weakNetworkPreset)
        assertTrue(!weak.copy(bandwidthCap = BandwidthCap.MBPS5).weakNetworkPreset)
        assertTrue(!weak.copy(lowLatency = true).weakNetworkPreset)
    }

    /** The high-capacity switch owns the progressive window, whatever the tier says. */
    @Test
    fun highCapacityPinsTheProgressiveWindow() {
        BufferTier.entries.forEach { tier ->
            val window = bufferWindow(
                tier,
                adaptive = false,
                lowLatency = true,
                lowRam = false,
                highCapacityVod = true
            )
            assertEquals(HIGH_CAPACITY_VOD_WINDOW, window)
        }
        assertEquals(15_000, HIGH_CAPACITY_VOD_WINDOW.minBufferMs)
        assertEquals(50_000, HIGH_CAPACITY_VOD_WINDOW.maxBufferMs)
        assertEquals(2_500, HIGH_CAPACITY_VOD_WINDOW.bufferForPlaybackMs)
        assertEquals(5_000, HIGH_CAPACITY_VOD_WINDOW.bufferForPlaybackAfterRebufferMs)

        // Adaptive streams keep their own windows.
        val adaptive = bufferWindow(
            BufferTier.LEAN,
            adaptive = true,
            lowLatency = true,
            lowRam = false,
            highCapacityVod = true
        )
        assertEquals(2_000, adaptive.minBufferMs)

        // Low-RAM boxes take the pin too; their byte budget still caps memory.
        val lowRam = bufferWindow(
            BufferTier.BALANCED,
            adaptive = false,
            lowLatency = true,
            lowRam = true,
            highCapacityVod = true
        )
        assertEquals(HIGH_CAPACITY_VOD_WINDOW, lowRam)
    }

    @Test
    fun highCapacityDeepensTheNativeCaches() {
        val cache = nativeCache(BufferTier.LEAN, highCapacityVod = true)
        assertEquals(30, cache.mpvCacheSeconds)
        assertEquals(5_000, cache.vlcCachingMs)
        // Without the switch the tier still owns the caches.
        assertEquals(4, nativeCache(BufferTier.LEAN).mpvCacheSeconds)
    }

    @Test
    fun backBufferIsTrimmedToWhatTheBudgetRetains() {
        val lowRam = bufferWindow(BufferTier.BALANCED, adaptive = true, lowLatency = true, lowRam = true)
        // 48 MiB fits the 8 s forward window at the 20 Mbps reference with room
        // left for part, but not all, of the 20 s rewind pin.
        val budget = byteBudget(presetBytes = 48L * 1024 * 1024, maxMemoryBytes = 1024L * 1024 * 1024)

        val plan = planBuffer(lowRam, budget, bandwidthCapBps = 0)

        assertEquals(8_000, plan.window.maxBufferMs)
        assertTrue(plan.window.backBufferMs < lowRam.backBufferMs)
        assertTrue(plan.trimmedBackBufferMs > 0)
        assertTrue(!plan.windowExceedsBudget)
        val held = (plan.window.maxBufferMs + plan.window.backBufferMs).toLong() *
            (REFERENCE_BITRATE_BPS / 8_000)
        assertTrue("forward + back must fit the budget", held <= budget.effectiveBytes)
    }

    @Test
    fun aLowBandwidthCapLeavesTheWindowUntouched() {
        val lowRam = bufferWindow(BufferTier.BALANCED, adaptive = true, lowLatency = true, lowRam = true)
        val budget = byteBudget(LOW_RAM_ADAPTIVE_BUFFER_BYTES, maxMemoryBytes = 256L * 1024 * 1024)

        val plan = planBuffer(lowRam, budget, BandwidthCap.MBPS2.bitsPerSecond)

        assertEquals(lowRam, plan.window)
        assertEquals(0, plan.trimmedBackBufferMs)
        assertTrue(!plan.windowExceedsBudget)
    }

    @Test
    fun anOverBudgetWindowIsShortenedAndDropsTheBackBuffer() {
        val pinned = bufferWindow(
            BufferTier.BALANCED,
            adaptive = false,
            lowLatency = true,
            lowRam = true,
            highCapacityVod = true
        )
        val budget = byteBudget(LOW_RAM_ADAPTIVE_BUFFER_BYTES, maxMemoryBytes = 256L * 1024 * 1024)

        val plan = planBuffer(pinned, budget, bandwidthCapBps = 0)

        // 16 MiB at the 20 Mbps reference holds 6.7 s, so the requested 50 s
        // window is never handed to the loader as-is.
        assertTrue(plan.windowExceedsBudget)
        assertEquals(0, plan.window.backBufferMs)
        assertEquals(pinned.backBufferMs, plan.trimmedBackBufferMs)
        assertEquals(6_710, plan.window.minBufferMs)
        assertEquals(6_710, plan.window.maxBufferMs)
        assertEquals(2_500, plan.window.bufferForPlaybackMs)
        assertEquals(5_000, plan.window.bufferForPlaybackAfterRebufferMs)
        val held = plan.window.maxBufferMs.toLong() * (REFERENCE_BITRATE_BPS / 8_000)
        assertTrue(held <= budget.effectiveBytes)
    }

    @Test
    fun unbudgetedDevicesKeepTheirWindow() {
        val window = bufferWindow(BufferTier.GENEROUS, adaptive = false, lowLatency = false, lowRam = false)
        val plan = planBuffer(window, budget = null, bandwidthCapBps = 0)
        assertEquals(window, plan.window)
        assertEquals(0, plan.trimmedBackBufferMs)
        assertTrue(!plan.windowExceedsBudget)
    }

    @Test
    fun everyPlannedWindowFitsOrReportsThatItCannot() {
        BufferTier.entries.forEach { tier ->
            listOf(true, false).forEach { adaptive ->
                listOf(true, false).forEach { lowRam ->
                    listOf(BandwidthCap.AUTO, BandwidthCap.MBPS2, BandwidthCap.MBPS20).forEach { cap ->
                        val window = bufferWindow(
                            tier,
                            adaptive = adaptive,
                            lowLatency = true,
                            lowRam = lowRam
                        )
                        val budget = if (lowRam) {
                            byteBudget(
                                if (adaptive) LOW_RAM_ADAPTIVE_BUFFER_BYTES
                                else LOW_RAM_PROGRESSIVE_BUFFER_BYTES,
                                maxMemoryBytes = 256L * 1024 * 1024
                            )
                        } else null
                        val plan = planBuffer(window, budget, cap.bitsPerSecond)
                        if (budget == null) {
                            assertEquals(window, plan.window)
                            return@forEach
                        }
                        val bitrate = cap.bitsPerSecond.takeIf { it > 0 }?.toLong()
                            ?: REFERENCE_BITRATE_BPS
                        val held = (plan.window.maxBufferMs + plan.window.backBufferMs).toLong() *
                            (bitrate / 8_000)
                        if (plan.windowExceedsBudget) {
                            assertEquals(0, plan.window.backBufferMs)
                        }
                        // Whether the window was trimmed or shortened, what the
                        // loader receives must fit the budget it is given.
                        assertTrue(held <= budget.effectiveBytes)
                    }
                }
            }
        }
    }

    /** Every window DefaultLoadControl receives must be internally consistent. */
    @Test
    fun everyPresetSatisfiesTheLoadControlConstraints() {
        BufferTier.entries.forEach { tier ->
            listOf(
                bufferWindow(tier, adaptive = true, lowLatency = false, lowRam = false),
                bufferWindow(tier, adaptive = true, lowLatency = true, lowRam = false),
                bufferWindow(tier, adaptive = false, lowLatency = true, lowRam = false),
                bufferWindow(tier, adaptive = true, lowLatency = true, lowRam = true),
                bufferWindow(
                    tier,
                    adaptive = false,
                    lowLatency = false,
                    lowRam = true,
                    highCapacityVod = true
                )
            ).forEach { window ->
                assertTrue(window.minBufferMs >= window.bufferForPlaybackMs)
                assertTrue(window.minBufferMs >= window.bufferForPlaybackAfterRebufferMs)
                assertTrue(window.maxBufferMs >= window.minBufferMs)
                assertTrue(window.backBufferMs >= 0)
            }
        }
    }

    @Test
    fun lowRamPresetsAreBoundedByTheHeapShare() {
        // The byte presets stay at their shipped values; the resolver only ever
        // lowers them, and never to zero (Media3 reads <=0 as "unbounded").
        assertEquals(16L * 1024 * 1024, LOW_RAM_ADAPTIVE_BUFFER_BYTES)
        assertEquals(24L * 1024 * 1024, LOW_RAM_PROGRESSIVE_BUFFER_BYTES)
        listOf(LOW_RAM_ADAPTIVE_BUFFER_BYTES, LOW_RAM_PROGRESSIVE_BUFFER_BYTES).forEach { preset ->
            assertTrue(preset > 0)
            val budget = byteBudget(preset, maxMemoryBytes = 256L * 1024 * 1024)
            assertTrue(budget.effectiveBytes in 1..(16L * 1024 * 1024))
            assertEquals(preset > 16L * 1024 * 1024, budget.clamped)
        }
    }

    @Test
    fun byteBudgetHonoursAPresetThatFitsTheHeapShare() {
        val budget = byteBudget(presetBytes = 24L * 1024 * 1024, maxMemoryBytes = 1024L * 1024 * 1024)
        assertEquals(24L * 1024 * 1024, budget.effectiveBytes)
        assertEquals(64L * 1024 * 1024, budget.heapLimitBytes)
        assertTrue(!budget.clamped)
        assertEquals(
            "24.0 MiB preset (heap 1024.0 MiB)",
            describeByteBudget(budget)
        )
    }

    @Test
    fun byteBudgetClampsAPresetAboveASixteenthOfTheHeap() {
        // A 256 MiB heap allows 16 MiB; the 24 MiB progressive preset asks for more.
        val budget = byteBudget(presetBytes = 24L * 1024 * 1024, maxMemoryBytes = 256L * 1024 * 1024)
        assertEquals(16L * 1024 * 1024, budget.effectiveBytes)
        assertEquals(24L * 1024 * 1024, budget.presetBytes)
        assertTrue(budget.clamped)
        assertEquals(
            "24.0 MiB preset clamped to 16.0 MiB (heap 256.0 MiB)",
            describeByteBudget(budget)
        )
    }

    @Test
    fun byteBudgetEqualityIsNotAClampAndTinyHeapsKeepASliver() {
        val exact = byteBudget(presetBytes = 16L * 1024 * 1024, maxMemoryBytes = 256L * 1024 * 1024)
        assertEquals(16L * 1024 * 1024, exact.effectiveBytes)
        assertTrue(!exact.clamped)

        // A very small heap must not yield a zero or negative budget: Media3
        // reads a non-positive target as "unbounded" and would lift the cap.
        val tiny = byteBudget(presetBytes = 16L * 1024 * 1024, maxMemoryBytes = 8L * 1024 * 1024)
        assertEquals(512L * 1024, tiny.effectiveBytes)
        assertTrue(tiny.effectiveBytes > 0)
        assertTrue(tiny.clamped)

        val zeroHeap = byteBudget(presetBytes = 16L * 1024 * 1024, maxMemoryBytes = 0L)
        assertTrue(zeroHeap.effectiveBytes > 0)
    }
}
