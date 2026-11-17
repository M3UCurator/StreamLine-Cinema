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
 * The selector's video ceiling. The source-quality setting picks the height and
 * the tier plus everything below it play; the width follows the widest common
 * rendition at that height so an ultrawide master is not excluded by its width
 * alone. With the limit off the ceiling is 8K, the highest tier the source
 * labels detect.
 */
internal data class ResolutionCap(val maxWidth: Int, val maxHeight: Int)

internal const val SELECTOR_MAX_WIDTH = 7680
internal const val SELECTOR_MAX_HEIGHT = 4320

/** Media read retries before the stream error policy gives up. */
internal const val SEGMENT_RETRIES = 4

internal fun resolutionCap(limit: QualityLimit): ResolutionCap {
    val height = if (limit.cutoffHeight > 0) {
        minOf(limit.cutoffHeight, SELECTOR_MAX_HEIGHT)
    } else {
        SELECTOR_MAX_HEIGHT
    }
    return ResolutionCap(widthFor(height), height)
}

private fun widthFor(height: Int): Int = when {
    height >= SELECTOR_MAX_HEIGHT -> SELECTOR_MAX_WIDTH
    height >= 2160 -> 4096
    height >= 1080 -> 2560
    height >= 720 -> 1920
    else -> 854
}

/**
 * Resolves the profile for one playback start. [tier]/[lowLatency]/
 * [highCapacityVod] are separate from [settings] so the player can pass the
 * settled buffer selection while the Settings UI passes the live values.
 * [forceBuiltIn] covers the native-engine fallback: the profile then names the
 * engine that will actually run, so its rebuild key stays truthful.
 * [maxMemoryBytes] is injectable so tests resolve the same budget on any host.
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
    forceBuiltIn: Boolean = false,
    maxMemoryBytes: Long = Runtime.getRuntime().maxMemory()
): PlaybackProfile = PlaybackProfile(
    engine = if (forceBuiltIn) PlaybackEngine.BUILT_IN else settings.playbackEngine,
    decoder = decoder,
    adaptive = adaptive,
    downmixStereo = settings.forceStereoDownmix,
    normalizeVolume = settings.normalizeVolume,
    amlogicFix = settings.amlogicFix,
    window = bufferWindow(tier, adaptive, lowLatency, lowRam, highCapacityVod),
    byteBudget = resolveByteBudget(adaptive, lowRam, maxMemoryBytes),
    native = nativeCache(tier, highCapacityVod = highCapacityVod && !adaptive),
    abr = settings.abrEnabled,
    bandwidthCap = settings.bandwidthCap,
    maxResolution = resolutionCap(settings.qualityLimit),
    segmentRetries = SEGMENT_RETRIES,
    userAgent = settings.userAgentString,
    headers = headers
)

/**
 * A byte ceiling for every device, not only the low-RAM profile. Media3 leaves
 * the target byte size unset, so a time-bounded loader can grow until its
 * ~200 MiB allocation chunk, which is most of the heap on a 32-bit box that
 * does not declare itself low-RAM. The normal preset is exposed only when the
 * heap share actually clamps it, so a roomy device keeps its time-prioritised
 * loader while a tight one gets the byte bound and the back-buffer trim.
 */
private fun resolveByteBudget(
    adaptive: Boolean,
    lowRam: Boolean,
    maxMemoryBytes: Long
): ByteBudget? = if (lowRam) {
    byteBudget(
        presetBytes = if (adaptive) {
            LOW_RAM_ADAPTIVE_BUFFER_BYTES
        } else {
            LOW_RAM_PROGRESSIVE_BUFFER_BYTES
        },
        maxMemoryBytes = maxMemoryBytes,
        heapDivisor = LOW_RAM_HEAP_DIVISOR
    )
} else {
    byteBudget(
        presetBytes = if (adaptive) {
            NORMAL_ADAPTIVE_BUFFER_BYTES
        } else {
            NORMAL_PROGRESSIVE_BUFFER_BYTES
        },
        maxMemoryBytes = maxMemoryBytes,
        heapDivisor = NORMAL_HEAP_DIVISOR
    ).takeIf { it.clamped }
}

/** The resolved buffer row for Settings and the stats panel. */
internal fun describeBuffer(profile: PlaybackProfile): String {
    // The window and the byte budget are planned together, so report the window
    // the loader will actually be built with, not the tier's request.
    val window = planBuffer(
        profile.window,
        profile.byteBudget,
        profile.bandwidthCap.bitsPerSecond
    ).window
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
