package com.streamline.cinema.ui.player

import android.content.Context
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioCapabilities

internal data class AudioAudit(
    val codec: String,
    val channels: Int,
    val sinkChannels: Int,
    val passthrough: Boolean,
    val downmix: Boolean,
    val normalize: Boolean,
    val ffmpegDecode: Boolean
) {
    val path: String
        get() {
            val base = when {
                downmix && channels > 2 -> "stereo downmix"
                passthrough -> "passthrough"
                ffmpegDecode -> if (channels > 2) "FFmpeg · $channels ch PCM" else "FFmpeg decode"
                else -> "platform decode"
            }
            return if (normalize) "$base + normalize" else base
        }

    val warning: String?
        get() = when {
            downmix || passthrough -> null
            sinkChannels in 1..2 && channels > 2 ->
                "sink handles $sinkChannels ch · enable Force stereo downmix"
            channels > sinkChannels && sinkChannels > 2 ->
                "$channels ch exceeds sink ($sinkChannels ch)"
            else -> null
        }

    val summary: String
        get() = buildString {
            append(path)
            if (channels > 0) append(" · ").append(channels).append(" ch")
            if (sinkChannels > 0) append(" · sink ").append(sinkChannels).append(" ch")
        }
}

/**
 * Forces an explicit decision for multichannel audio: encoded passthrough is
 * only kept when the active output advertises the codec, otherwise the track
 * is decoded (FFmpeg for AC-3/E-AC-3/DTS/TrueHD) and may need the stereo
 * downmix sink. Every decision is logged so a silent movie can be traced to
 * either a decoder or an output-capability problem.
 */
internal object AudioAuditProbe {
    private const val TAG = "StreamLine:audio"

    private val ffmpegCodecs = setOf(
        MimeTypes.AUDIO_AC3,
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_HD,
        MimeTypes.AUDIO_TRUEHD
    )

    fun inspect(context: Context, format: Format?, downmix: Boolean, normalize: Boolean): AudioAudit? {
        if (format == null) return null
        val capabilities = runCatching { AudioCapabilities.getCapabilities(context) }
            .getOrDefault(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
        val passthrough = !downmix && !normalize &&
            runCatching { capabilities.isPassthroughPlaybackSupported(format) }.getOrDefault(false)
        return AudioAudit(
            codec = format.sampleMimeType?.substringAfter('/')?.uppercase() ?: "unknown",
            channels = format.channelCount,
            sinkChannels = capabilities.maxChannelCount,
            passthrough = passthrough,
            downmix = downmix,
            normalize = normalize,
            ffmpegDecode = format.sampleMimeType in ffmpegCodecs
        )
    }

    fun describeSink(context: Context, downmix: Boolean, normalize: Boolean): String {
        val capabilities = runCatching { AudioCapabilities.getCapabilities(context) }
            .getOrDefault(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
        return buildString {
            append(
                when {
                    downmix && normalize -> "downmix + normalize sink"
                    downmix -> "stereo downmix sink"
                    normalize -> "normalize sink"
                    else -> "native sink"
                }
            )
            append(" · ").append(capabilities.maxChannelCount).append(" ch")
            for (encoding in PASSTHROUGH_LABELS.keys) {
                if (capabilities.supportsEncoding(encoding)) {
                    append(" · ").append(PASSTHROUGH_LABELS.getValue(encoding))
                }
            }
        }
    }

    fun log(audit: AudioAudit?) {
        val active = audit ?: return
        Log.i(
            TAG,
            "Pass-through audit · ${active.codec} · ${active.channels} ch · " +
                "sink ${active.sinkChannels} ch · passthrough=${active.passthrough} · " +
                "downmix=${active.downmix} · path=${active.path}" +
                (active.warning?.let { " · warning=$it" } ?: "")
        )
    }

    private val PASSTHROUGH_LABELS: Map<Int, String> = mapOf(
        androidx.media3.common.C.ENCODING_AC3 to "AC-3",
        androidx.media3.common.C.ENCODING_E_AC3 to "E-AC-3",
        androidx.media3.common.C.ENCODING_DTS to "DTS",
        androidx.media3.common.C.ENCODING_DTS_HD to "DTS-HD"
    )
}
