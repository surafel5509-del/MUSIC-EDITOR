#pragma once
// Granular pitch shifter / time stretcher.
//
// Two overlapping read heads traverse a fixed input ring buffer at a rate
// ratio; Hann windows crossfade between heads to avoid discontinuities.
//   pitchRatio > 1, stretch 1.0 => pitch up (like varispeed + time preserve)
//   stretchRatio != 1, pitch 1.0 => time stretch preserving pitch
// This is the live "creative" shifter used by FX and the monitor path.
// Offline export uses the same core rendered at arbitrary block sizes.
// Latency = one grain window (reported to the graph for compensation).

#include "FxUnit.h"
#include <vector>

namespace s1::audio::fx {

class PitchShift : public FxUnit {
public:
    void init(float sampleRate) override;
    void reset() override;
    void process(float* left, float* right, FrameCount frames) override;
    void setParam(int32_t index, float value) override;
    float getParam(int32_t index) const override;
    FrameCount latencyFrames() const override { return grainFrames_; }
    const char* name() const override { return "PitchShift"; }

    static constexpr int kParamSemitones = 0;   // -24..+24
    static constexpr int kParamStretch = 1;     // 0.25..4.0 time stretch ratio
    static constexpr int kParamGrainMs = 2;     // 20..200
    static constexpr int kParamFormantKeep = 3; // 0..1 (approx via pre-emphasis)

private:
    std::vector<float> ringL_, ringR_;
    size_t ringCap_ = 0;
    size_t writePos_ = 0;
    float readPosA_ = 0.f, readPosB_ = 0.f;
    float grainPhase_ = 0.f;
    int grainFrames_ = 2048;
    SmoothedParam semitones_, stretch_, grainMs_, formant_;
};

} // namespace s1::audio::fx
