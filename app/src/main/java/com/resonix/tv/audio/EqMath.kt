package com.resonix.tv.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Frequency response of the EQ for the on-screen curve. Uses the same "RBJ Audio EQ Cookbook"
 * filters and the same clamps as the native engine (dsp/biquad.cpp), so the curve shows what you hear.
 */
object EqMath {
    const val FREQ_MIN = 20f
    const val FREQ_MAX = 20000f

    private class Coeffs(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    /** Null = the band does nothing (switched off, or a boost / cut of about 0 dB). */
    private fun design(band: EqBand, fs: Double): Coeffs? {
        if (!band.enabled) return null
        val freq = band.freqHz.toDouble().coerceIn(10.0, fs * 0.45)
        val q = band.q.toDouble().coerceIn(0.1, 18.0)
        val gainDb = band.gainDb.toDouble().coerceIn(-24.0, 24.0)
        if (band.type.usesGain && abs(gainDb) < 0.01) return null

        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freq / fs
        val cw = cos(w0)
        val sw = sin(w0)
        val alpha = sw / (2.0 * q)
        val sqrtA2alpha = 2.0 * sqrt(a) * alpha

        var b0 = 1.0
        var b1 = 0.0
        var b2 = 0.0
        var a0 = 1.0
        var a1 = 0.0
        var a2 = 0.0
        when (band.type) {
            EqBandType.PEAK -> {
                b0 = 1.0 + alpha * a
                b1 = -2.0 * cw
                b2 = 1.0 - alpha * a
                a0 = 1.0 + alpha / a
                a1 = -2.0 * cw
                a2 = 1.0 - alpha / a
            }
            EqBandType.LOW_SHELF -> {
                b0 = a * ((a + 1.0) - (a - 1.0) * cw + sqrtA2alpha)
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cw)
                b2 = a * ((a + 1.0) - (a - 1.0) * cw - sqrtA2alpha)
                a0 = (a + 1.0) + (a - 1.0) * cw + sqrtA2alpha
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cw)
                a2 = (a + 1.0) + (a - 1.0) * cw - sqrtA2alpha
            }
            EqBandType.HIGH_SHELF -> {
                b0 = a * ((a + 1.0) + (a - 1.0) * cw + sqrtA2alpha)
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cw)
                b2 = a * ((a + 1.0) + (a - 1.0) * cw - sqrtA2alpha)
                a0 = (a + 1.0) - (a - 1.0) * cw + sqrtA2alpha
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cw)
                a2 = (a + 1.0) - (a - 1.0) * cw - sqrtA2alpha
            }
            EqBandType.HIGH_PASS -> {
                b0 = (1.0 + cw) / 2.0
                b1 = -(1.0 + cw)
                b2 = (1.0 + cw) / 2.0
                a0 = 1.0 + alpha
                a1 = -2.0 * cw
                a2 = 1.0 - alpha
            }
            EqBandType.LOW_PASS -> {
                b0 = (1.0 - cw) / 2.0
                b1 = 1.0 - cw
                b2 = (1.0 - cw) / 2.0
                a0 = 1.0 + alpha
                a1 = -2.0 * cw
                a2 = 1.0 - alpha
            }
        }
        return Coeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun magnitudeDb(c: Coeffs, fs: Double, freqHz: Double): Double {
        val w = 2.0 * PI * freqHz / fs
        val cos1 = cos(w)
        val sin1 = sin(w)
        val cos2 = cos(2.0 * w)
        val sin2 = sin(2.0 * w)
        val numRe = c.b0 + c.b1 * cos1 + c.b2 * cos2
        val numIm = -(c.b1 * sin1 + c.b2 * sin2)
        val denRe = 1.0 + c.a1 * cos1 + c.a2 * cos2
        val denIm = -(c.a1 * sin1 + c.a2 * sin2)
        val num = max(numRe * numRe + numIm * numIm, 1e-30)
        val den = max(denRe * denRe + denIm * denIm, 1e-30)
        return 10.0 * log10(num / den)
    }

    private fun sampleRateOrDefault(sampleRate: Int): Double = if (sampleRate > 0) sampleRate.toDouble() else 48000.0

    /** Combined response (dB) of all bands at [points] frequencies, log-spaced from [FREQ_MIN] to [FREQ_MAX]. */
    fun response(bands: List<EqBand>, sampleRate: Int, points: Int): FloatArray {
        val fs = sampleRateOrDefault(sampleRate)
        val filters = bands.mapNotNull { design(it, fs) }
        val out = FloatArray(points)
        if (points < 2) return out
        val span = ln((FREQ_MAX / FREQ_MIN).toDouble())
        for (i in 0 until points) {
            val freq = FREQ_MIN.toDouble() * exp(span * i / (points - 1))
            var sum = 0.0
            for (c in filters) sum += magnitudeDb(c, fs, freq)
            out[i] = sum.toFloat()
        }
        return out
    }

    /** Combined response (dB) of all bands at one frequency. */
    fun responseAt(bands: List<EqBand>, sampleRate: Int, freqHz: Float): Float {
        val fs = sampleRateOrDefault(sampleRate)
        var sum = 0.0
        for (band in bands) {
            val c = design(band, fs) ?: continue
            sum += magnitudeDb(c, fs, freqHz.toDouble())
        }
        return sum.toFloat()
    }

    /** 0..1 position of a frequency on the log axis 20 Hz ... 20 kHz. */
    fun freqToFraction(freqHz: Float): Float {
        val span = ln((FREQ_MAX / FREQ_MIN).toDouble())
        val x = ln(freqHz.coerceIn(FREQ_MIN, FREQ_MAX).toDouble() / FREQ_MIN) / span
        return x.toFloat().coerceIn(0f, 1f)
    }
}
