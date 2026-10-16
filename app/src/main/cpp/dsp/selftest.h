#pragma once

#include <string>

namespace resonix::dsp {

// Runs the DSP self-test (frequency response, per-channel gains, neutrality, smoothness,
// limiter, robustness, speed). Used by the CI (host build) and by the app (on the TV itself).
// First line of the result is a one-line summary; the rest are per-test details. ASCII only.
//   minRealtimeFactor: required processing speed vs. real time for 8 ch x 10 bands.
std::string runSelfTest(double minRealtimeFactor, bool* allPassed = nullptr);

}  // namespace resonix::dsp
