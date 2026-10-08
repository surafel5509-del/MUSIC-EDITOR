#pragma once
// Stereo delay with ping-pong mode, feedback-loop filtering (low+high cut),
// and wet/dry. Delay time is set from Kotlin in ms (tempo-sync divisions are
// resolved there against the transport BPM — keeps the native unit simple).

#include "FxUnit.h"
#include "../dsp/DelayLine.h"
#include "../dsp/Biquad.h"
#include <algorithm>
#include <vector>

namespace s1::audio::fx {

namespace delayparam {
constexpr int kTimeMs = 0;
constexpr int kFeedback = 1;    // 0..0.95
constexpr int kWet = 2;         // dB
constexpr int kDry = 3;         // dB
constexpr int kLowCutHz = 4;    // feedback loop filter
constexpr int kHighCutHz = 5;
constexpr int kWidth = 6;       // ping-pong spread 0..1
}

class DelayUnit : public FxUnit {
public:
    explicit DelayUnit(bool pingPong) : pingPong_(pingPong) {}
    void init(float sampleRate) override;
    void reset() override;
    void process(float* left, float* right, FrameCount frames) override;
    void setParam(int32_t index, float value) override;
    float getParam(int32_t index) const override;
    FrameCount tailFrames() const override;
    const char* name() const override { return pingPong_ ? "PingPongDelay" : "Delay"; }

private:
    bool pingPong_;
    dsp::DelayLine delayL_, delayR_;
    std::vector<float> storageL_, storageR_;
    dsp::Biquad lcL_, hcL_, lcR_, hcR_; // feedback loop filters
    SmoothedParam timeMs_, feedback_, wetDb_, dryDb_, lowCut_, highCut_, width_;
    float lastTimeMs_ = -1.f, lastLc_ = -1.f, lastHc_ = -1.f;
};

} // namespace s1::audio::fx
