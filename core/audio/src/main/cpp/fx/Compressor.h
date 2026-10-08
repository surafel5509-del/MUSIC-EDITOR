#pragma once
// Dynamics processors: Compressor, Limiter (with look-ahead), Gate, Expander,
// De-Esser. All feed-forward topology with log-domain gain computation and
// independent attack/release ballistics (program-dependent release optional).

#include "FxUnit.h"
#include "../dsp/Envelope.h"
#include "../dsp/Biquad.h"
#include <algorithm>
#include <vector>

namespace s1::audio::fx {

/** Param ids shared across dynamics units (mirrors Kotlin FxParamIndex). */
namespace dynparam {
constexpr int kThreshold = 0;   // dB
constexpr int kRatio = 1;       // 1..inf (inf rendered as 100:1)
constexpr int kAttackMs = 2;
constexpr int kReleaseMs = 3;
constexpr int kKneeDb = 4;      // soft knee width
constexpr int kMakeupDb = 5;
constexpr int kMix = 6;         // parallel compression 0..1
constexpr int kDetector = 7;    // 0 = peak, 1 = RMS
constexpr int kSidechainHpHz = 8;
constexpr int kProgramRelease = 9; // 0/1
}

class Compressor : public FxUnit {
public:
    void init(float sampleRate) override;
    void reset() override;
    void process(float* left, float* right, FrameCount frames) override;
    void setParam(int32_t index, float value) override;
    float getParam(int32_t index) const override;
    const char* name() const override { return "Compressor"; }
    /** Gain-reduction meter (linear, UI reads with acquire). */
    float gainReductionDb() const { return grDb_.load(std::memory_order_relaxed); }

private:
    SmoothedParam threshold_, ratio_, attack_, release_, knee_, makeup_, mix_;
    dsp::PeakDetector peakDet_;
    dsp::RmsDetector rmsDet_;
    bool useRms_ = false;
    bool programRelease_ = false;
    std::atomic<float> grDb_{0.f};
    float gainSmoothed_ = 1.f;
};

/**
 * Look-ahead brickwall limiter: 2ms look-ahead delay line per channel,
 * instantaneous attack, exponential release, soft-clip pre-stage.
 * Reports latencyFrames() = look-ahead so the graph compensates.
 */
class Limiter : public FxUnit {
public:
    void init(float sampleRate) override;
    void reset() override;
    void process(float* left, float* right, FrameCount frames) override;
    void setParam(int32_t index, float value) override;
    float getParam(int32_t index) const override;
    FrameCount latencyFrames() const override { return lookAheadFrames_; }
    const char* name() const override { return "Limiter"; }

    static constexpr float kLookAheadMs = 2.0f;

private:
    SmoothedParam ceiling_;
    SmoothedParam release_;
    dsp::PeakDetector det_;
    float gain_ = 1.f;
    FrameCount lookAheadFrames_ = 96;
    std::vector<float> delayL_, delayR_;
    size_t delayPos_ = 0;
};

/** Noise gate / downward expander (ratio < 1 behaves as expander). */
class Gate : public FxUnit {
public:
    void init(float sampleRate) override;
    void reset() override;
    void process(float* left, float* right, FrameCount frames) override;
    void setParam(int32_t index, float value) override;
    float getParam(int32_t index) const override;
    const char* name() const override { return "Gate"; }
    bool isOpen() const { return open_; }

private:
    SmoothedParam threshold_, attack_, holdMs_, release_, rangeDb_;
    dsp::PeakDetector det_;
    float holdCounter_ = 0.f;
    float gain_ = 0.f;
    bool open_ = false;
};

/** De-esser: split-band compressor targeting 4-10kHz sibilance. */
class DeEsser : public FxUnit {
public:
    void init(float sampleRate) override;
    void reset() override;
    void process(float* left, float* right, FrameCount frames) override;
    void setParam(int32_t index, float value) override;
    float getParam(int32_t index) const override;
    const char* name() const override { return "DeEsser"; }

private:
    SmoothedParam freq_, threshold_, release_;
    dsp::Biquad bandL_, bandR_, bandL2_, bandR2_;
    dsp::PeakDetector det_;
    float gain_ = 1.f;
};

} // namespace s1::audio::fx
