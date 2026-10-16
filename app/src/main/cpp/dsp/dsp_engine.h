#pragma once

#include <atomic>
#include <cstdint>
#include <thread>

#include "biquad.h"
#include "dsp_types.h"

namespace resonix::dsp {

// UI thread blocks briefly while writing; the audio thread only ever try-locks (never waits).
class SpinLock {
public:
    void lock() noexcept {
        while (flag_.test_and_set(std::memory_order_acquire)) std::this_thread::yield();
    }
    bool tryLock() noexcept { return !flag_.test_and_set(std::memory_order_acquire); }
    void unlock() noexcept { flag_.clear(std::memory_order_release); }

private:
    std::atomic_flag flag_;
};

// Multi-channel DSP chain (per channel):  gain / mute / solo  ->  parametric EQ  ->
// master gain  ->  soft limiter.   Interleaved PCM in, interleaved PCM out, zero latency.
class DspEngine {
public:
    DspEngine();

    // ---- stream setup + processing: call from ONE thread (the playback thread) ----
    bool configure(int sampleRate, int channelCount, bool floatPcm);
    void reset();  // clears filter memory (after seeks)
    int channelCount() const { return channels_; }
    bool isFloat() const { return float_; }
    int frameBytes() const { return channels_ * (float_ ? 4 : 2); }

    // `in` and `out` may point to the same buffer.
    void processInt16(const int16_t* in, int16_t* out, int frames);
    void processFloat(const float* in, float* out, int frames);

    // ---- parameters: any thread, the audio thread never blocks on them ----
    void setBypass(bool bypass);
    void setMasterGainDb(float db);
    void setChannelGainDb(int channel, float db);
    void setChannelMute(int channel, bool mute);
    void setChannelSolo(int channel, bool solo);
    void setEqEnabled(bool enabled);
    void setEqChannelMask(uint32_t mask);  // bit n = apply EQ to channel n
    void setEqBand(int index, const BandParams& band);

    // Linear peak per channel since the previous call (0..1). For level meters.
    int readPeaks(float* out, int maxChannels);

private:
    struct Params {
        bool bypass = false;
        bool eqEnabled = true;
        uint32_t eqMask = 0xFFu;
        float masterDb = 0.0f;
        float channelDb[kMaxChannels] = {};
        bool mute[kMaxChannels] = {};
        bool solo[kMaxChannels] = {};
        BandParams bands[kMaxBands];
    };

    struct BandRuntime {
        BiquadCoeffs cur;     // coefficients in use (ramping toward `target`)
        BiquadCoeffs target;
        BiquadCoeffs step;
        int rampLeft = 0;
        bool targetIdentity = true;
        bool active = false;  // processed in the audio loop
        BandParams applied;
        int designedForRate = 0;
        BiquadState state[kMaxChannels];
    };

    template <typename F>
    void update(F&& f) {
        lock_.lock();
        f(staging_);
        version_.fetch_add(1, std::memory_order_release);
        lock_.unlock();
    }

    template <typename Sample>
    void run(const Sample* in, Sample* out, int frames);
    template <typename Sample>
    void deinterleave(const Sample* in, int n);
    template <typename Sample>
    void interleave(Sample* out, int n);

    void syncParams();
    void applyParams(const Params& p);
    void updateBand(int index, const BandParams& bp);
    void snapToTargets();
    void applyChannelGains(int n);
    void applyEq(int n);
    void applyMasterAndLimit(int n);
    bool isNeutral() const;
    template <typename Sample>
    void scanPeaks(const Sample* in, int n);

    // stream
    int sampleRate_ = 48000;
    int channels_ = 2;
    bool float_ = false;

    // parameter hand-over (UI thread -> audio thread)
    SpinLock lock_;
    Params staging_;
    std::atomic<uint32_t> version_{1};
    uint32_t appliedVersion_ = 0;

    // audio-thread state
    bool bypass_ = false;
    bool eqEnabled_ = true;
    uint32_t eqMask_ = 0xFFu;
    double gainCur_[kMaxChannels];
    double gainTarget_[kMaxChannels];
    double masterCur_ = 1.0;
    double masterTarget_ = 1.0;
    BandRuntime bands_[kMaxBands];
    double work_[kMaxChannels][kBlockFrames];
    std::atomic<float> peaks_[kMaxChannels];
};

}  // namespace resonix::dsp
