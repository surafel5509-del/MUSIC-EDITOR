#pragma once
// Modulation & saturation units:
//   Chorus / Flanger — modulated delay lines (same core, different ranges/FB)
//   Phaser           — 6 cascaded modulated allpasses + feedback
//   Tremolo / AutoPan — LFO amplitude / stereo panning
//   AutoFilter       — LFO-swept resonant lowpass (biquad, per-block recompute)
//   Distortion       — waveshaper (tanh/soft-clip/asym) + tone stack
//   Bitcrusher       — sample-rate reduction + bit depth truncation + dither
//   TapeSaturation   — hysteresis-style soft saturation with wow/flutter

#include "FxUnit.h"
#include "../common/FastMath.h"
#include "../dsp/DelayLine.h"
#include "../dsp/Biquad.h"
#include <cmath>
#include <vector>

namespace s1::audio::fx {

enum class LfoShape { Sine, Triangle, Saw, Square, RandomSH };

class Lfo {
public:
    void init(float sampleRate) { sr_ = sampleRate; }
    void setRate(float hz) { rate_ = std::max(0.01f, hz); }
    void setShape(LfoShape s) { shape_ = s; }
    /** Returns -1..1 */
    inline float next() {
        phase_ += rate_ / sr_;
        if (phase_ >= 1.f) { phase_ -= 1.f; onCycle(); }
        switch (shape_) {
            case LfoShape::Sine: return std::sin(2.f * math::kPi * phase_);
            case LfoShape::Triangle: return 4.f * std::fabs(phase_ - 0.5f) - 1.f;
            case LfoShape::Saw: return 2.f * phase_ - 1.f;
            case LfoShape::Square: return phase_ < 0.5f ? 1.f : -1.f;
            case LfoShape::RandomSH: return sampleHold_;
        }
        return 0.f;
    }
    void reset() { phase_ = 0.f; }
private:
    void onCycle() { sampleHold_ = (static_cast<float>(randState_ >> 8) / 8388608.f) - 1.f;
                     randState_ = randState_ * 1664525u + 1013904223u; }
    float sr_ = 48000.f;
    float rate_ = 1.f;
    float phase_ = 0.f;
    float sampleHold_ = 0.f;
    LfoShape shape_ = LfoShape::Sine;
    uint32_t randState_ = 12345u;
};

/** Chorus & Flanger share topology; [flangerMode] switches range + feedback. */
class ModulatedDelay : public FxUnit {
public:
    void init(float sampleRate) override;
    void reset() override;
    void process(float* left, float* right, FrameCount frames) override;
    void setParam(int32_t index, float value) override;
    float getParam(int32_t index) const override;
    FrameCount latencyFrames() const override { return 0; }
    const char* name() const override { return flangerMode_ ? "Flanger" : "Chorus"; }
    void setFlangerMode(bool on);

private:
    dsp::DelayLine delayL_, delayR_;
    std::vector<float> storageL_, storageR_;
    Lfo lfoL_, lfoR_;
    SmoothedParam rate_, depth_, feedback_, mix_, centerMs_;
    bool flangerMode_ = false;
    SmoothedParam stereoPhaseDeg_;
};

class Phaser : public FxUnit {
public:
    void init(float sampleRate) override;
    void reset() override;
    void process(float* left, float* right, FrameCount frames) override;
    void setParam(int32_t index, float value) override;
    float getParam(int32_t index) const override;
    const char* name() const override { return "Phaser"; }
private:
    static constexpr int kStages = 6;
    dsp::DelayLine allpassL_[kStages / 2], allpassR_[kStages / 2];
    std::vector<float> storageL_[kStages / 2], storageR_[kStages / 2];
    Lfo lfo_;
    SmoothedParam rate_, depth_, feedback_, mix_, baseFreq_;
};

class TremoloPan : public FxUnit {
public:
    void init(float sampleRate) override;
    void reset() override;
    void process(float* left, float* right, FrameCount frames) override;
    void setParam(int32_t index, float value) override;
    float getParam(int32_t index) const override;
    const char* name() const override { return "Tremolo/AutoPan"; }
private:
    Lfo lfo_;
    SmoothedParam rate_, depth_, panAmount_;
};

class AutoFilter : public FxUnit {
public:
    void init(float sampleRate) override;
    void reset() override;
    void process(float* left, float* right, FrameCount frames) override;
    void setParam(int32_t index, float value) override;
    float getParam(int32_t index) const override;
    const char* name() const override { return "AutoFilter"; }
private:
    Lfo lfo_;
    dsp::Biquad lpL_, lpR_;
    SmoothedParam rate_, baseHz_, rangeOct_, resonance_;
    float lastQ_ = -1.f;
};

class Distortion : public FxUnit {
public:
    enum Shape : int { Tanh = 0, SoftClip = 1, Asymmetric = 2, Fuzz = 3 };
    void init(float sampleRate) override;
    void reset() override;
    void process(float* left, float* right, FrameCount frames) override;
    void setParam(int32_t index, float value) override;
    float getParam(int32_t index) const override;
    const char* name() const override { return "Distortion"; }
private:
    dsp::Biquad preHighpassL_, preHighpassR_;
    dsp::Biquad toneL_, toneR_;
    SmoothedParam drive_, tone_, mixDb_, shapeSel_;
};

class Bitcrusher : public FxUnit {
public:
    void init(float sampleRate) override;
    void reset() override;
    void process(float* left, float* right, FrameCount frames) override;
    void setParam(int32_t index, float value) override;
    float getParam(int32_t index) const override;
    const char* name() const override { return "Bitcrusher"; }
private:
    SmoothedParam bits_, rateDiv_, mix_;
    float phase_ = 0.f;
    float heldL_ = 0.f, heldR_ = 0.f;
    uint32_t ditherState_ = 987654321u;
};

class TapeSaturation : public FxUnit {
public:
    void init(float sampleRate) override;
    void reset() override;
    void process(float* left, float* right, FrameCount frames) override;
    void setParam(int32_t index, float value) override;
    float getParam(int32_t index) const override;
    const char* name() const override { return "TapeSaturation"; }
private:
    dsp::Biquad headL_, headR_;      // head bump EQ
    SmoothedParam saturation_, wowDepth_, noiseDb_, speedSel_;
    Lfo wowLfo_;
    float hysteresis_ = 0.f;
};

} // namespace s1::audio::fx
