package com.resonix.tv.audio

import java.nio.ByteBuffer

/** Raw JNI entry points of libresonix_native.so (C++ DSP engine). Use [DspEngine] instead. */
object DspNative {
    init {
        System.loadLibrary("resonix_native")
    }

    external fun create(): Long
    external fun destroy(handle: Long)
    external fun configure(handle: Long, sampleRate: Int, channelCount: Int, floatPcm: Boolean): Boolean

    /** Both buffers must be direct. Returns the processed frame count or a negative error code. */
    external fun process(
        handle: Long,
        input: ByteBuffer,
        inputOffset: Int,
        output: ByteBuffer,
        outputOffset: Int,
        frames: Int,
    ): Int

    external fun reset(handle: Long)
    external fun setBypass(handle: Long, bypass: Boolean)
    external fun setMasterGainDb(handle: Long, db: Float)
    external fun setChannelGainDb(handle: Long, channel: Int, db: Float)
    external fun setChannelMute(handle: Long, channel: Int, mute: Boolean)
    external fun setChannelSolo(handle: Long, channel: Int, solo: Boolean)
    external fun setEqEnabled(handle: Long, enabled: Boolean)
    external fun setEqChannelMask(handle: Long, mask: Int)
    external fun setEqBand(
        handle: Long,
        index: Int,
        type: Int,
        freqHz: Float,
        gainDb: Float,
        q: Float,
        enabled: Boolean,
    )

    external fun getPeaks(handle: Long, out: FloatArray): Int

    /** Runs the DSP self-test on this device. First line is a one-line summary. */
    external fun selfTest(): String
}
