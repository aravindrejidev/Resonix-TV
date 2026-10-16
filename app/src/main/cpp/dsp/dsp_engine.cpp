#include "dsp_engine.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <type_traits>

namespace resonix::dsp {

namespace {

constexpr double kLimiterThreshold = 0.95;  // transparent below this level
constexpr double kLimiterKnee = 0.05;       // soft knee, output never exceeds 1.0
constexpr double kRampSeconds = 0.012;      // EQ coefficient glide (no zipper noise / clicks)
constexpr double kTiny = 1e-25;             // denormal flush threshold

inline double dbToLinear(double db) { return db <= -59.9 ? 0.0 : std::pow(10.0, db / 20.0); }

inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

inline double softLimit(double x) noexcept {
    const double a = x < 0.0 ? -x : x;
    if (a <= kLimiterThreshold) return x;
    const double y =
        kLimiterThreshold + kLimiterKnee * std::tanh((a - kLimiterThreshold) / kLimiterKnee);
    return x < 0.0 ? -y : y;
}

inline void atomicMax(std::atomic<float>& target, float value) {
    float prev = target.load(std::memory_order_relaxed);
    while (value > prev &&
           !target.compare_exchange_weak(prev, value, std::memory_order_relaxed)) {
    }
}

inline int16_t toInt16(double v) noexcept {
    v *= 32768.0;
    if (v > 32767.0) v = 32767.0;
    if (v < -32768.0) v = -32768.0;
    return static_cast<int16_t>(std::lrint(v));
}

}  // namespace

DspEngine::DspEngine() {
    static const float kIsoFreqs[kMaxBands] = {31.5f, 63.f,   125.f,  250.f,  500.f,
                                               1000.f, 2000.f, 4000.f, 8000.f, 16000.f};
    for (int b = 0; b < kMaxBands; ++b) staging_.bands[b].freqHz = kIsoFreqs[b];
    for (int c = 0; c < kMaxChannels; ++c) {
        gainCur_[c] = gainTarget_[c] = 1.0;
        peaks_[c].store(0.0f, std::memory_order_relaxed);
    }
    std::memset(work_, 0, sizeof(work_));
}

// ------------------------------------------------------------------ stream setup

bool DspEngine::configure(int sampleRate, int channelCount, bool floatPcm) {
    if (sampleRate < 8000 || sampleRate > 384000) return false;
    if (channelCount < 1 || channelCount > kMaxChannels) return false;

    sampleRate_ = sampleRate;
    channels_ = channelCount;
    float_ = floatPcm;

    for (BandRuntime& b : bands_) {
        b.designedForRate = 0;  // forces a redesign for the new sample rate
        b.active = false;
        b.cur = b.target = BiquadCoeffs{};
        b.targetIdentity = true;
        b.rampLeft = 0;
        for (BiquadState& s : b.state) s = BiquadState{};
    }

    lock_.lock();  // not the per-block path, so a (tiny) blocking copy is fine here
    const Params snap = staging_;
    const uint32_t v = version_.load(std::memory_order_relaxed);
    lock_.unlock();
    appliedVersion_ = v;
    eqMask_ = snap.eqMask;
    applyParams(snap);
    snapToTargets();

    for (int c = 0; c < kMaxChannels; ++c) peaks_[c].store(0.0f, std::memory_order_relaxed);
    return true;
}

void DspEngine::reset() {
    for (BandRuntime& b : bands_)
        for (BiquadState& s : b.state) s = BiquadState{};
}

// ------------------------------------------------------------------ parameters

void DspEngine::setBypass(bool bypass) {
    update([&](Params& p) { p.bypass = bypass; });
}

void DspEngine::setMasterGainDb(float db) {
    update([&](Params& p) { p.masterDb = clampf(db, -60.0f, 24.0f); });
}

void DspEngine::setChannelGainDb(int channel, float db) {
    if (channel < 0 || channel >= kMaxChannels) return;
    update([&](Params& p) { p.channelDb[channel] = clampf(db, -60.0f, 24.0f); });
}

void DspEngine::setChannelMute(int channel, bool mute) {
    if (channel < 0 || channel >= kMaxChannels) return;
    update([&](Params& p) { p.mute[channel] = mute; });
}

void DspEngine::setChannelSolo(int channel, bool solo) {
    if (channel < 0 || channel >= kMaxChannels) return;
    update([&](Params& p) { p.solo[channel] = solo; });
}

void DspEngine::setEqEnabled(bool enabled) {
    update([&](Params& p) { p.eqEnabled = enabled; });
}

void DspEngine::setEqChannelMask(uint32_t mask) {
    update([&](Params& p) { p.eqMask = mask; });
}

void DspEngine::setEqBand(int index, const BandParams& band) {
    if (index < 0 || index >= kMaxBands) return;
    update([&](Params& p) { p.bands[index] = band; });
}

int DspEngine::readPeaks(float* out, int maxChannels) {
    const int n = std::min(channels_, std::min(maxChannels, kMaxChannels));
    for (int c = 0; c < n; ++c) out[c] = peaks_[c].exchange(0.0f, std::memory_order_relaxed);
    return n;
}

void DspEngine::syncParams() {
    if (version_.load(std::memory_order_acquire) == appliedVersion_) return;
    if (!lock_.tryLock()) return;  // writer is busy: pick the change up in the next block
    const Params snap = staging_;
    const uint32_t v = version_.load(std::memory_order_relaxed);
    lock_.unlock();
    appliedVersion_ = v;
    applyParams(snap);
}

void DspEngine::applyParams(const Params& p) {
    bypass_ = p.bypass;
    eqEnabled_ = p.eqEnabled;
    if (p.eqMask != eqMask_) {
        eqMask_ = p.eqMask;
        reset();  // channels joined/left the EQ: drop stale filter memory
    }
    masterTarget_ = dbToLinear(p.masterDb);

    bool anySolo = false;
    for (int c = 0; c < channels_; ++c) anySolo = anySolo || p.solo[c];
    for (int c = 0; c < channels_; ++c) {
        const bool silent = p.mute[c] || (anySolo && !p.solo[c]);
        gainTarget_[c] = silent ? 0.0 : dbToLinear(p.channelDb[c]);
    }
    for (int b = 0; b < kMaxBands; ++b) updateBand(b, p.bands[b]);
}

void DspEngine::updateBand(int index, const BandParams& bp) {
    BandRuntime& r = bands_[index];
    if (r.designedForRate == sampleRate_ && r.applied == bp) return;
    r.applied = bp;
    r.designedForRate = sampleRate_;

    const bool wantActive = bp.enabled && !isIdentityBand(bp.type, bp.gainDb);
    const BiquadCoeffs t =
        wantActive ? designBiquad(bp.type, sampleRate_, bp.freqHz, bp.gainDb, bp.q)
                   : BiquadCoeffs{};

    if (!r.active) {
        if (!wantActive) {
            r.cur = r.target = BiquadCoeffs{};
            r.targetIdentity = true;
            r.rampLeft = 0;
            return;
        }
        r.cur = BiquadCoeffs{};  // fade in from "no filter", with clean memory
        for (BiquadState& s : r.state) s = BiquadState{};
        r.active = true;
    }

    r.target = t;
    r.targetIdentity = !wantActive;
    r.rampLeft = std::max(1, static_cast<int>(sampleRate_ * kRampSeconds));
    const double k = 1.0 / r.rampLeft;
    r.step.b0 = (t.b0 - r.cur.b0) * k;
    r.step.b1 = (t.b1 - r.cur.b1) * k;
    r.step.b2 = (t.b2 - r.cur.b2) * k;
    r.step.a1 = (t.a1 - r.cur.a1) * k;
    r.step.a2 = (t.a2 - r.cur.a2) * k;
}

void DspEngine::snapToTargets() {
    for (int c = 0; c < kMaxChannels; ++c) gainCur_[c] = gainTarget_[c];
    masterCur_ = masterTarget_;
    for (BandRuntime& r : bands_) {
        r.cur = r.target;
        r.rampLeft = 0;
        if (r.targetIdentity) r.active = false;
    }
}

// ------------------------------------------------------------------ processing

void DspEngine::processInt16(const int16_t* in, int16_t* out, int frames) {
    run<int16_t>(in, out, frames);
}

void DspEngine::processFloat(const float* in, float* out, int frames) {
    run<float>(in, out, frames);
}

bool DspEngine::isNeutral() const {
    if (masterCur_ != 1.0 || masterTarget_ != 1.0) return false;
    for (int c = 0; c < channels_; ++c)
        if (gainCur_[c] != 1.0 || gainTarget_[c] != 1.0) return false;
    if (eqEnabled_)
        for (const BandRuntime& b : bands_)
            if (b.active) return false;
    return true;
}

template <typename Sample>
void DspEngine::scanPeaks(const Sample* in, int n) {
    const int ch = channels_;
    for (int c = 0; c < ch; ++c) {
        float peak = 0.0f;
        for (int i = 0; i < n; ++i) {
            float v;
            if constexpr (std::is_same_v<Sample, int16_t>)
                v = static_cast<float>(in[i * ch + c]) * (1.0f / 32768.0f);
            else
                v = in[i * ch + c];
            v = std::fabs(v);
            if (v > peak) peak = v;
        }
        atomicMax(peaks_[c], peak);
    }
}

template <typename Sample>
void DspEngine::run(const Sample* in, Sample* out, int frames) {
    const int ch = channels_;
    while (frames > 0) {
        const int n = frames < kBlockFrames ? frames : kBlockFrames;
        syncParams();

        if (bypass_ || isNeutral()) {
            // Nothing to do: bit-exact pass-through (the limiter must never colour a neutral chain).
            scanPeaks<Sample>(in, n);
            if (in != out) std::memcpy(out, in, sizeof(Sample) * static_cast<size_t>(n) * ch);
        } else {
            deinterleave<Sample>(in, n);
            applyChannelGains(n);
            if (eqEnabled_) applyEq(n);
            applyMasterAndLimit(n);
            interleave<Sample>(out, n);
        }
        in += static_cast<size_t>(n) * ch;
        out += static_cast<size_t>(n) * ch;
        frames -= n;
    }
}

template <typename Sample>
void DspEngine::deinterleave(const Sample* in, int n) {
    const int ch = channels_;
    for (int c = 0; c < ch; ++c) {
        double* dst = work_[c];
        const Sample* src = in + c;
        if constexpr (std::is_same_v<Sample, int16_t>) {
            for (int i = 0; i < n; ++i) dst[i] = static_cast<double>(src[i * ch]) * (1.0 / 32768.0);
        } else {
            for (int i = 0; i < n; ++i) {
                const double v = static_cast<double>(src[i * ch]);
                dst[i] = std::isfinite(v) ? v : 0.0;  // never let NaN/Inf into the filters
            }
        }
    }
}

template <typename Sample>
void DspEngine::interleave(Sample* out, int n) {
    const int ch = channels_;
    for (int c = 0; c < ch; ++c) {
        const double* src = work_[c];
        Sample* dst = out + c;
        if constexpr (std::is_same_v<Sample, int16_t>) {
            for (int i = 0; i < n; ++i) dst[i * ch] = toInt16(src[i]);
        } else {
            for (int i = 0; i < n; ++i) dst[i * ch] = static_cast<float>(src[i]);
        }
    }
}

void DspEngine::applyChannelGains(int n) {
    for (int c = 0; c < channels_; ++c) {
        double* x = work_[c];
        const double cur = gainCur_[c];
        const double tgt = gainTarget_[c];
        if (cur == tgt) {
            if (cur != 1.0)
                for (int i = 0; i < n; ++i) x[i] *= cur;
        } else {
            const double step = (tgt - cur) / n;  // linear glide across the block
            double g = cur;
            for (int i = 0; i < n; ++i) {
                x[i] *= g;
                g += step;
            }
            gainCur_[c] = tgt;
        }
    }
}

void DspEngine::applyEq(int n) {
    for (BandRuntime& r : bands_) {
        if (!r.active) continue;

        if (r.rampLeft == 0) {
            for (int c = 0; c < channels_; ++c)
                if (eqMask_ & (1u << c)) biquadProcess(r.cur, r.state[c], work_[c], n);
        } else {
            for (int i = 0; i < n; ++i) {
                if (r.rampLeft > 0) {
                    r.cur.b0 += r.step.b0;
                    r.cur.b1 += r.step.b1;
                    r.cur.b2 += r.step.b2;
                    r.cur.a1 += r.step.a1;
                    r.cur.a2 += r.step.a2;
                    if (--r.rampLeft == 0) r.cur = r.target;
                }
                for (int c = 0; c < channels_; ++c)
                    if (eqMask_ & (1u << c)) work_[c][i] = biquadTick(r.cur, r.state[c], work_[c][i]);
            }
        }

        for (int c = 0; c < channels_; ++c) {  // flush denormals (slow on ARM)
            BiquadState& s = r.state[c];
            if (std::fabs(s.z1) < kTiny) s.z1 = 0.0;
            if (std::fabs(s.z2) < kTiny) s.z2 = 0.0;
        }
        if (r.rampLeft == 0 && r.targetIdentity) r.active = false;
    }
}

void DspEngine::applyMasterAndLimit(int n) {
    const double start = masterCur_;
    const double step = (masterTarget_ - start) / n;
    for (int c = 0; c < channels_; ++c) {
        double* x = work_[c];
        double g = start;
        double peak = 0.0;
        for (int i = 0; i < n; ++i) {
            const double v = softLimit(x[i] * g);
            g += step;
            x[i] = v;
            const double a = v < 0.0 ? -v : v;
            if (a > peak) peak = a;
        }
        atomicMax(peaks_[c], static_cast<float>(peak));
    }
    masterCur_ = masterTarget_;
}

}  // namespace resonix::dsp
