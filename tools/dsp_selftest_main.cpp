// Host-side runner for the DSP self-test (used by CI and for local checks):
//   g++ -std=c++20 -O2 -Iapp/src/main/cpp app/src/main/cpp/dsp/*.cpp tools/dsp_selftest_main.cpp -o dsp_selftest
#include <cstdio>
#include <string>

#include "dsp/selftest.h"

int main() {
    bool ok = false;
    const std::string report = resonix::dsp::runSelfTest(10.0, &ok);
    std::fputs(report.c_str(), stdout);
    return ok ? 0 : 1;
}
