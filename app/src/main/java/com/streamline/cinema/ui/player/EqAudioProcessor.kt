package com.streamline.cinema.ui.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Band centres of the ten-band curve every preset is written against, in Hz. */
internal val EQ_BAND_HZ = listOf(70f, 180f, 320f, 600f, 1000f, 3000f, 6000f, 12000f, 14000f, 16000f)

internal fun eqBandLabel(hz: Float): String = when {
    hz < 1000f -> "${hz.toInt()} Hz"
    else -> {
        val khz = hz / 1000f
        if (khz % 1f == 0f) "${khz.toInt()} kHz"
        else String.format(Locale.US, "%.1f kHz", khz)
    }
}

internal val EQ_BAND_LABELS: List<String> = EQ_BAND_HZ.map(::eqBandLabel)

/**
 * Band gains shared between the OSD and the audio sink. Media3 is handed an
 * immutable snapshot per buffer, so a slider move lands on the next buffer
 * boundary and never touches the audio session, the AudioTrack, or the
 * hardware effect chain.
 */
internal class EqState {
    class Snapshot(val gainsDb: FloatArray, val generation: Int)

    @Volatile private var current = Snapshot(FloatArray(EQ_BAND_HZ.size), 0)

    val snapshot: Snapshot get() = current

    fun update(gainsDb: FloatArray) {
        current = Snapshot(gainsDb.copyOf(), current.generation + 1)
    }
}

/**
 * Peaking-biquad equalizer running on float PCM inside the player. The device
 * `Equalizer` effect used to run here; on TV-class audio HALs attaching or
 * re-enabling that effect mid-playback tears down the output and stalls the
 * player, so shaping happens in-process instead.
 */
internal class EqAudioProcessor(private val state: EqState) : BaseAudioProcessor() {

    private var channels = 0
    private var sampleRate = 0
    private var appliedGeneration = -1
    private val filters = Array(EQ_BAND_HZ.size) { BiquadBand() }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT ||
            inputAudioFormat.channelCount <= 0 ||
            inputAudioFormat.sampleRate <= 0
        ) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        appliedGeneration = -1
        filters.forEach { it.reset(channels) }
        return inputAudioFormat
    }

    override fun onFlush() {
        appliedGeneration = -1
        filters.forEach { it.reset(channels) }
    }

    override fun onReset() {
        appliedGeneration = -1
        filters.forEach { it.reset(channels) }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / (Float.SIZE_BYTES * channels)
        if (frames <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val snapshot = state.snapshot
        if (snapshot.generation != appliedGeneration) {
            filters.forEachIndexed { index, filter ->
                filter.setPeaking(EQ_BAND_HZ[index], snapshot.gainsDb.getOrElse(index) { 0f }, sampleRate)
            }
            appliedGeneration = snapshot.generation
        }
        val input = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val outputBuffer = replaceOutputBuffer(frames * channels * Float.SIZE_BYTES)
        val output = outputBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer()

        val start = input.position()
        for (frame in 0 until frames) {
            val base = start + frame * channels
            for (channel in 0 until channels) {
                var sample = input.get(base + channel)
                for (filter in filters) sample = filter.process(channel, sample)
                output.put(base + channel, sample.coerceIn(-1f, 1f))
            }
        }
        outputBuffer.position(frames * channels * Float.SIZE_BYTES)
        outputBuffer.flip()
        inputBuffer.position(inputBuffer.limit())
    }

    /** Direct-form-II transposed biquad, one state pair per output channel. */
    private class BiquadBand {
        private var b0 = 1f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f
        private var z1 = FloatArray(0)
        private var z2 = FloatArray(0)

        fun reset(channels: Int) {
            z1 = FloatArray(channels)
            z2 = FloatArray(channels)
        }

        fun setPeaking(centerHz: Float, gainDb: Float, sampleRate: Int) {
            if (gainDb == 0f) {
                b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
                return
            }
            val a = 10.0.pow(gainDb / 40.0).toFloat()
            val w0 = 2.0 * PI * centerHz / sampleRate
            val cosW = cos(w0).toFloat()
            val alpha = (sin(w0) / (2.0 * Q)).toFloat()
            val a0 = 1f + alpha / a
            b0 = (1f + alpha * a) / a0
            b1 = -2f * cosW / a0
            b2 = (1f - alpha * a) / a0
            a1 = -2f * cosW / a0
            a2 = (1f - alpha / a) / a0
        }

        fun process(channel: Int, input: Float): Float {
            val out = b0 * input + z1[channel]
            z1[channel] = b1 * input - a1 * out + z2[channel]
            z2[channel] = b2 * input - a2 * out
            return out
        }
    }

    private companion object {
        // Roughly an octave of reach per band, so neighbouring boosts overlap
        // without ringing.
        const val Q = 1.0
    }
}

/**
 * Linear gain shared between the OSD and the audio sink. Replaces the
 * LoudnessEnhancer effect, which sat on the same fragile audio session and
 * produced the same class of playback stall when toggled mid-film.
 */
internal class GainState {
    @Volatile private var gainDb = 0f

    /** Linear multiplier for the current gain setting. */
    val linear: Float
        get() = 10.0.pow(gainDb / 20.0).toFloat()

    fun update(gainDb: Float) {
        this.gainDb = gainDb
    }
}

internal class GainAudioProcessor(private val state: GainState) : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val gain = state.linear
        val values = inputBuffer.remaining() / Float.SIZE_BYTES
        if (values <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val input = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val outputBuffer = replaceOutputBuffer(values * Float.SIZE_BYTES)
        val output = outputBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val start = input.position()
        for (index in 0 until values) {
            output.put(start + index, (input.get(start + index) * gain).coerceIn(-1f, 1f))
        }
        outputBuffer.position(values * Float.SIZE_BYTES)
        outputBuffer.flip()
        inputBuffer.position(inputBuffer.limit())
    }
}
