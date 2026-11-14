package com.resonix.tv.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * "Night mode": makes loud scenes quieter and quiet scenes (whispers, dialogue) louder, so you can
 * follow the story at low volume without waking the house. A compressor that listens to all
 * speakers together (the subwoofer channel is not used for the decision), so the stereo image
 * and the surround balance stay as they are.
 *
 * Sits in front of the DSP chain in ExoPlayer's audio sink (16-bit or float PCM, 1..8 channels).
 * While it is off, audio is copied through unchanged.
 */
@OptIn(UnstableApi::class)
class NightModeProcessor : BaseAudioProcessor() {

    /** 0 = off, 1 = light, 2 = strong. May change while a movie plays. */
    @Volatile
    var level: Int = 0

    private var floatPcm = false
    private var channels = 0
    private var bytesPerSample = 2
    private var envelopeRelease = 0f
    private var gainAttack = 0f
    private var gainRelease = 0f
    private var envelope = 0f
    private var gain = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!isFloat && inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET // inactive: pass through
        }
        floatPcm = isFloat
        channels = inputAudioFormat.channelCount
        bytesPerSample = if (isFloat) 4 else 2
        val rate = inputAudioFormat.sampleRate.toDouble()
        envelopeRelease = exp(-1.0 / (0.250 * rate)).toFloat() // the level meter lets go over 0.25 s
        gainAttack = exp(-1.0 / (0.0005 * rate)).toFloat()     // turning down is almost instant (0.5 ms)
        gainRelease = exp(-1.0 / (0.080 * rate)).toFloat()     // turning up again takes 80 ms
        resetState()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val currentLevel = level

        // Off and settled: nothing to do, copy the bytes.
        if (currentLevel == 0 && gain == 1f) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        val threshold: Float
        val exponent: Float // how strongly loud parts are pulled down: 1 - 1/ratio
        val makeup: Float
        when (currentLevel) {
            1 -> { // light: above -24 dBFS ratio 2:1, +3 dB
                threshold = 0.0631f
                exponent = 0.5f
                makeup = 1.413f
            }
            2 -> { // strong: above -32 dBFS ratio 4:1, +8 dB
                threshold = 0.0251f
                exponent = 0.75f
                makeup = 2.512f
            }
            else -> { // fading back to normal after being switched off
                threshold = 1f
                exponent = 0f
                makeup = 1f
            }
        }

        // Read the samples in the machine's own byte order, whatever the buffer says.
        val input = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        val frameBytes = bytesPerSample * channels
        val frames = remaining / frameBytes
        val start = inputBuffer.position()
        val lfe = if (channels >= 6) 3 else -1 // the 5.1 / 7.1 bass channel does not steer the compressor
        var env = envelope
        var g = gain
        var offset = 0
        for (frame in 0 until frames) {
            var peak = 0f
            for (c in 0 until channels) {
                if (c == lfe) continue
                val v = abs(read(input, start + offset + c * bytesPerSample))
                if (v > peak) peak = v
            }
            env = if (peak > env) peak else peak + (env - peak) * envelopeRelease
            val target = if (env <= threshold) {
                makeup
            } else {
                makeup * (env / threshold).toDouble().pow(-exponent.toDouble()).toFloat()
            }
            val coefficient = if (target < g) gainAttack else gainRelease
            g = target + (g - target) * coefficient
            for (c in 0 until channels) {
                val index = offset + c * bytesPerSample
                write(output, index, read(input, start + index) * g)
            }
            offset += frameBytes
        }
        envelope = env
        gain = if (currentLevel == 0 && abs(g - 1f) < 0.0005f) 1f else g

        // A partial frame at the end (should not happen) goes through unchanged.
        val consumed = frames * frameBytes
        output.position(consumed)
        if (consumed < remaining) {
            val tail = inputBuffer.duplicate()
            tail.position(start + consumed)
            output.put(tail)
        }
        output.position(remaining)
        output.flip()
        inputBuffer.position(inputBuffer.limit())
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        resetState()
    }

    override fun onReset() {
        resetState()
        channels = 0
    }

    private fun resetState() {
        envelope = 0f
        gain = 1f
    }

    private fun read(buffer: ByteBuffer, index: Int): Float =
        if (floatPcm) buffer.getFloat(index) else buffer.getShort(index) / 32768f

    private fun write(buffer: ByteBuffer, index: Int, value: Float) {
        val safe = softClip(value)
        if (floatPcm) {
            buffer.putFloat(index, safe)
        } else {
            buffer.putShort(index, (safe * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
        }
    }

    /** Rounds off peaks above 0.9 so a sudden loud bang after silence cannot click. */
    private fun softClip(value: Float): Float {
        val a = abs(value)
        if (a <= 0.9f) return value
        val shaped = 0.9f + 0.1f * tanh((a - 0.9f) / 0.1f)
        return if (value < 0f) -shaped else shaped
    }
}
