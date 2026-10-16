#include "selftest.h"

#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "dsp_engine.h"

namespace resonix::dsp {

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr int kRate = 48000;

struct Report {
    std::string details;
    int passed = 0;
    int total = 0;

    void check(bool ok, const char* name, const char* fmt, double a = 0, double b = 0, double c = 0) {
        char info[160];
        std::snprintf(info, sizeof(info), fmt, a, b, c);
        char line[256];
        std::snprintf(line, sizeof(line), "%s %s (%s)\n", ok ? "PASS" : "FAIL", name, info);
        details += line;
        ++total;
        if (ok) ++passed;
    }
};

// Feeds a sine through channel 0..n-1 of a float stream and returns the RMS of the last half.
double sineRmsOut(DspEngine& e, int channels, double freq, double amp, int channelToMeasure,
                  int totalFrames = 48000) {
    std::vector<float> buf(static_cast<size_t>(480) * channels);
    double sum = 0;
    long count = 0;
    long frame = 0;
    while (frame < totalFrames) {
        const int n = std::min<long>(480, totalFrames - frame);
        for (int i = 0; i < n; ++i) {
            const float v = static_cast<float>(amp * std::sin(2.0 * kPi * freq * (frame + i) / kRate));
            for (int c = 0; c < channels; ++c) buf[static_cast<size_t>(i) * channels + c] = v;
        }
        e.processFloat(buf.data(), buf.data(), n);
        if (frame >= totalFrames / 2) {
            for (int i = 0; i < n; ++i) {
                const double y = buf[static_cast<size_t>(i) * channels + channelToMeasure];
                sum += y * y;
                ++count;
            }
        }
        frame += n;
    }
    return std::sqrt(sum / static_cast<double>(count));
}

double gainDbAt(DspEngine& e, int channels, double freq, int ch = 0) {
    const double amp = 0.1;  // low enough to stay far from the limiter
    e.reset();
    const double rms = sineRmsOut(e, channels, freq, amp, ch);
    return 20.0 * std::log10(rms / (amp / std::sqrt(2.0)));
}

BandParams band(BandType t, float f, float g, float q) {
    BandParams b;
    b.type = t;
    b.freqHz = f;
    b.gainDb = g;
    b.q = q;
    b.enabled = true;
    return b;
}

bool finiteAll(const std::vector<float>& v) {
    for (float x : v)
        if (!std::isfinite(x)) return false;
    return true;
}

}  // namespace

std::string runSelfTest(double minRealtimeFactor, bool* allPassed) {
    Report r;

    // ---- 1. peak band: measured response == analytic response, +6 dB at the centre
    {
        DspEngine e;
        e.configure(kRate, 2, true);
        const BandParams b = band(BandType::Peak, 1000, 6.0f, 1.0f);
        e.setEqBand(0, b);
        e.setEqEnabled(true);
        e.configure(kRate, 2, true);  // re-sync parameters
        const BiquadCoeffs c = designBiquad(b.type, kRate, b.freqHz, b.gainDb, b.q);
        double maxErr = 0;
        for (double f : {100.0, 500.0, 1000.0, 2000.0, 8000.0})
            maxErr = std::fmax(maxErr, std::fabs(gainDbAt(e, 2, f) - magnitudeDb(c, kRate, f)));
        const double centre = gainDbAt(e, 2, 1000.0);
        r.check(maxErr < 0.1, "EQ peak response matches theory", "max error %.3f dB", maxErr);
        r.check(std::fabs(centre - 6.0) < 0.15, "EQ peak +6 dB at centre", "measured %.2f dB", centre);
    }

    // ---- 2. shelves + high-pass
    {
        DspEngine e;
        e.setEqBand(0, band(BandType::LowShelf, 200, 6.0f, 0.707f));
        e.setEqBand(1, band(BandType::HighShelf, 4000, -6.0f, 0.707f));
        e.configure(kRate, 2, true);
        const double lo = gainDbAt(e, 2, 30.0), mid = gainDbAt(e, 2, 1000.0), hi = gainDbAt(e, 2, 16000.0);
        r.check(std::fabs(lo - 6.0) < 0.6 && std::fabs(hi + 6.0) < 0.8 && std::fabs(mid) < 0.8,
                "EQ low/high shelf", "30 Hz %.2f dB, 1 kHz %.2f dB, 16 kHz %.2f dB", lo, mid, hi);

        DspEngine h;
        h.setEqBand(0, band(BandType::HighPass, 100, 0.0f, 0.707f));
        h.configure(kRate, 2, true);
        const double stop = gainDbAt(h, 2, 20.0), pass = gainDbAt(h, 2, 1000.0);
        r.check(stop < -20.0 && std::fabs(pass) < 0.1, "EQ high-pass", "20 Hz %.1f dB, 1 kHz %.2f dB", stop, pass);
    }

    // ---- 3. discrete channel gains: boost centre, cut LFE, mute, solo (5.1)
    {
        DspEngine e;
        e.setChannelGainDb(2, 6.0f);
        e.setChannelGainDb(3, -6.0f);
        e.setChannelMute(5, true);
        e.configure(kRate, 6, true);
        const double c = gainDbAt(e, 6, 1000.0, 2), lfe = gainDbAt(e, 6, 1000.0, 3);
        const double fl = gainDbAt(e, 6, 1000.0, 0), muted = sineRmsOut(e, 6, 1000.0, 0.1, 5);
        r.check(std::fabs(c - 6.0) < 0.1 && std::fabs(lfe + 6.0) < 0.1 && std::fabs(fl) < 0.1 && muted == 0.0,
                "per-channel gain / mute (5.1)", "C %.2f dB, LFE %.2f dB, FL %.2f dB", c, lfe, fl);

        e.setChannelMute(5, false);
        e.setChannelSolo(2, true);
        e.configure(kRate, 6, true);
        const double solo = gainDbAt(e, 6, 1000.0, 2), others = sineRmsOut(e, 6, 1000.0, 0.1, 0);
        r.check(std::fabs(solo - 6.0) < 0.1 && others == 0.0, "solo channel", "solo %.2f dB, others %.6f", solo, others);
    }

    // ---- 4. neutral chain is bit-exact (int16 and float), 7.1
    {
        DspEngine e;
        e.configure(kRate, 8, false);
        std::vector<int16_t> in(static_cast<size_t>(8) * 3000), out(in.size());
        uint32_t s = 12345;
        for (auto& v : in) {
            s = s * 1664525u + 1013904223u;
            v = static_cast<int16_t>(s >> 16);
        }
        e.processInt16(in.data(), out.data(), 3000);
        bool exact = in == out;
        DspEngine f;
        f.configure(kRate, 8, true);
        std::vector<float> fin(in.size()), fout(in.size());
        for (size_t i = 0; i < in.size(); ++i) fin[i] = in[i] / 32768.0f;
        f.processFloat(fin.data(), fout.data(), 3000);
        exact = exact && fin == fout;
        r.check(exact, "neutral chain is bit-exact", "int16 + float, 7.1");
    }

    // ---- 5. int16 path == float path with an active EQ (within 1 LSB)
    {
        DspEngine a, b;
        for (DspEngine* e : {&a, &b}) {
            e->setEqBand(0, band(BandType::Peak, 250, 5.0f, 1.2f));
            e->setEqBand(1, band(BandType::Peak, 3000, -4.0f, 0.9f));
            e->setChannelGainDb(1, 3.0f);
        }
        a.configure(kRate, 2, false);
        b.configure(kRate, 2, true);
        std::vector<int16_t> in16(2 * 4800), out16(in16.size());
        std::vector<float> in32(in16.size()), out32(in16.size());
        for (size_t i = 0; i < in16.size() / 2; ++i) {
            const double v = 0.3 * std::sin(2 * kPi * 440.0 * i / kRate) + 0.2 * std::sin(2 * kPi * 3100.0 * i / kRate);
            in16[2 * i] = in16[2 * i + 1] = static_cast<int16_t>(std::lrint(v * 32768.0));
            in32[2 * i] = in32[2 * i + 1] = in16[2 * i] / 32768.0f;
        }
        a.processInt16(in16.data(), out16.data(), 4800);
        b.processFloat(in32.data(), out32.data(), 4800);
        double maxLsb = 0;
        for (size_t i = 0; i < out16.size(); ++i) maxLsb = std::fmax(maxLsb, std::fabs(out16[i] - out32[i] * 32768.0));
        r.check(maxLsb <= 1.01, "int16 path == float path", "max diff %.2f LSB", maxLsb);
    }

    // ---- 6. limiter keeps the output inside full scale
    {
        DspEngine e;
        e.setMasterGainDb(24.0f);
        e.configure(kRate, 2, true);
        std::vector<float> buf(2 * 4800);
        for (size_t i = 0; i < buf.size() / 2; ++i)
            buf[2 * i] = buf[2 * i + 1] = static_cast<float>(0.9 * std::sin(2 * kPi * 1000.0 * i / kRate));
        e.processFloat(buf.data(), buf.data(), 4800);
        double peak = 0;
        for (float v : buf) peak = std::fmax(peak, std::fabs(v));
        r.check(peak <= 1.0 && peak > 0.95, "limiter holds +24 dB boost below full scale", "peak %.4f", peak);
    }

    // ---- 7. parameter changes are smooth: compare with the jump an abrupt change would make
    {
        // 7a: +12 dB gain change on DC 0.1 (abrupt: one 0.30 step)
        DspEngine g;
        g.configure(kRate, 2, true);
        std::vector<float> buf(2 * 4800, 0.1f);
        g.processFloat(buf.data(), buf.data(), 2400);
        g.setChannelGainDb(0, 12.0f);
        g.processFloat(buf.data() + 2 * 2400, buf.data() + 2 * 2400, 2400);
        double stepGain = 0;
        for (size_t i = 1; i < buf.size() / 2; ++i)
            stepGain = std::fmax(stepGain, std::fabs(buf[2 * i] - buf[2 * (i - 1)]));

        // 7b: +9 dB low shelf switched on while DC 0.1 is playing (abrupt: one 0.18 step)
        DspEngine s;
        s.configure(kRate, 2, true);
        buf.assign(2 * 4800, 0.1f);
        s.processFloat(buf.data(), buf.data(), 2400);
        s.setEqBand(0, band(BandType::LowShelf, 100, 9.0f, 0.7f));
        s.processFloat(buf.data() + 2 * 2400, buf.data() + 2 * 2400, 2400);
        double stepEq = 0;
        for (size_t i = 1; i < buf.size() / 2; ++i)
            stepEq = std::fmax(stepEq, std::fabs(buf[2 * i] - buf[2 * (i - 1)]));

        r.check(stepGain < 0.002 && stepEq < 0.01, "gain / EQ changes glide without clicks",
                "max sample step: gain %.5f, EQ %.5f (abrupt would be 0.2-0.3)", stepGain, stepEq);
    }

    // ---- 8. robustness: extreme parameters, NaN input, channel-count switching
    {
        DspEngine e;
        e.setEqBand(0, band(BandType::Peak, 1.0f, 100.0f, 0.0f));
        e.setEqBand(1, band(BandType::HighPass, 90000.0f, -100.0f, 500.0f));
        e.setEqBand(2, band(BandType::LowShelf, -5.0f, 24.0f, 0.001f));
        e.setChannelGainDb(0, 500.0f);
        e.setMasterGainDb(-500.0f);
        bool ok = true;
        for (int ch : {2, 6, 8, 1, 8}) {
            ok = ok && e.configure(kRate, ch, true);
            std::vector<float> buf(static_cast<size_t>(ch) * 2000, 0.5f);
            buf[3] = std::nanf("");
            buf[7] = INFINITY;
            e.processFloat(buf.data(), buf.data(), 2000);
            ok = ok && finiteAll(buf);
        }
        ok = ok && !e.configure(kRate, 9, true) && !e.configure(100, 2, true);
        r.check(ok, "extreme parameters / NaN input stay finite", "2,6,8,1,8 channels");
    }

    // ---- 9. level meters
    {
        DspEngine e;
        e.configure(kRate, 6, true);
        e.setChannelGainDb(2, 6.0f);
        sineRmsOut(e, 6, 1000.0, 0.25, 2, 9600);
        float p[8] = {};
        const int n = e.readPeaks(p, 8);
        float again[8] = {};
        e.readPeaks(again, 8);
        r.check(n == 6 && p[2] > 0.45f && p[2] < 0.55f && p[0] > 0.2f && p[0] < 0.3f && again[2] == 0.0f,
                "level meters", "C %.3f, FL %.3f", p[2], p[0]);
    }

    // ---- 10. speed: 7.1, 10 bands, 2 s of audio
    double factor = 0;
    {
        DspEngine e;
        const float freqs[10] = {31.5f, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
        for (int i = 0; i < 10; ++i) e.setEqBand(i, band(BandType::Peak, freqs[i], (i % 2 ? 3.0f : -3.0f), 1.0f));
        e.setChannelGainDb(2, 4.0f);
        e.configure(kRate, 8, true);
        const int total = kRate * 2;
        std::vector<float> buf(static_cast<size_t>(1024) * 8);
        for (size_t i = 0; i < buf.size(); ++i) buf[i] = 0.2f * std::sin(0.01f * static_cast<float>(i));
        const auto t0 = std::chrono::steady_clock::now();
        for (int done = 0; done < total; done += 1024) e.processFloat(buf.data(), buf.data(), 1024);
        const double sec = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        factor = 2.0 / (sec > 1e-9 ? sec : 1e-9);
        char name[64];
        std::snprintf(name, sizeof(name), "speed (need >= %.0fx real time)", minRealtimeFactor);
        r.check(factor >= minRealtimeFactor, name, "7.1 x 10 bands: %.0fx real time", factor);
    }

    const bool ok = r.passed == r.total;
    if (allPassed) *allPassed = ok;
    char head[128];
    std::snprintf(head, sizeof(head), "DSP self-test: %s %d/%d | 7.1 x 10 bands: %.0fx real time\n",
                  ok ? "PASS" : "FAIL", r.passed, r.total, factor);
    return std::string(head) + r.details;
}

}  // namespace resonix::dsp
