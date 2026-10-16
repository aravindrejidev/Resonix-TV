#pragma once

#include "dsp_types.h"

namespace resonix::dsp {

// Normalised biquad: y = b0*x + z1; z1 = b1*x - a1*y + z2; z2 = b2*x - a2*y   (a0 == 1)
struct BiquadCoeffs {
    double b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0;
};

struct BiquadState {
    double z1 = 0.0, z2 = 0.0;
};

// RBJ "Audio EQ Cookbook" designs. Parameters are clamped to sane ranges.
BiquadCoeffs designBiquad(BandType type, double sampleRate, double freqHz, double gainDb, double q);

// Peak / shelf bands with (almost) 0 dB gain are a no-op and are skipped.
bool isIdentityBand(BandType type, double gainDb);

// Magnitude (dB) of the filter at the given frequency - used by the self-test.
double magnitudeDb(const BiquadCoeffs& c, double sampleRate, double freqHz);

// Transposed direct form II, double precision: stable at low frequencies, zero latency.
inline double biquadTick(const BiquadCoeffs& c, BiquadState& s, double x) noexcept {
    const double y = c.b0 * x + s.z1;
    s.z1 = c.b1 * x - c.a1 * y + s.z2;
    s.z2 = c.b2 * x - c.a2 * y;
    return y;
}

inline void biquadProcess(const BiquadCoeffs& c, BiquadState& s, double* data, int frames) noexcept {
    double z1 = s.z1, z2 = s.z2;
    for (int i = 0; i < frames; ++i) {
        const double x = data[i];
        const double y = c.b0 * x + z1;
        z1 = c.b1 * x - c.a1 * y + z2;
        z2 = c.b2 * x - c.a2 * y;
        data[i] = y;
    }
    s.z1 = z1;
    s.z2 = z2;
}

}  // namespace resonix::dsp
