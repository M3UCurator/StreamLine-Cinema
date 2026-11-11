package com.streamline.cinema.ui.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
 * exponential backoff, but lets a definitive HTTP 4xx fail immediately so the
 * player can fail over to another source instead of hammering a dead CDN.
 */
internal class StreamLoadErrorPolicy(minRetries: Int) :
    DefaultLoadErrorHandlingPolicy(minRetries) {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        if (error is HttpDataSource.InvalidResponseCodeException) {
            val code = error.responseCode
            if (code in 400..499 && code != 408 && code != 429) return C.TIME_UNSET
        }
        val exponent = (loadErrorInfo.errorCount - 1).coerceIn(0, 4)
        return (500L shl exponent).coerceAtMost(8_000L)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        if (dataType == C.DATA_TYPE_MEDIA) 4 else 6
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

internal fun adaptiveMimeType(url: String?): String? {
    val clean = url?.substringBefore('?')?.substringBefore('#')?.lowercase() ?: return null
    val path = clean.substringAfterLast('/', "")
    return when {
        clean.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
        clean.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
        isSmoothStreamingPath(path) -> MimeTypes.APPLICATION_SS
        else -> null
    }
}
