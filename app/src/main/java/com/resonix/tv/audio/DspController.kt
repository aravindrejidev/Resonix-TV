package com.resonix.tv.audio

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

/**
 * Observable, persistent settings of the DSP chain (channel gains, mutes, EQ, master, preset).
 * Every change is pushed to the native engine immediately and saved.
 *
 * Two equalizers are stored side by side:
 *  - the graphic EQ ([bands]): ten fixed frequencies, only the gain changes;
 *  - the parametric EQ ([paramBands]): ten bands, each with its own filter type, frequency, gain and Q.
 * Only one of them is sent to the audio engine at a time ([eqMode]).
 */
class DspController(
    private val engine: DspEngine,
    private val prefs: SharedPreferences,
) {
    var masterDb by mutableFloatStateOf(0f)
        private set
    var eqEnabled by mutableStateOf(true)
        private set
    var eqMode by mutableStateOf(EqMode.GRAPHIC)
        private set
    var presetName by mutableStateOf<String?>("Flat")
        private set
    var processingEnabled by mutableStateOf(true)
        private set

    val channelDb = mutableStateListOf<Float>().apply { repeat(MAX_CH) { add(0f) } }
    val channelMute = mutableStateListOf<Boolean>().apply { repeat(MAX_CH) { add(false) } }

    /** Graphic EQ bands. */
    val bands = mutableStateListOf<EqBand>().apply { addAll(EqDefaults.flat()) }

    /** Parametric EQ bands (saved automatically, kept until you change or reset them). */
    val paramBands = mutableStateListOf<EqBand>().apply { addAll(EqDefaults.flatParametric()) }

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

    // ---- equalizer: on / off and which one ----
    fun enableEq(on: Boolean) {
        eqEnabled = on
        engine.setEqEnabled(on)
        changed()
    }

    fun toggleEq() = enableEq(!eqEnabled)

    /** Picks the graphic or the parametric EQ for playback (the other one stops processing). */
    fun setEqMode(mode: EqMode) {
        if (eqMode == mode) return
        eqMode = mode
        pushEqBands()
        changed()
    }

    fun toggleEqMode() = setEqMode(if (eqMode == EqMode.GRAPHIC) EqMode.PARAMETRIC else EqMode.GRAPHIC)

    /** True when this EQ is switched on and is the one the audio engine runs. */
    fun isInUse(mode: EqMode): Boolean = eqEnabled && eqMode == mode

    /**
     * "Use this EQ": on = this EQ becomes the one that runs (and the EQ is switched on);
     * off = the EQ is switched off completely. Asking to switch off an EQ that is not running does nothing.
     */
    fun setInUse(mode: EqMode, on: Boolean) {
        if (on) {
            eqMode = mode
            eqEnabled = true
        } else if (eqMode == mode && eqEnabled) {
            eqEnabled = false
        } else {
            return
        }
        engine.setEqEnabled(eqEnabled)
        pushEqBands()
        changed()
    }

    // ---- graphic EQ ----
    fun adjustBand(index: Int, delta: Float) {
        if (index !in bands.indices) return
        val band = bands[index]
        setBandGain(index, band.gainDb + delta)
    }

    fun resetBand(index: Int) = setBandGain(index, 0f)

    fun resetGraphic() {
        val flat = EqDefaults.flat()
        for (i in 0 until MAX_BANDS) bands[i] = flat[i]
        if (eqMode == EqMode.GRAPHIC) pushEqBands()
        changed()
    }

    private fun setBandGain(index: Int, db: Float) {
        if (index !in bands.indices) return
        bands[index] = bands[index].copy(gainDb = snap(db, GAIN_MIN, GAIN_MAX))
        if (eqMode == EqMode.GRAPHIC) engine.setBand(index, bands[index])
        changed()
    }

    // ---- parametric EQ ----

    /** One key press on the frequency: [direction] +1 / -1; [coarse] = bigger jumps while the key is held. */
    fun adjustParamFreq(index: Int, direction: Int, coarse: Boolean) {
        val band = paramBands.getOrNull(index) ?: return
        val stepsPerOctave = if (coarse) 3.0 else 12.0
        val next = stepLog(band.freqHz, direction, stepsPerOctave, PARAM_FREQ_MIN, PARAM_FREQ_MAX, 3)
        setParamBand(index, band.copy(freqHz = next))
    }

    /** Gain in 0.5 dB steps. High-pass / low-pass filters have no gain: nothing happens. */
    fun adjustParamGain(index: Int, direction: Int, coarse: Boolean) {
        val band = paramBands.getOrNull(index) ?: return
        if (!band.type.usesGain) return
        val step = if (coarse) 1.0f else 0.5f
        setParamBand(index, band.copy(gainDb = snap(band.gainDb + direction * step, PARAM_GAIN_MIN, PARAM_GAIN_MAX)))
    }

    fun adjustParamQ(index: Int, direction: Int, coarse: Boolean) {
        val band = paramBands.getOrNull(index) ?: return
        val stepsPerOctave = if (coarse) 2.0 else 8.0
        val next = stepLog(band.q, direction, stepsPerOctave, PARAM_Q_MIN, PARAM_Q_MAX, 2)
        setParamBand(index, band.copy(q = next))
    }

    /** Next / previous filter type (wraps around). */
    fun cycleParamType(index: Int, direction: Int) {
        val band = paramBands.getOrNull(index) ?: return
        val types = EqBandType.entries
        val next = types[(band.type.ordinal + direction).mod(types.size)]
        setParamBand(index, band.copy(type = next))
    }

    fun toggleParamBand(index: Int) {
        val band = paramBands.getOrNull(index) ?: return
        setParamBand(index, band.copy(enabled = !band.enabled))
    }

    fun setParamEnabled(index: Int, enabled: Boolean) {
        val band = paramBands.getOrNull(index) ?: return
        if (band.enabled != enabled) setParamBand(index, band.copy(enabled = enabled))
    }

    fun resetParamBand(index: Int) {
        val default = EqDefaults.flatParametric().getOrNull(index) ?: return
        setParamBand(index, default)
    }

    fun resetParametric() {
        val flat = EqDefaults.flatParametric()
        for (i in 0 until MAX_BANDS) paramBands[i] = flat[i]
        if (eqMode == EqMode.PARAMETRIC) pushEqBands()
        saveEq()
        if (eqMode == EqMode.PARAMETRIC) presetName = null
    }

    private fun setParamBand(index: Int, band: EqBand) {
        paramBands[index] = band
        if (eqMode == EqMode.PARAMETRIC) {
            engine.setBand(index, band)
            presetName = null
        }
        saveEq()
    }

    // ---- presets (channel gains + graphic EQ) ----
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
        eqMode = EqMode.GRAPHIC // a preset carries a graphic EQ; the saved parametric EQ stays untouched
        pushAll()
        presetName = preset.name
        save()
    }

    /** Flat graphic EQ, 0 dB everywhere. The saved parametric EQ is kept. */
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

    /**
     * Multiplies [value] by 2^(direction / stepsPerOctave) and rounds to [digits] significant digits,
     * so the numbers stay tidy (1.06 kHz, not 1059.46 Hz). A key press always moves the value
     * unless it is already at the limit.
     */
    private fun stepLog(
        value: Float,
        direction: Int,
        stepsPerOctave: Double,
        min: Float,
        max: Float,
        digits: Int,
    ): Float {
        var steps = direction
        repeat(4) {
            val raw = value.toDouble() * 2.0.pow(steps / stepsPerOctave)
            val result = roundSignificant(raw, digits).toFloat().coerceIn(min, max)
            if (result != value) return result
            steps += direction
        }
        return value.coerceIn(min, max)
    }

    private fun roundSignificant(x: Double, digits: Int): Double {
        if (x == 0.0) return 0.0
        val scale = 10.0.pow(digits - 1 - floor(log10(abs(x))).toInt())
        return round(x * scale) / scale
    }

    private fun pushEqBands() {
        val source = if (eqMode == EqMode.PARAMETRIC) paramBands else bands
        for (i in 0 until MAX_BANDS) engine.setBand(i, source[i])
    }

    private fun pushAll() {
        engine.setMasterGainDb(masterDb)
        engine.setEqEnabled(eqEnabled)
        engine.setBypass(!processingEnabled)
        for (i in 0 until MAX_CH) {
            engine.setChannelGainDb(i, channelDb[i])
            engine.setChannelMute(i, channelMute[i])
        }
        pushEqBands()
    }

    private fun load() {
        masterDb = prefs.getFloat("master", 0f)
        eqEnabled = prefs.getBoolean("eq", true)
        processingEnabled = prefs.getBoolean("processing", true)
        val savedMode = prefs.getInt("eq_mode", EqMode.GRAPHIC.id)
        eqMode = EqMode.entries.firstOrNull { it.id == savedMode } ?: EqMode.GRAPHIC
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
        for (i in 0 until MAX_BANDS) {
            val default = paramBands[i]
            val typeId = prefs.getInt("p${i}_t", default.type.id)
            paramBands[i] = EqBand(
                freqHz = prefs.getFloat("p${i}_f", default.freqHz).coerceIn(PARAM_FREQ_MIN, PARAM_FREQ_MAX),
                gainDb = prefs.getFloat("p${i}_g", default.gainDb).coerceIn(PARAM_GAIN_MIN, PARAM_GAIN_MAX),
                q = prefs.getFloat("p${i}_q", default.q).coerceIn(PARAM_Q_MIN, PARAM_Q_MAX),
                type = EqBandType.entries.firstOrNull { it.id == typeId } ?: EqBandType.PEAK,
                enabled = prefs.getBoolean("p${i}_e", default.enabled),
            )
        }
    }

    private fun save() {
        val e = prefs.edit()
        e.putFloat("master", masterDb)
        e.putBoolean("eq", eqEnabled)
        e.putInt("eq_mode", eqMode.id)
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
        putParametric(e)
        e.apply()
    }

    /** Saves only the parametric bands (used for every parametric edit). */
    private fun saveEq() {
        val e = prefs.edit()
        putParametric(e)
        e.apply()
    }

    private fun putParametric(e: SharedPreferences.Editor) {
        for (i in 0 until MAX_BANDS) {
            val b = paramBands[i]
            e.putFloat("p${i}_f", b.freqHz)
            e.putFloat("p${i}_g", b.gainDb)
            e.putFloat("p${i}_q", b.q)
            e.putInt("p${i}_t", b.type.id)
            e.putBoolean("p${i}_e", b.enabled)
        }
    }

    companion object {
        const val MAX_CH = DspEngine.MAX_CHANNELS
        const val MAX_BANDS = DspEngine.MAX_BANDS
        const val GAIN_MIN = -12f
        const val GAIN_MAX = 12f
        const val MASTER_MIN = -12f
        const val MASTER_MAX = 6f

        // Parametric EQ ranges (the native engine accepts +/-24 dB, 10 Hz ... 0.45 x sample rate, Q 0.1 ... 18).
        const val PARAM_GAIN_MIN = -15f
        const val PARAM_GAIN_MAX = 15f
        const val PARAM_FREQ_MIN = 20f
        const val PARAM_FREQ_MAX = 20000f
        const val PARAM_Q_MIN = 0.1f
        const val PARAM_Q_MAX = 18f
    }
}
