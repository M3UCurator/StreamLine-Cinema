package com.streamline.cinema.ui.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Loudness normalizer modelled on FFmpeg's `acompressor` filter: downward
 * compression with an RMS detector, averaged channel linking and a soft knee,
 * followed by makeup gain. The Jellyfin FFmpeg extension shipped with the app
 * is built without libavfilter, so the same static transfer curve runs here as
 * a streaming processor on float PCM instead of an avfilter graph.
 */
internal class NormalizerAudioProcessor : BaseAudioProcessor() {

    private var channels = 0
    private var attackCoefficient = 0f
    private var releaseCoefficient = 0f
    private var envelope = 1f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT ||
            inputAudioFormat.channelCount <= 0 ||
            inputAudioFormat.sampleRate <= 0
        ) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        attackCoefficient = exp(-1.0 / (ATTACK_SECONDS * inputAudioFormat.sampleRate)).toFloat()
        releaseCoefficient = exp(-1.0 / (RELEASE_SECONDS * inputAudioFormat.sampleRate)).toFloat()
        envelope = 1f
        return inputAudioFormat
    }

    override fun onFlush() {
        envelope = 1f
    }

    override fun onReset() {
        envelope = 1f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / (Float.SIZE_BYTES * channels)
        if (frames <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val input = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val outputBuffer = replaceOutputBuffer(frames * channels * Float.SIZE_BYTES)
        val output = outputBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer()

        val start = input.position()
        for (frame in 0 until frames) {
            val base = start + frame * channels
            var sum = 0.0
            for (channel in 0 until channels) {
                val sample = input.get(base + channel).toDouble()
                sum += sample * sample
            }
            // RMS across every channel (acompressor's default detector) with
            // average linking, so one loud channel does not duck the others.
            val target = targetGain(sqrt(sum / channels).toFloat())
            envelope = if (target < envelope) {
                attackCoefficient * envelope + (1f - attackCoefficient) * target
            } else {
                releaseCoefficient * envelope + (1f - releaseCoefficient) * target
            }
            for (channel in 0 until channels) {
                input.get(base + channel).let { sample ->
                    output.put(base + channel, (sample * envelope).coerceIn(-1f, 1f))
                }
            }
        }
        outputBuffer.position(frames * channels * Float.SIZE_BYTES)
        outputBuffer.flip()
        inputBuffer.position(inputBuffer.limit())
    }

    /** Soft-knee downward compressor curve in dB, plus the makeup gain. */
    private fun targetGain(level: Float): Float {
        val db = 20.0 * log10(level.coerceAtLeast(1e-6f).toDouble())
        val over = db - THRESHOLD_DB
        val gainDb = when {
            over <= -KNEE_DB / 2 -> 0.0
            over >= KNEE_DB / 2 -> over * (1.0 / RATIO - 1.0)
            else -> {
                val kneeOffset = over + KNEE_DB / 2
                (1.0 / RATIO - 1.0) * kneeOffset * kneeOffset / (2.0 * KNEE_DB)
            }
        }
        return 10.0.pow((gainDb + MAKEUP_DB) / 20.0).toFloat()
    }

    private companion object {
        // acompressor options: threshold=-18 dB, ratio=4, attack=10 ms,
        // release=250 ms, knee=6 dB, makeup=+6 dB, mode=downward.
        const val THRESHOLD_DB = -18.0
        const val RATIO = 4.0
        const val ATTACK_SECONDS = 0.010
        const val RELEASE_SECONDS = 0.250
        const val KNEE_DB = 6.0
        const val MAKEUP_DB = 6.0
    }
}
