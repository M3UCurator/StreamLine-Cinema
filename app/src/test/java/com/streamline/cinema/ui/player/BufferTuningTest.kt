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
}
