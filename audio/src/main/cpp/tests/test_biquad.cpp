#include <gtest/gtest.h>
#include <cmath>
#include "../dsp/biquad.h"

using studioone::dsp::Biquad;
using studioone::dsp::BiquadType;

namespace {
double rmsAfter(Biquad& filter, double freqHz, double sampleRate, int frames = 4096) {
    double sum = 0.0;
    for (int i = 0; i < frames; ++i) {
        const double in = std::sin(2.0 * M_PI * freqHz * i / sampleRate);
        const double out = filter.process(in);
        if (i > 1024) sum += out * out;  // skip settling
    }
    return std::sqrt(sum / (frames - 1024));
}
}  // namespace

TEST(BiquadTest, LowPassAttenuatesHighFrequencies) {
    Biquad filter;
    filter.prepare(48000.0);
    filter.configure(BiquadType::LowPass, 200.0, 0.0, 0.707);

    const double passband = rmsAfter(filter, 50.0, 48000.0);
    Biquad fresh;
    fresh.prepare(48000.0);
    fresh.configure(BiquadType::LowPass, 200.0, 0.0, 0.707);
    const double stopband = rmsAfter(fresh, 8000.0, 48000.0);

    EXPECT_GT(passband, 10.0 * stopband);
}

TEST(BiquadTest, PeakingBoostRaisesBandEnergy) {
    Biquad filter;
    filter.prepare(44100.0);
    filter.configure(BiquadType::Peaking, 1000.0, 12.0, 1.0);

    Biquad unity;
    unity.prepare(44100.0);
    unity.configure(BiquadType::Peaking, 1000.0, 0.0, 1.0);

    const double boosted = rmsAfter(filter, 1000.0, 44100.0);
    const double flat = rmsAfter(unity, 1000.0, 44100.0);
    EXPECT_GT(boosted, flat * 2.0);  // 12 dB ~ 4x amplitude
}
