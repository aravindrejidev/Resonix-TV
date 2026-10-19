package com.resonix.tv.audio

import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** PCM format currently flowing through the DSP (channelCount 0 = nothing played yet). */
data class StreamFormat(val sampleRate: Int, val channelCount: Int, val floatPcm: Boolean)

/**
 * Kotlin face of the C++ DSP chain:  per-channel gain / mute / solo  ->  parametric EQ  ->
 * master gain  ->  soft limiter.  All setters are safe to call from the UI thread while audio
 * is playing; the audio thread never waits for them.
 */
class DspEngine {
    @Volatile
    private var handle: Long = DspNative.create()

    var sampleRate: Int = 0
        private set
    var channelCount: Int = 0
        private set
    var isFloat: Boolean = false
        private set

    val isReady: Boolean get() = handle != 0L

    private val formatState = MutableStateFlow(StreamFormat(0, 0, false))

    /** Updates whenever the player starts a stream with a different PCM format. */
    val format: StateFlow<StreamFormat> = formatState.asStateFlow()

    /** Called by the audio processor whenever the PCM format changes. */
    fun configure(sampleRate: Int, channelCount: Int, floatPcm: Boolean): Boolean {
        val h = handle
        if (h == 0L || !DspNative.configure(h, sampleRate, channelCount, floatPcm)) return false
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        this.isFloat = floatPcm
        formatState.value = StreamFormat(sampleRate, channelCount, floatPcm)
        return true
    }

    fun process(input: ByteBuffer, inputOffset: Int, output: ByteBuffer, outputOffset: Int, frames: Int): Int {
        val h = handle
        return if (h == 0L) -1 else DspNative.process(h, input, inputOffset, output, outputOffset, frames)
    }

    fun reset() = withHandle { DspNative.reset(it) }

    // ---- routing / gains ----
    fun setBypass(bypass: Boolean) = withHandle { DspNative.setBypass(it, bypass) }
    fun setMasterGainDb(db: Float) = withHandle { DspNative.setMasterGainDb(it, db) }
    fun setChannelGainDb(channel: Int, db: Float) = withHandle { DspNative.setChannelGainDb(it, channel, db) }
    fun setChannelMute(channel: Int, mute: Boolean) = withHandle { DspNative.setChannelMute(it, channel, mute) }
    fun setChannelSolo(channel: Int, solo: Boolean) = withHandle { DspNative.setChannelSolo(it, channel, solo) }

    // ---- parametric EQ ----
    fun setEqEnabled(enabled: Boolean) = withHandle { DspNative.setEqEnabled(it, enabled) }

    /** Bit n set = the EQ also processes channel n (default: all channels). */
    fun setEqChannelMask(mask: Int) = withHandle { DspNative.setEqChannelMask(it, mask) }

    fun setBand(index: Int, band: EqBand) = withHandle {
        DspNative.setEqBand(it, index, band.type.id, band.freqHz, band.gainDb, band.q, band.enabled)
    }

    /** Sets bands 0..n-1 and switches off the remaining ones. */
    fun setBands(bands: List<EqBand>) {
        for (i in 0 until MAX_BANDS) {
            setBand(i, bands.getOrNull(i) ?: EqBand(freqHz = 1000f, enabled = false))
        }
    }

    /** Applies a preset to the channels of the current layout (see [ChannelLayouts]). */
    fun applyPreset(preset: DspPreset, layout: List<ChannelRole>) {
        layout.forEachIndexed { index, role ->
            setChannelGainDb(index, preset.channelGainsDb[role] ?: 0f)
            setChannelMute(index, false)
            setChannelSolo(index, false)
        }
        setBands(preset.bands)
        setEqEnabled(preset.eqEnabled)
    }

    /** Linear peak (0..1) per channel since the last call, for level meters. Returns channels filled. */
    fun readPeaks(out: FloatArray): Int {
        val h = handle
        return if (h == 0L) 0 else DspNative.getPeaks(h, out)
    }

    /** Only call once nothing can be playing any more. */
    fun release() {
        val h = handle
        handle = 0L
        if (h != 0L) DspNative.destroy(h)
    }

    private inline fun withHandle(block: (Long) -> Unit) {
        val h = handle
        if (h != 0L) block(h)
    }

    companion object {
        const val MAX_CHANNELS = 8
        const val MAX_BANDS = 10
    }
}
