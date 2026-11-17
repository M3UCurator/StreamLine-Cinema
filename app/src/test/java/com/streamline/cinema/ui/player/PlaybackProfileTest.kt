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

    // Explicit heap: the test JVM's own maxMemory varies by machine, and a
    // small one would switch the normal-device byte ceiling on unexpectedly.
    private fun profile(
        settings: AppSettings = this.settings,
        engine: PlaybackEngine = settings.playbackEngine,
        decoder: DecoderType = settings.decoderType,
        adaptive: Boolean = true,
        lowRam: Boolean = false,
        headers: Map<String, String> = this.headers,
        maxMemoryBytes: Long = ROOMY_HEAP_BYTES,
        forceBuiltIn: Boolean = false
    ) = playbackProfile(
        settings = settings.copy(playbackEngine = engine),
        decoder = decoder,
        adaptive = adaptive,
        lowRam = lowRam,
        headers = headers,
        maxMemoryBytes = maxMemoryBytes,
        forceBuiltIn = forceBuiltIn
    )

    private companion object {
        const val ROOMY_HEAP_BYTES = 4L * 1024 * 1024 * 1024
    }

    @Test
    fun bufferSettingsMoveTheRebuildKey() {
        val balanced = profile()
        val lean = profile(settings = settings.copy(bufferTier = BufferTier.LEAN))
        assertNotEquals(balanced.rebuildKey, lean.rebuildKey)
    }

    @Test
    fun liveClassSettingsLeaveTheRebuildKeyAlone() {
        val base = profile()
        val changed = profile(
            settings = settings.copy(
                abrEnabled = !settings.abrEnabled,
                bandwidthCap = BandwidthCap.MBPS10,
                qualityLimit = QualityLimit.Q1080
            )
        )
        assertEquals(base.rebuildKey, changed.rebuildKey)
        assertNotEquals(base.bandwidthCap, changed.bandwidthCap)
    }

    @Test
    fun resolutionCapFollowsTheQualityLimit() {
        // No limit plays the full ladder, up to the 8K sources the labels detect.
        assertEquals(ResolutionCap(7680, 4320), profileWithQuality(QualityLimit.OFF).maxResolution)

        // The selected tier is a ceiling, not a floor: it and everything below
        // it play, so "4K" admits a 2160p rendition and "1080p" a 1080p one.
        assertEquals(ResolutionCap(4096, 2160), profileWithQuality(QualityLimit.Q4K).maxResolution)
        assertEquals(ResolutionCap(2560, 1080), profileWithQuality(QualityLimit.Q1080).maxResolution)
        assertEquals(ResolutionCap(1920, 720), profileWithQuality(QualityLimit.Q720).maxResolution)
        assertEquals(ResolutionCap(854, 480), profileWithQuality(QualityLimit.Q480).maxResolution)
    }

    @Test
    fun resolutionCapRebuildsOnlyTheVlcEngine() {
        val media3 = profile(settings = settings.copy(qualityLimit = QualityLimit.Q720))
        // Media3 applies the cap through the running selector, so no rebuild.
        assertEquals(profile().rebuildKey, media3.rebuildKey)

        val vlc = profile(engine = PlaybackEngine.VLC)
        val vlcCapped = profile(
            settings = settings.copy(playbackEngine = PlaybackEngine.VLC, qualityLimit = QualityLimit.Q720)
        )
        assertNotEquals(vlc.rebuildKey, vlcCapped.rebuildKey)

        // MPV has no per-rendition resolution option, so it stays live-class.
        val mpv = profile(engine = PlaybackEngine.MPV)
        val mpvCapped = profile(
            settings = settings.copy(playbackEngine = PlaybackEngine.MPV, qualityLimit = QualityLimit.Q720)
        )
        assertEquals(mpv.rebuildKey, mpvCapped.rebuildKey)
    }

    @Test
    fun segmentRetriesComeFromTheResolver() {
        assertEquals(SEGMENT_RETRIES, profile().segmentRetries)
    }

    private fun profileWithQuality(limit: QualityLimit) =
        profile(settings = settings.copy(qualityLimit = limit))

    @Test
    fun headersRebuildNativeEnginesOnly() {
        val media3 = profile(engine = PlaybackEngine.AUTO)
        val media3OtherHeaders = profile(engine = PlaybackEngine.AUTO, headers = emptyMap())
        assertEquals(media3.rebuildKey, media3OtherHeaders.rebuildKey)

        val vlc = profile(engine = PlaybackEngine.VLC)
        val vlcOtherHeaders = profile(engine = PlaybackEngine.VLC, headers = emptyMap())
        assertNotEquals(vlc.rebuildKey, vlcOtherHeaders.rebuildKey)
    }

    @Test
    fun lowRamResolvesAByteBudgetAndTurnsOffTimePriority() {
        // 256 MiB leaves 16 MiB under the low-RAM divisor, so the adaptive
        // preset is exactly the heap share and the progressive one is clamped.
        val heap = 256L * 1024 * 1024
        val lowRam = profile(lowRam = true, maxMemoryBytes = heap)
        assertTrue(lowRam.memoryBounded)
        val budget = requireNotNull(lowRam.byteBudget)
        assertTrue(budget.effectiveBytes > 0)
        assertEquals(LOW_RAM_ADAPTIVE_BUFFER_BYTES, budget.presetBytes)
        assertEquals(heap / LOW_RAM_HEAP_DIVISOR, budget.effectiveBytes)
        assertEquals(LOW_RAM_HEAP_DIVISOR, budget.heapDivisor)

        val fullRam = profile(lowRam = false)
        assertFalse(fullRam.memoryBounded)
        assertNull(fullRam.byteBudget)
        assertEquals("unbounded (time-prioritised)", describeMemoryBudget(fullRam))
    }

    @Test
    fun normalDevicesOnlyCarryABudgetWhenTheHeapClampsIt() {
        // A roomy box keeps the time-prioritised loader it always had.
        assertNull(profile(maxMemoryBytes = ROOMY_HEAP_BYTES).byteBudget)

        // 384 MiB gives a 96 MiB cap, below both normal presets.
        val box = profile(maxMemoryBytes = 384L * 1024 * 1024)
        val budget = requireNotNull(box.byteBudget)
        assertTrue(budget.clamped)
        assertEquals(NORMAL_ADAPTIVE_BUFFER_BYTES, budget.presetBytes)
        assertEquals(384L * 1024 * 1024 / NORMAL_HEAP_DIVISOR, budget.effectiveBytes)
        assertEquals(NORMAL_HEAP_DIVISOR, budget.heapDivisor)

        // Exactly enough for the adaptive preset is not a clamp, so it stays
        // off and the loader remains time-prioritised.
        assertNull(profile(maxMemoryBytes = NORMAL_ADAPTIVE_BUFFER_BYTES * NORMAL_HEAP_DIVISOR).byteBudget)
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
        val software = profile(decoder = DecoderType.SW)
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
