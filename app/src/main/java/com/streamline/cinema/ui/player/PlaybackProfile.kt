package com.streamline.cinema.ui.player

import com.streamline.cinema.data.AppSettings
import com.streamline.cinema.data.BandwidthCap
import com.streamline.cinema.data.BufferTier
import com.streamline.cinema.data.DecoderType
import com.streamline.cinema.data.PlaybackEngine
import com.streamline.cinema.data.QualityLimit

/**
 * Everything a playback start reads, resolved once from Settings and the
 * device instead of each consumer re-deriving it.
 *
 * The split matters: [rebuildKey] names the fields a running player cannot
 * absorb (engine, decoder, renderer chain, load control), while the remaining
 * live-class fields are pushed into the existing instance through the track
 * selector or the data source factories. [maxResolution] is live-class for
 * Media3 and constructor-time for VLC, whose adaptive module reads it as a
 * libVLC option. Settings shows the same resolved values the player receives,
 * so "picked" and "in effect" cannot drift.
 */
internal data class PlaybackProfile(
    val engine: PlaybackEngine,
    val decoder: DecoderType,
    val adaptive: Boolean,
    val downmixStereo: Boolean,
    val normalizeVolume: Boolean,
    val amlogicFix: Boolean,
    val window: BufferWindow,
    val byteBudget: ByteBudget?,
    val native: NativeCache,
    val abr: Boolean,
    val bandwidthCap: BandwidthCap,
    val maxResolution: ResolutionCap,
    val segmentRetries: Int,
    val userAgent: String,
    val headers: Map<String, String>
) {
    /**
     * What a rebuild is allowed to depend on. Buffer settings sit here because
     * DefaultLoadControl is fixed at construction; UA and headers join them
     * only for the native engines, which receive both at construction, while
     * Media3 applies them to the next data source in place. VLC also takes
     * [maxResolution] at construction (its adaptive module has no runtime
     * setter); MPV has no per-rendition resolution knob, so a quality change
     * there costs no rebuild. [adaptive] is derived from the URL, not Settings,
     * and the window already encodes it.
     */
    val rebuildKey: List<Any?> = buildList {
        add(engine)
        add(decoder)
        add(downmixStereo)
        add(normalizeVolume)
        add(amlogicFix)
        add(window)
        add(byteBudget)
        add(native)
        if (engine == PlaybackEngine.VLC || engine == PlaybackEngine.MPV) {
            add(userAgent)
            add(headers)
        }
        if (engine == PlaybackEngine.VLC) add(maxResolution)
    }

    val memoryBounded: Boolean get() = byteBudget != null
}

/**
 * The selector's video ceiling. The source-quality setting picks the height
 * (its tier and everything above it are excluded); both axes are then clamped
 * so a 4K ladder never hands this device more than 1080p. The decoder, not
 * the display, is the binding constraint on a 32-bit box.
 */
internal data class ResolutionCap(val maxWidth: Int, val maxHeight: Int)

internal const val SELECTOR_MAX_WIDTH = 1920
internal const val SELECTOR_MAX_HEIGHT = 1080

/** Media read retries before the stream error policy gives up. */
internal const val SEGMENT_RETRIES = 4

internal fun resolutionCap(limit: QualityLimit): ResolutionCap {
    val height = if (limit.cutoffHeight > 0) limit.cutoffHeight - 1 else SELECTOR_MAX_HEIGHT
    return ResolutionCap(SELECTOR_MAX_WIDTH, minOf(height, SELECTOR_MAX_HEIGHT))
}

/**
 * Resolves the profile for one playback start. [tier]/[lowLatency]/
 * [highCapacityVod] are separate from [settings] so the player can pass the
 * settled buffer selection while the Settings UI passes the live values.
 * [forceBuiltIn] covers the native-engine fallback: the profile then names the
 * engine that will actually run, so its rebuild key stays truthful.
 */
internal fun playbackProfile(
    settings: AppSettings,
    decoder: DecoderType,
    adaptive: Boolean,
    lowRam: Boolean,
    tier: BufferTier = settings.bufferTier,
    lowLatency: Boolean = settings.lowLatency,
    highCapacityVod: Boolean = settings.highCapacityBuffer,
    headers: Map<String, String> = emptyMap(),
    forceBuiltIn: Boolean = false
): PlaybackProfile = PlaybackProfile(
    engine = if (forceBuiltIn) PlaybackEngine.BUILT_IN else settings.playbackEngine,
    decoder = decoder,
    adaptive = adaptive,
    downmixStereo = settings.forceStereoDownmix,
    normalizeVolume = settings.normalizeVolume,
    amlogicFix = settings.amlogicFix,
    window = bufferWindow(tier, adaptive, lowLatency, lowRam, highCapacityVod),
    byteBudget = if (lowRam) {
        byteBudget(
            if (adaptive) LOW_RAM_ADAPTIVE_BUFFER_BYTES else LOW_RAM_PROGRESSIVE_BUFFER_BYTES
        )
    } else {
        null
    },
    native = nativeCache(tier, highCapacityVod = highCapacityVod && !adaptive),
    abr = settings.abrEnabled,
    bandwidthCap = settings.bandwidthCap,
    maxResolution = resolutionCap(settings.qualityLimit),
    segmentRetries = SEGMENT_RETRIES,
    userAgent = settings.userAgentString,
    headers = headers
)

/** The resolved buffer row for Settings and the stats panel. */
internal fun describeBuffer(profile: PlaybackProfile): String {
    val window = profile.window
    val forward = if (window.minBufferMs == window.maxBufferMs) {
        "window ${seconds(window.minBufferMs)} s"
    } else {
        "min ${seconds(window.minBufferMs)} s · max ${seconds(window.maxBufferMs)} s"
    }
    return "$forward · start ${seconds(window.bufferForPlaybackMs)} s · " +
        "re-buffer ${seconds(window.bufferForPlaybackAfterRebufferMs)} s"
}

/** The byte cap actually handed to the loader, or "unbounded". */
internal fun describeMemoryBudget(profile: PlaybackProfile): String =
    profile.byteBudget?.let(::describeByteBudget) ?: "unbounded (time-prioritised)"

internal fun describeNativeCache(profile: PlaybackProfile): String =
    "${profile.native.mpvCacheSeconds} s MPV · ${seconds(profile.native.vlcCachingMs)} s VLC"
