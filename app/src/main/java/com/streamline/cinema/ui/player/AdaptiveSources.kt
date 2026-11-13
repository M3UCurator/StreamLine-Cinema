package com.streamline.cinema.ui.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.streamline.cinema.data.StreamKind
import com.streamline.cinema.data.StreamUrls

/**
 * Picks the explicit Media3 source for each URL instead of relying on the
 * DefaultMediaSourceFactory heuristic: HLS and DASH get their own factories and
 * data source (bounded chunk read timeout), everything else stays progressive.
 */
internal class AdaptiveMediaSourceFactory(
    private val progressive: MediaSource.Factory,
    private val hls: MediaSource.Factory,
    private val dash: MediaSource.Factory,
    private val smoothStreaming: MediaSource.Factory
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
                hls.createMediaSource(mediaItem)
            mimeType == MimeTypes.APPLICATION_MPD || path?.endsWith(".mpd") == true ->
                dash.createMediaSource(mediaItem)
            mimeType == MimeTypes.APPLICATION_SS || isSmoothStreamingPath(path) ->
                smoothStreaming.createMediaSource(mediaItem)
            else -> progressive.createMediaSource(mediaItem)
        }
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
    if (httpStatusCode != null &&
        httpStatusCode in 400..499 &&
        httpStatusCode != 408 &&
        httpStatusCode != 429
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
    val dash = DashMediaSource.Factory(adaptiveDataSource)
        .setLoadErrorHandlingPolicy(policy)
    val smoothStreaming = SsMediaSource.Factory(adaptiveDataSource)
        .setLoadErrorHandlingPolicy(policy)
    return AdaptiveMediaSourceFactory(progressive, hls, dash, smoothStreaming)
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
