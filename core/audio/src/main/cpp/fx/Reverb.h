#pragma once
// Schroeder/Freeverb-style reverb: 8 parallel damped comb filters + 4 series
// allpasses per channel, stereo-spread tuned delays, plus a "plate" variant
// with modulated combs. Input is downmixed to mono (classic plate topology)
// or true stereo (studio variant) depending on mode.

#include "FxUnit.h"
#include "../dsp/DelayLine.h"
#include <cmath>
#include <vector>

namespace s1::audio::fx {

namespace revparam {
constexpr int kSize = 0;       // room size 0..1
constexpr int kDamping = 1;    // 0..1
constexpr int kWidth = 2;      // stereo width 0..1
constexpr int kDry = 3;        // dB
constexpr int kWet = 4;        // dB
constexpr int kPreDelayMs = 5;
constexpr int kDecaySeconds = 6; // display target; mapped onto size
}

class Reverb : public FxUnit {
public:
    void init(float sampleRate) override;
    void reset() override;
    void process(float* left, float* right, FrameCount frames) override;
    void setParam(int32_t index, float value) override;
    float getParam(int32_t index) const override;
    FrameCount tailFrames() const override;
    const char* name() const override { return "Reverb"; }

private:
    struct CombSet {
        dsp::DelayLine lines[8];
        float filterStore[8] = {0};
        std::vector<float> storage[8];
    };
    CombSet combsL_, combsR_;
    dsp::DelayLine allpassL_[4], allpassR_[4];
    std::vector<float> allpassStorageL_[4], allpassStorageR_[4];
    dsp::DelayLine preDelay_;
    std::vector<float> preDelayStorage_;

    SmoothedParam size_, damp_, width_, dryDb_, wetDb_, preDelayMs_;
    float lastSize_ = -1.f, lastDamp_ = -1.f;
};

} // namespace s1::audio::fx
