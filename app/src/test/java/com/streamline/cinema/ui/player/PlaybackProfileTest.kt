package com.streamline.cinema.ui.player

import com.streamline.cinema.data.AppSettings
import com.streamline.cinema.data.BandwidthCap
import com.streamline.cinema.data.BufferTier
import com.streamline.cinema.data.DecoderType
import com.streamline.cinema.data.PlaybackEngine
import com.streamline.cinema.data.QualityLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProfileTest {

    private val settings = AppSettings()
    private val headers = mapOf("Referer" to "https://example.test/watch")

    private fun profile(
        settings: AppSettings = this.settings,
        engine: PlaybackEngine = settings.playbackEngine,
        adaptive: Boolean = true,
        lowRam: Boolean = false,
        forceBuiltIn: Boolean = false
    ) = playbackProfile(
        settings = settings.copy(playbackEngine = engine),
        decoder = settings.decoderType,
        adaptive = adaptive,
        lowRam = lowRam,
        headers = headers,
        forceBuiltIn = forceBuiltIn
    )

    @Test
    fun bufferSettingsMoveTheRebuildKey() {
        val balanced = profile()
        val lean = playbackProfile(
            settings = settings.copy(bufferTier = BufferTier.LEAN),
            decoder = settings.decoderType,
            adaptive = true,
            lowRam = false,
            headers = headers
        )
        assertNotEquals(balanced.rebuildKey, lean.rebuildKey)
    }

    @Test
    fun liveClassSettingsLeaveTheRebuildKeyAlone() {
        val base = profile()
        val changed = playbackProfile(
            settings = settings.copy(
                abrEnabled = !settings.abrEnabled,
                bandwidthCap = BandwidthCap.MBPS10,
                qualityLimit = QualityLimit.Q1080
            ),
            decoder = settings.decoderType,
            adaptive = true,
            lowRam = false,
            headers = headers
        )
        assertEquals(base.rebuildKey, changed.rebuildKey)
        assertNotEquals(base.bandwidthCap, changed.bandwidthCap)
    }

    @Test
    fun resolutionCapCapsA4KLadderAt1080p() {
        assertEquals(ResolutionCap(1920, 1080), profileWithQuality(QualityLimit.OFF).maxResolution)
        // A 4K setting may list 4K sources, but the selector never hands the
        // decoder a rendition above 1080p.
        assertEquals(ResolutionCap(1920, 1080), profileWithQuality(QualityLimit.Q4K).maxResolution)

        val hd = profileWithQuality(QualityLimit.Q1080).maxResolution
        assertEquals(1920, hd.maxWidth)
        assertEquals(1079, hd.maxHeight)

        assertEquals(479, profileWithQuality(QualityLimit.Q480).maxResolution.maxHeight)
    }

    @Test
    fun resolutionCapRebuildsOnlyTheVlcEngine() {
        val media3 = playbackProfile(
            settings = settings.copy(qualityLimit = QualityLimit.Q720),
            decoder = settings.decoderType,
            adaptive = true,
            lowRam = false,
            headers = headers
        )
        // Media3 applies the cap through the running selector, so no rebuild.
        assertEquals(profile().rebuildKey, media3.rebuildKey)

        val vlc = profile(engine = PlaybackEngine.VLC)
        val vlcCapped = playbackProfile(
            settings = settings.copy(playbackEngine = PlaybackEngine.VLC, qualityLimit = QualityLimit.Q720),
            decoder = settings.decoderType,
            adaptive = true,
            lowRam = false,
            headers = headers
        )
        assertNotEquals(vlc.rebuildKey, vlcCapped.rebuildKey)

        // MPV has no per-rendition resolution option, so it stays live-class.
        val mpv = profile(engine = PlaybackEngine.MPV)
        val mpvCapped = playbackProfile(
            settings = settings.copy(playbackEngine = PlaybackEngine.MPV, qualityLimit = QualityLimit.Q720),
            decoder = settings.decoderType,
            adaptive = true,
            lowRam = false,
            headers = headers
        )
        assertEquals(mpv.rebuildKey, mpvCapped.rebuildKey)
    }

    @Test
    fun segmentRetriesComeFromTheResolver() {
        assertEquals(SEGMENT_RETRIES, profile().segmentRetries)
    }

    private fun profileWithQuality(limit: QualityLimit) = playbackProfile(
        settings = settings.copy(qualityLimit = limit),
        decoder = settings.decoderType,
        adaptive = true,
        lowRam = false,
        headers = headers
    )

    @Test
    fun headersRebuildNativeEnginesOnly() {
        val media3 = profile(engine = PlaybackEngine.AUTO)
        val media3OtherHeaders = playbackProfile(
            settings = settings,
            decoder = settings.decoderType,
            adaptive = true,
            lowRam = false,
            headers = emptyMap()
        )
        assertEquals(media3.rebuildKey, media3OtherHeaders.rebuildKey)

        val vlc = profile(engine = PlaybackEngine.VLC)
        val vlcOtherHeaders = playbackProfile(
            settings = settings.copy(playbackEngine = PlaybackEngine.VLC),
            decoder = settings.decoderType,
            adaptive = true,
            lowRam = false,
            headers = emptyMap()
        )
        assertNotEquals(vlc.rebuildKey, vlcOtherHeaders.rebuildKey)
    }

    @Test
    fun lowRamResolvesAByteBudgetAndTurnsOffTimePriority() {
        val lowRam = profile(lowRam = true)
        assertTrue(lowRam.memoryBounded)
        val budget = requireNotNull(lowRam.byteBudget)
        assertTrue(budget.effectiveBytes > 0)
        // The unit-test heap decides whether the preset survives the clamp,
        // so assert the relationship rather than the exact number.
        assertEquals(
            minOf(budget.presetBytes, Runtime.getRuntime().maxMemory() / BYTE_BUDGET_HEAP_DIVISOR),
            budget.effectiveBytes
        )

        val fullRam = profile(lowRam = false)
        assertFalse(fullRam.memoryBounded)
        assertNull(fullRam.byteBudget)
        assertEquals("unbounded (time-prioritised)", describeMemoryBudget(fullRam))
    }

    @Test
    fun adaptiveAndProgressiveResolveDifferentBudgets() {
        val adaptive = profile(adaptive = true, lowRam = true)
        val progressive = profile(adaptive = false, lowRam = true)
        assertTrue(
            requireNotNull(progressive.byteBudget).presetBytes >
                requireNotNull(adaptive.byteBudget).presetBytes
        )
        assertNotEquals(adaptive.window, progressive.window)
    }

    @Test
    fun forcedFallbackNamesTheEngineThatRuns() {
        val requested = profile(engine = PlaybackEngine.VLC)
        assertEquals(PlaybackEngine.VLC, requested.engine)

        val fallenBack = profile(engine = PlaybackEngine.VLC, forceBuiltIn = true)
        assertEquals(PlaybackEngine.BUILT_IN, fallenBack.engine)
        // The rebuilt key is the one a built-in start would have used, so a
        // fallback cannot rebuild a second time for the same settings.
        val builtIn = profile(engine = PlaybackEngine.BUILT_IN, forceBuiltIn = true)
        assertEquals(builtIn.rebuildKey, fallenBack.rebuildKey)
    }

    @Test
    fun decoderLivesInTheRebuildKey() {
        val auto = profile()
        val software = playbackProfile(
            settings = settings,
            decoder = DecoderType.SW,
            adaptive = true,
            lowRam = false,
            headers = headers
        )
        assertNotEquals(auto.rebuildKey, software.rebuildKey)
    }

    @Test
    fun descriptionsCarryTheResolvedWindow() {
        val full = profile(adaptive = true, lowRam = false)
        assertTrue(describeBuffer(full).contains("start"))
        assertTrue(describeNativeCache(full).contains("VLC"))
        assertTrue(describeMemoryBudget(full).startsWith("unbounded"))

        val lowRam = profile(adaptive = true, lowRam = true)
        assertTrue(describeMemoryBudget(lowRam).contains("heap"))
    }
}
