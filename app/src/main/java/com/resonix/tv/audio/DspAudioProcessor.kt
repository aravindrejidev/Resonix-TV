package com.resonix.tv.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sits inside ExoPlayer's audio sink and runs every decoded PCM block (16-bit or float,
 * 1..8 channels) through the native [DspEngine]. No extra buffering => zero added latency.
 * Other formats (24-bit, encoded passthrough) are left untouched.
 */
@OptIn(UnstableApi::class)
class DspAudioProcessor(private val engine: DspEngine) : BaseAudioProcessor() {

    private var bytesPerFrame = 0
    private var scratch: ByteBuffer? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val floatPcm = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!floatPcm && inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET // inactive: pass through
        }
        if (!engine.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, floatPcm)) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        bytesPerFrame = inputAudioFormat.channelCount * if (floatPcm) 4 else 2
        return inputAudioFormat // same format in and out
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val frames = remaining / bytesPerFrame
        val output = replaceOutputBuffer(remaining)
        val start = inputBuffer.position()

        var processedBytes = 0
        if (frames > 0) {
            val source: ByteBuffer
            val sourceOffset: Int
            if (inputBuffer.isDirect) {
                source = inputBuffer
                sourceOffset = start
            } else {
                source = scratchBuffer(frames * bytesPerFrame)
                val copy = inputBuffer.duplicate()
                copy.limit(start + frames * bytesPerFrame)
                source.clear()
                source.put(copy)
                sourceOffset = 0
            }
            val done = engine.process(source, sourceOffset, output, 0, frames)
            if (done > 0) processedBytes = done * bytesPerFrame
        }

        // Whatever the engine did not handle (partial trailing frame, or an error) goes through unchanged.
        if (processedBytes < remaining) {
            val tail = inputBuffer.duplicate()
            tail.position(start + processedBytes)
            output.position(processedBytes)
            output.put(tail)
        } else {
            output.position(remaining)
        }
        output.flip()
        inputBuffer.position(inputBuffer.limit())
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        engine.reset() // no filter memory carried across seeks
    }

    override fun onReset() {
        engine.reset()
        scratch = null
        bytesPerFrame = 0
    }

    private fun scratchBuffer(size: Int): ByteBuffer {
        val current = scratch
        if (current != null && current.capacity() >= size) return current
        return ByteBuffer.allocateDirect(size.coerceAtLeast(8192)).order(ByteOrder.nativeOrder()).also { scratch = it }
    }
}
