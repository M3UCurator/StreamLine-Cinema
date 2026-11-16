package com.streamline.cinema.ui.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.streamline.cinema.data.StreamKind
import com.streamline.cinema.data.StreamUrls

// Multiplier on the playlist's target duration before a non-advancing live
// playlist is declared stuck (Media3's own default is 3.5).
private const val PLAYLIST_STUCK_TARGET_DURATIONS = 10.0

/**
 * Picks the explicit Media3 source for each URL instead of relying on the
 * DefaultMediaSourceFactory heuristic: HLS and DASH get their own factories and
 * data source (bounded chunk read timeout), everything else stays progressive.
 */
internal class AdaptiveMediaSourceFactory(
    private val progressive: MediaSource.Factory,
    private val hls: MediaSource.Factory,
    private val dash: MediaSource.Factory,
    private val smoothStreaming: MediaSource.Factory,
    private val subtitleDataSource: DataSource.Factory,
    private var loadErrorPolicy: LoadErrorHandlingPolicy
) : MediaSource.Factory {

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider
    ): MediaSource.Factory {
        progressive.setDrmSessionManagerProvider(drmSessionManagerProvider)
        hls.setDrmSessionManagerProvider(drmSessionManagerProvider)
        dash.setDrmSessionManagerProvider(drmSessionManagerProvider)
        smoothStreaming.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy
    ): MediaSource.Factory {
        loadErrorPolicy = loadErrorHandlingPolicy
        progressive.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        hls.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        dash.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        smoothStreaming.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun setCmcdConfigurationFactory(
        cmcdConfigurationFactory: CmcdConfiguration.Factory
    ): MediaSource.Factory {
        // CMCD applies to every adaptive factory we build.
        progressive.setCmcdConfigurationFactory(cmcdConfigurationFactory)
        hls.setCmcdConfigurationFactory(cmcdConfigurationFactory)
        dash.setCmcdConfigurationFactory(cmcdConfigurationFactory)
        smoothStreaming.setCmcdConfigurationFactory(cmcdConfigurationFactory)
        return this
    }

    override fun getSupportedTypes(): IntArray = progressive.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val mimeType = mediaItem.localConfiguration?.mimeType
        val path = mediaItem.localConfiguration?.uri?.lastPathSegment?.lowercase()
        return when {
            mimeType == MimeTypes.APPLICATION_M3U8 || path?.endsWith(".m3u8") == true ->
                mergeSubtitles(hls.createMediaSource(mediaItem), mediaItem)
            mimeType == MimeTypes.APPLICATION_MPD || path?.endsWith(".mpd") == true ->
                mergeSubtitles(dash.createMediaSource(mediaItem), mediaItem)
            mimeType == MimeTypes.APPLICATION_SS || isSmoothStreamingPath(path) ->
                mergeSubtitles(smoothStreaming.createMediaSource(mediaItem), mediaItem)
            else -> progressive.createMediaSource(mediaItem)
        }
    }

    /**
     * Only DefaultMediaSourceFactory merges MediaItem.SubtitleConfigurations; the
     * HLS/DASH/Smooth factories ignore them, so adaptive sources have to carry
     * their side-loaded tracks here or captions are attached but never fetched.
     */
    private fun mergeSubtitles(source: MediaSource, mediaItem: MediaItem): MediaSource {
        val configs = mediaItem.localConfiguration?.subtitleConfigurations.orEmpty()
        if (configs.isEmpty()) return source
        val factory = SingleSampleMediaSource.Factory(subtitleDataSource)
            .setLoadErrorHandlingPolicy(loadErrorPolicy)
        val sources = ArrayList<MediaSource>(configs.size + 1)
        sources.add(source)
        configs.forEach { config ->
            sources.add(factory.createMediaSource(config, C.TIME_UNSET))
        }
        return MergingMediaSource(*sources.toTypedArray())
    }
}

/**
 * Retries transport failures (timeouts, resets, truncated chunks) with an
 * exponential backoff, but fails fast on outcomes a retry cannot cure: a
 * definitive HTTP 4xx, or a manifest/container the demuxer does not support.
 * Malformed bytes usually mean a truncated transfer, so they keep one retry
 * before the source is declared dead; after that the panel/failover path owns
 * it instead of grinding through the whole transport budget.
 */
internal class StreamLoadErrorPolicy(minRetries: Int) :
    DefaultLoadErrorHandlingPolicy(minRetries) {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        val status = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        return streamRetryDelayMs(status, error as? ParserException, loadErrorInfo.errorCount)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        if (dataType == C.DATA_TYPE_MEDIA) 4 else 6
}

/**
 * The retry decision behind [StreamLoadErrorPolicy], kept pure so it can be
 * unit tested: `C.TIME_UNSET` means the load is final, otherwise the delay
 * before the next attempt.
 */
internal fun streamRetryDelayMs(
    httpStatusCode: Int?,
    parserError: ParserException?,
    errorCount: Int
): Long {
    // A throttled provider wants fewer requests, not faster ones. Retrying
    // inside the loader every 500 ms only refreshes its negative cache
    // (Flussonic bans the token for two minutes), so 429 is final here and
    // the player-level backoff in the OSD waits the limit out.
    if (httpStatusCode == 429) return C.TIME_UNSET
    if (httpStatusCode != null &&
        httpStatusCode in 400..499 &&
        httpStatusCode != 408
    ) {
        return C.TIME_UNSET
    }
    if (parserError != null) {
        // Legal syntax this demuxer does not implement (including an
        // unrecognised container) cannot become readable on a retry.
        if (!parserError.contentIsMalformed) return C.TIME_UNSET
        // Malformed bytes are usually a truncated transfer: one retry is
        // worth it, after that the source itself is broken.
        if (errorCount > 1) return C.TIME_UNSET
    }
    val exponent = (errorCount - 1).coerceIn(0, 4)
    return (500L shl exponent).coerceAtMost(8_000L)
}

internal fun adaptiveSourceFactories(
    progressiveDataSource: androidx.media3.datasource.DataSource.Factory,
    adaptiveDataSource: androidx.media3.datasource.DataSource.Factory,
    policy: LoadErrorHandlingPolicy
): MediaSource.Factory {
    val progressive = DefaultMediaSourceFactory(progressiveDataSource)
        .setLoadErrorHandlingPolicy(policy)
    val hls = HlsMediaSource.Factory(adaptiveDataSource)
        .setLoadErrorHandlingPolicy(policy)
        // Chunkless preparation drives ABR from the master playlist immediately
        // instead of waiting for the first segment to reveal the renditions.
        .setAllowChunklessPreparation(true)
        // Media3 calls a non-advancing playlist stuck after 3.5 target
        // durations, which is 7 s on a 2 s-segment live channel: a couple of
        // slow refreshes then end a healthy stream as ERROR_CODE_TIMEOUT. Ten
        // durations give a short-window CDN room to catch up, while the 15 s
        // stall watchdog still catches a playhead that really stopped.
        .setPlaylistTrackerFactory { dataSource, errorPolicy, parser, cmcd, executor ->
            DefaultHlsPlaylistTracker(
                dataSource,
                errorPolicy,
                parser,
                cmcd,
                PLAYLIST_STUCK_TARGET_DURATIONS,
                executor
            )
        }
    val dash = DashMediaSource.Factory(adaptiveDataSource)
        .setLoadErrorHandlingPolicy(policy)
    val smoothStreaming = SsMediaSource.Factory(adaptiveDataSource)
        .setLoadErrorHandlingPolicy(policy)
    return AdaptiveMediaSourceFactory(progressive, hls, dash, smoothStreaming, adaptiveDataSource, policy)
}

private fun isSmoothStreamingPath(path: String?): Boolean =
    path == "manifest" || path?.endsWith(".ism") == true || path?.endsWith(".ismc") == true

internal fun adaptiveMimeType(url: String?, kind: StreamKind? = null): String? {
    when (kind) {
        StreamKind.HLS -> return MimeTypes.APPLICATION_M3U8
        StreamKind.DASH -> return MimeTypes.APPLICATION_MPD
        StreamKind.SMOOTH -> return MimeTypes.APPLICATION_SS
        null -> Unit
    }
    return StreamUrls.kindOf(url)?.media3Mime()
}

/** Exact Media3 constant; DefaultMediaSourceFactory compares case-sensitively. */
internal fun StreamKind.media3Mime(): String = when (this) {
    StreamKind.HLS -> MimeTypes.APPLICATION_M3U8
    StreamKind.DASH -> MimeTypes.APPLICATION_MPD
    StreamKind.SMOOTH -> MimeTypes.APPLICATION_SS
}
