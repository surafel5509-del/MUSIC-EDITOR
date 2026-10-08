#pragma once
// Utility units: Gain (level/phase/width), ParametricEqUnit (8-band wrapper
// over dsp::ParametricEq), SimpleFilterUnit (single biquad HP/LP).
//
// EQ parameter mapping (mirrors Kotlin EqParams): index = band*4 + field
//   field 0 = enabled (0/1), 1 = type (BiquadType ordinal), 2 = freq, 3 = Q,
//   plus gainDb packed with freq for Peak/shelf types via index band*4+3+1.
// Simplified here: index = band*5 + field, field: 0 enabled,1 type,2 freq,3 q,4 gain.

#include "FxUnit.h"
#include "../dsp/Biquad.h"
#include <cmath>
#include <algorithm>

namespace s1::audio::fx {

class GainUnit : public FxUnit {
public:
    void init(float sampleRate) override { sr_ = sampleRate; gainDb_.snap(0.f); width_.snap(1.f); invert_.snap(0.f); }
    void reset() override {}
    void process(float* left, float* right, FrameCount frames) override {
        const float g = math::dbToGain(gainDb_.nextBlock());
        const float w = width_.nextBlock();
        const bool inv = invert_.target() >= 0.5f;
        const float sign = inv ? -1.f : 1.f;
        for (FrameCount i = 0; i < frames; ++i) {
            // Mid/Side width control.
            const float mid = (left[i] + right[i]) * 0.5f;
            const float side = (left[i] - right[i]) * 0.5f * w;
            left[i]  = sign * DenormalGuard::protect((mid + side) * g);
            right[i] = sign * DenormalGuard::protect((mid - side) * g);
        }
    }
    void setParam(int32_t index, float value) override {
        switch (index) {
            case 0: gainDb_.set(value); break;
            case 1: width_.set(value); break;
            case 2: invert_.set(value); break;
            default: break;
        }
    }
    float getParam(int32_t index) const override {
        switch (index) {
            case 0: return gainDb_.target();
            case 1: return width_.target();
            case 2: return invert_.target();
            default: return 0.f;
        }
    }
    const char* name() const override { return "Gain"; }
private:
    SmoothedParam gainDb_, width_, invert_;
};

class ParametricEqUnit : public FxUnit {
public:
    void init(float sampleRate) override { sr_ = sampleRate; eq_.init(sampleRate); }
    void reset() override { eq_.reset(); }
    void process(float* left, float* right, FrameCount frames) override {
        // dsp::ParametricEq owns independent L and R biquad sets.
        eq_.processStereo(left, right, frames);
    }
    void setParam(int32_t index, float value) override;
    float getParam(int32_t index) const override;
    const char* name() const override { return "ParametricEQ"; }
private:
    dsp::ParametricEq eq_;
    dsp::EqBand bands_[dsp::ParametricEq::kMaxBands]{};
};

class SimpleFilterUnit : public FxUnit {
public:
    explicit SimpleFilterUnit(dsp::BiquadType type) : type_(type) {}
    void init(float sampleRate) override {
        sr_ = sampleRate;
        fL_.setSampleRate(sampleRate); fR_.setSampleRate(sampleRate);
        freq_.snap(type_ == dsp::BiquadType::HighPass ? 80.f : 8000.f);
        q_.snap(0.707f);
        applyCoefs();
    }
    void reset() override { fL_.reset(); fR_.reset(); }
    void process(float* left, float* right, FrameCount frames) override {
        const float f = freq_.nextBlock();
        const float q = q_.nextBlock();
        if (std::fabs(f - lastF_) > 0.5f || std::fabs(q - lastQ_) > 0.001f) {
            lastF_ = f; lastQ_ = q; applyCoefs();
        }
        for (FrameCount i = 0; i < frames; ++i) {
            left[i] = fL_.process(left[i]);
            right[i] = fR_.process(right[i]);
        }
    }
    void setParam(int32_t index, float value) override {
        switch (index) {
            case 0: freq_.set(value); break;
            case 1: q_.set(value); break;
            default: break;
        }
    }
    float getParam(int32_t index) const override {
        switch (index) {
            case 0: return freq_.target();
            case 1: return q_.target();
            default: return 0.f;
        }
    }
    const char* name() const override {
        return type_ == dsp::BiquadType::HighPass ? "HighPass" : "LowPass";
    }
private:
    void applyCoefs() {
        fL_.setParams(type_, freq_.target(), q_.target(), 0.f);
        fR_.setParams(type_, freq_.target(), q_.target(), 0.f);
    }
    dsp::BiquadType type_;
    dsp::Biquad fL_, fR_;
    SmoothedParam freq_, q_;
    float lastF_ = -1.f, lastQ_ = -1.f;
};

// ParametricEqUnit impl (kept in header for the small param mapping).
inline void ParametricEqUnit::setParam(int32_t index, float value) {
    const int band = index / 5;
    const int field = index % 5;
    if (band >= dsp::ParametricEq::kMaxBands) return;
    switch (field) {
        case 0: bands_[band].enabled = value >= 0.5f; break;
        case 1: bands_[band].type = static_cast<dsp::BiquadType>(static_cast<int>(value)); break;
        case 2: bands_[band].freqHz = value; break;
        case 3: bands_[band].q = value; break;
        case 4: bands_[band].gainDb = value; break;
    }
    eq_.setBand(band, bands_[band]);
}

inline float ParametricEqUnit::getParam(int32_t index) const {
    const int band = index / 5;
    const int field = index % 5;
    if (band >= dsp::ParametricEq::kMaxBands) return 0.f;
    switch (field) {
        case 0: return bands_[band].enabled ? 1.f : 0.f;
        case 1: return static_cast<float>(static_cast<int>(bands_[band].type));
        case 2: return bands_[band].freqHz;
        case 3: return bands_[band].q;
        case 4: return bands_[band].gainDb;
        default: return 0.f;
    }
}

} // namespace s1::audio::fx
