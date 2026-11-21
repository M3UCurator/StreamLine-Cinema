package com.streamline.cinema.ui.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.util.Locale

/**
 * Reports the decoder Media3 actually initialised, not the mode that was
 * requested. The settings only say HW/HW+/AUTO/SW, so a c2.amlogic.* hardware
 * decoder and a c2.android.* software fallback look identical in the OSD until
 * playback stutters; naming the codec, its profile string and the HDR transfer
 * also explains files that decode audio but never paint a frame.
 */
@OptIn(UnstableApi::class)
internal class DecoderProbe : AnalyticsListener {

    @Volatile var videoDecoder: String? = null
        private set

    @Volatile var audioDecoder: String? = null
        private set

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        videoDecoder = decoderName
        Log.i(TAG, "Video decoder · $decoderName · init ${initializationDurationMs} ms")
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        audioDecoder = decoderName
        Log.i(TAG, "Audio decoder · $decoderName · init ${initializationDurationMs} ms")
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
        Log.i(TAG, "Video track · ${describeVideo(format)}")
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
        Log.i(TAG, "Audio track · ${describeAudio(format)}")
    }

    // A stream that reports a decoder, a format and audio progress but never
    // this line is the black-picture case: the decoder is running, the surface
    // is not getting frames.
    override fun onRenderedFirstFrame(
        eventTime: AnalyticsListener.EventTime,
        output: Any,
        renderTimeMs: Long
    ) {
        Log.i(TAG, "First frame rendered in $renderTimeMs ms · ${videoDecoder ?: "decoder"}")
    }

    override fun onVideoCodecError(
        eventTime: AnalyticsListener.EventTime,
        videoCodecError: Exception
    ) {
        Log.w(TAG, "Video codec error · ${videoDecoder ?: "decoder"} · ${videoCodecError.message}")
    }

    override fun onAudioCodecError(
        eventTime: AnalyticsListener.EventTime,
        audioCodecError: Exception
    ) {
        Log.w(TAG, "Audio codec error · ${audioDecoder ?: "decoder"} · ${audioCodecError.message}")
    }

    companion object {
        private const val TAG = "StreamLine:codec"

        fun describeVideo(format: Format): String = buildString {
            append(format.codecs ?: format.sampleMimeType ?: "unknown codec")
            if (format.width > 0 && format.height > 0) {
                append(" · ${format.width}×${format.height}")
            }
            if (format.frameRate > 0f) {
                append(String.format(Locale.US, " @%.3g fps", format.frameRate))
            }
            if (format.bitrate > 0) append(" · ${format.bitrate / 1_000} kbps")
            hdrLabel(format.colorInfo?.colorTransfer)?.let { append(" · $it") }
        }

        fun describeAudio(format: Format): String = buildString {
            append(format.codecs ?: format.sampleMimeType ?: "unknown codec")
            if (format.channelCount > 0) append(" · ${format.channelCount} ch")
            if (format.sampleRate > 0) append(" · ${format.sampleRate} Hz")
            if (format.bitrate > 0) append(" · ${format.bitrate / 1_000} kbps")
        }

        private fun hdrLabel(colorTransfer: Int?): String? = when (colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"
            C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
    }
}
