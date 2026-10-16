#include "biquad.h"

#include <algorithm>
#include <cmath>
#include <complex>

namespace resonix::dsp {

namespace {
constexpr double kPi = 3.14159265358979323846;
}

bool isIdentityBand(BandType type, double gainDb) {
    const bool gainBased =
        type == BandType::Peak || type == BandType::LowShelf || type == BandType::HighShelf;
    return gainBased && std::fabs(gainDb) < 0.01;
}

BiquadCoeffs designBiquad(BandType type, double fs, double freqHz, double gainDb, double q) {
    freqHz = std::clamp(freqHz, 10.0, fs * 0.45);
    q = std::clamp(q, 0.1, 18.0);
    gainDb = std::clamp(gainDb, -24.0, 24.0);

    const double A = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * kPi * freqHz / fs;
    const double cw = std::cos(w0);
    const double sw = std::sin(w0);
    const double alpha = sw / (2.0 * q);
    const double sqrtA2alpha = 2.0 * std::sqrt(A) * alpha;

    double b0 = 1, b1 = 0, b2 = 0, a0 = 1, a1 = 0, a2 = 0;
    switch (type) {
        case BandType::Peak:
            b0 = 1.0 + alpha * A;
            b1 = -2.0 * cw;
            b2 = 1.0 - alpha * A;
            a0 = 1.0 + alpha / A;
            a1 = -2.0 * cw;
            a2 = 1.0 - alpha / A;
            break;
        case BandType::LowShelf:
            b0 = A * ((A + 1.0) - (A - 1.0) * cw + sqrtA2alpha);
            b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cw);
            b2 = A * ((A + 1.0) - (A - 1.0) * cw - sqrtA2alpha);
            a0 = (A + 1.0) + (A - 1.0) * cw + sqrtA2alpha;
            a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cw);
            a2 = (A + 1.0) + (A - 1.0) * cw - sqrtA2alpha;
            break;
        case BandType::HighShelf:
            b0 = A * ((A + 1.0) + (A - 1.0) * cw + sqrtA2alpha);
            b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cw);
            b2 = A * ((A + 1.0) + (A - 1.0) * cw - sqrtA2alpha);
            a0 = (A + 1.0) - (A - 1.0) * cw + sqrtA2alpha;
            a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cw);
            a2 = (A + 1.0) - (A - 1.0) * cw - sqrtA2alpha;
            break;
        case BandType::HighPass:
            b0 = (1.0 + cw) / 2.0;
            b1 = -(1.0 + cw);
            b2 = (1.0 + cw) / 2.0;
            a0 = 1.0 + alpha;
            a1 = -2.0 * cw;
            a2 = 1.0 - alpha;
            break;
        case BandType::LowPass:
            b0 = (1.0 - cw) / 2.0;
            b1 = 1.0 - cw;
            b2 = (1.0 - cw) / 2.0;
            a0 = 1.0 + alpha;
            a1 = -2.0 * cw;
            a2 = 1.0 - alpha;
            break;
    }

    BiquadCoeffs c;
    c.b0 = b0 / a0;
    c.b1 = b1 / a0;
    c.b2 = b2 / a0;
    c.a1 = a1 / a0;
    c.a2 = a2 / a0;
    return c;
}

double magnitudeDb(const BiquadCoeffs& c, double fs, double freqHz) {
    const double w = 2.0 * kPi * freqHz / fs;
    const std::complex<double> z1 = std::polar(1.0, -w);
    const std::complex<double> z2 = std::polar(1.0, -2.0 * w);
    const std::complex<double> num = c.b0 + c.b1 * z1 + c.b2 * z2;
    const std::complex<double> den = 1.0 + c.a1 * z1 + c.a2 * z2;
    return 20.0 * std::log10(std::abs(num) / std::abs(den));
}

}  // namespace resonix::dsp
