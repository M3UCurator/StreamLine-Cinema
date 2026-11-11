package com.streamline.cinema.ui.player

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ToFloatPcmAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Renderer stack built around the Jellyfin FFmpeg extension:
 *
 * - Audio runs in EXTENSION_RENDERER_MODE_PREFER, so the FFmpeg decoder handles
 *   AC-3, E-AC-3 and DTS (including 5.1/7.1) before MediaCodec. MediaCodec
 *   audio decoders frequently advertise those codecs on TV boxes but fail or
 *   mute at runtime.
 * - Video stays hardware-first: platform MediaCodec renderers are registered
 *   before the FFmpeg software renderer, which is only a last-resort fallback
 *   for H.264/HEVC/AV1 when no hardware decoder exists.
 * - When [downmixStereo] is set every track wider than two channels is folded
 *   to stereo with constant-power matrices.
 * - When [normalizeVolume] is set every decoded track runs through an
 *   acompressor-style loudness normalizer. Both processors need PCM, so
 *   encoded passthrough is withdrawn whenever either is enabled.
 */
internal class CinemaRenderersFactory(
    context: Context,
    private val downmixStereo: Boolean,
    private val normalizeVolume: Boolean
) : DefaultRenderersFactory(context) {

    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): androidx.media3.exoplayer.audio.AudioSink {
        if (!downmixStereo && !normalizeVolume) {
            return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                ?: DefaultAudioSink.Builder(context).build()
        }
        val processors = ArrayList<AudioProcessor>()
        if (downmixStereo) processors += buildDownmixProcessor()
        if (normalizeVolume) {
            Log.i(TAG, "Volume normalizer active · acompressor curve · -18 dB threshold · 4:1 · 6 dB makeup")
            // The normalizer works on float PCM; the converter is a no-op when
            // the sink already delivers float.
            processors += ToFloatPcmAudioProcessor()
            processors += NormalizerAudioProcessor()
            // The sink's own processors (silence skipping) only accept 16-bit,
            // so the chain drops back to 16-bit after the normalizer.
            processors += ToInt16PcmAudioProcessor()
        }
        return DefaultAudioSink.Builder(context)
            .setAudioProcessors(processors.toTypedArray())
            // Both processors need decoded PCM, so encoded passthrough is
            // withdrawn; multichannel PCM itself stays allowed.
            .setAudioCapabilities(
                AudioCapabilities(
                    intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_8BIT, C.ENCODING_PCM_FLOAT),
                    8
                )
            )
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }

    private fun buildDownmixProcessor(): ChannelMixingAudioProcessor {
        val downmix = ChannelMixingAudioProcessor()
        // The processor rejects any input count without a matrix, so mono and
        // stereo get identity matrices; 3..6 fold down via constant power.
        for (channels in 1..6) {
            downmix.putChannelMixingMatrix(ChannelMixingMatrix.createForConstantPower(channels, 2))
        }
        downmix.putChannelMixingMatrix(
            ChannelMixingMatrix(
                7,
                2,
                floatArrayOf(
                    1f, 0f, 0.7071f, 0.5f, 0.7071f, 0f, 0.5f,
                    0f, 1f, 0.7071f, 0.5f, 0f, 0.7071f, 0.5f
                )
            )
        )
        downmix.putChannelMixingMatrix(
            ChannelMixingMatrix(
                8,
                2,
                floatArrayOf(
                    1f, 0f, 0.7071f, 0.5f, 0.7071f, 0f, 0.7071f, 0f,
                    0f, 1f, 0.7071f, 0.5f, 0f, 0.7071f, 0f, 0.7071f
                )
            )
        )
        return downmix
    }

    /**
     * Video is not extension-preferred: passing EXTENSION_RENDERER_MODE_ON keeps
     * the MediaCodec renderers in front and only appends Jellyfin's FFmpeg video
     * renderer as the final software fallback.
     */
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        super.buildVideoRenderers(
            context,
            EXTENSION_RENDERER_MODE_ON,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out
        )
    }

    companion object {
        private const val TAG = "StreamLine:audio"

        fun logFfmpegAvailability() {
            val available = runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)
            val version = if (available) {
                runCatching { FfmpegLibrary.getVersion() }.getOrNull()
            } else {
                null
            }
            Log.i(
                TAG,
                if (available) {
                    "FFmpeg decoder extension loaded · ${version ?: "version unknown"} · " +
                        "AC-3/E-AC-3/DTS/HEVC/AV1 available"
                } else {
                    "FFmpeg decoder extension unavailable · falling back to platform decoders"
                }
            )
        }
    }
}
