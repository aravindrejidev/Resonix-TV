#pragma once

#include <cstdint>

namespace resonix::dsp {

constexpr int kMaxChannels = 8;     // up to 7.1
constexpr int kMaxBands = 10;       // parametric EQ bands
constexpr int kBlockFrames = 1024;  // internal processing block (~21 ms at 48 kHz)

enum class BandType : int32_t {
    Peak = 0,
    LowShelf = 1,
    HighShelf = 2,
    HighPass = 3,
    LowPass = 4,
};

struct BandParams {
    BandType type = BandType::Peak;
    float freqHz = 1000.0f;
    float gainDb = 0.0f;
    float q = 1.0f;
    bool enabled = false;

    bool operator==(const BandParams& o) const {
        return type == o.type && freqHz == o.freqHz && gainDb == o.gainDb && q == o.q &&
               enabled == o.enabled;
    }
};

}  // namespace resonix::dsp
