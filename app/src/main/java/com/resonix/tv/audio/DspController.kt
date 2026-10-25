package com.resonix.tv.audio

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.round

/**
 * Observable, persistent settings of the DSP chain (channel gains, mutes, EQ, master, preset).
 * Every change is pushed to the native engine immediately and saved.
 */
class DspController(
    private val engine: DspEngine,
    private val prefs: SharedPreferences,
) {
    var masterDb by mutableFloatStateOf(0f)
        private set
    var eqEnabled by mutableStateOf(true)
        private set
    var presetName by mutableStateOf<String?>("Flat")
        private set
    var processingEnabled by mutableStateOf(true)
        private set

    val channelDb = mutableStateListOf<Float>().apply { repeat(MAX_CH) { add(0f) } }
    val channelMute = mutableStateListOf<Boolean>().apply { repeat(MAX_CH) { add(false) } }
    val bands = mutableStateListOf<EqBand>().apply { addAll(EqDefaults.flat()) }

    init {
        load()
        pushAll()
    }

    // ---- master / channels ----
    fun adjustMaster(delta: Float) = setMaster(masterDb + delta)

    fun setMaster(db: Float) {
        masterDb = snap(db, MASTER_MIN, MASTER_MAX)
        engine.setMasterGainDb(masterDb)
        changed()
    }

    fun adjustChannel(index: Int, delta: Float) {
        if (index !in 0 until MAX_CH) return
        channelDb[index] = snap(channelDb[index] + delta, GAIN_MIN, GAIN_MAX)
        engine.setChannelGainDb(index, channelDb[index])
        changed()
    }

    fun toggleMute(index: Int) {
        if (index !in 0 until MAX_CH) return
        channelMute[index] = !channelMute[index]
        engine.setChannelMute(index, channelMute[index])
        changed()
    }

    // ---- equalizer ----
    fun enableEq(on: Boolean) {
        eqEnabled = on
        engine.setEqEnabled(on)
        changed()
    }

    fun toggleEq() = enableEq(!eqEnabled)

    fun adjustBand(index: Int, delta: Float) {
        if (index !in bands.indices) return
        val band = bands[index]
        setBandGain(index, band.gainDb + delta)
    }

    fun resetBand(index: Int) = setBandGain(index, 0f)

    private fun setBandGain(index: Int, db: Float) {
        if (index !in bands.indices) return
        bands[index] = bands[index].copy(gainDb = snap(db, GAIN_MIN, GAIN_MAX))
        engine.setBand(index, bands[index])
        changed()
    }

    // ---- presets ----
    fun applyPreset(preset: DspPreset) {
        val count = engine.format.value.channelCount.let { if (it in 1..MAX_CH) it else MAX_CH }
        val layout = ChannelLayouts.forChannelCount(count)
        for (i in 0 until MAX_CH) {
            channelDb[i] = layout.getOrNull(i)?.let { preset.channelGainsDb[it] } ?: 0f
            channelMute[i] = false
        }
        for (i in 0 until MAX_BANDS) {
            bands[i] = preset.bands.getOrNull(i) ?: EqBand(freqHz = 1000f, enabled = false)
        }
        masterDb = 0f
        eqEnabled = preset.eqEnabled
        pushAll()
        presetName = preset.name
        save()
    }

    fun resetAll() = applyPreset(DspPresets.FLAT)

    /** Master switch for the whole chain (channel gains + EQ). Off = audio passes through untouched. */
    fun setProcessing(on: Boolean) {
        processingEnabled = on
        engine.setBypass(!on)
        save()
    }

    // ---- plumbing ----
    private fun changed() {
        presetName = null // no longer exactly a preset
        save()
    }

    private fun snap(value: Float, min: Float, max: Float): Float = (round(value * 2f) / 2f).coerceIn(min, max)

    private fun pushAll() {
        engine.setMasterGainDb(masterDb)
        engine.setEqEnabled(eqEnabled)
        engine.setBypass(!processingEnabled)
        for (i in 0 until MAX_CH) {
            engine.setChannelGainDb(i, channelDb[i])
            engine.setChannelMute(i, channelMute[i])
        }
        for (i in 0 until MAX_BANDS) engine.setBand(i, bands[i])
    }

    private fun load() {
        masterDb = prefs.getFloat("master", 0f)
        eqEnabled = prefs.getBoolean("eq", true)
        processingEnabled = prefs.getBoolean("processing", true)
        val savedPreset = prefs.getString("preset", "Flat") ?: ""
        presetName = savedPreset.ifEmpty { null }
        for (i in 0 until MAX_CH) {
            channelDb[i] = prefs.getFloat("ch${i}_db", 0f)
            channelMute[i] = prefs.getBoolean("ch${i}_mute", false)
        }
        for (i in 0 until MAX_BANDS) {
            val default = bands[i]
            val typeId = prefs.getInt("b${i}_t", default.type.id)
            bands[i] = EqBand(
                freqHz = prefs.getFloat("b${i}_f", default.freqHz),
                gainDb = prefs.getFloat("b${i}_g", default.gainDb),
                q = prefs.getFloat("b${i}_q", default.q),
                type = EqBandType.entries.firstOrNull { it.id == typeId } ?: EqBandType.PEAK,
                enabled = prefs.getBoolean("b${i}_e", default.enabled),
            )
        }
    }

    private fun save() {
        val e = prefs.edit()
        e.putFloat("master", masterDb)
        e.putBoolean("eq", eqEnabled)
        e.putBoolean("processing", processingEnabled)
        e.putString("preset", presetName ?: "")
        for (i in 0 until MAX_CH) {
            e.putFloat("ch${i}_db", channelDb[i])
            e.putBoolean("ch${i}_mute", channelMute[i])
        }
        for (i in 0 until MAX_BANDS) {
            val b = bands[i]
            e.putFloat("b${i}_f", b.freqHz)
            e.putFloat("b${i}_g", b.gainDb)
            e.putFloat("b${i}_q", b.q)
            e.putInt("b${i}_t", b.type.id)
            e.putBoolean("b${i}_e", b.enabled)
        }
        e.apply()
    }

    companion object {
        const val MAX_CH = DspEngine.MAX_CHANNELS
        const val MAX_BANDS = DspEngine.MAX_BANDS
        const val GAIN_MIN = -12f
        const val GAIN_MAX = 12f
        const val MASTER_MIN = -12f
        const val MASTER_MAX = 6f
    }
}
