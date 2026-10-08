#pragma once
// Envelope generators & followers. All state is per-instance; no allocation.

#include "../common/Types.h"
#include <algorithm>
#include <cmath>

namespace s1::audio::dsp {

/** Classic analog-model ADSR (linear segments, exponential release). */
class AdsrEnvelope {
public:
    enum class Stage { Idle, Attack, Decay, Sustain, Release };

    void setParams(float attackMs, float decayMs, float sustain01, float releaseMs, float sampleRate) {
        sr_ = sampleRate;
        attackRate_ = (sustain01 <= 0.f ? 1.f : 1.f) / std::max(1.f, attackMs * 0.001f * sampleRate);
        decayRate_ = (1.f - sustain01) / std::max(1.f, decayMs * 0.001f * sampleRate);
        sustain_ = sustain01;
        // Exponential release: reach ~0 in releaseMs
        releaseCoef_ = std::exp(-6.0f / std::max(1.f, releaseMs * 0.001f * sampleRate));
    }

    void noteOn() { stage_ = Stage::Attack; value_ = 0.f; }
    void noteOff() { if (stage_ != Stage::Idle) stage_ = Stage::Release; }
    bool isIdle() const { return stage_ == Stage::Idle; }
    Stage stage() const { return stage_; }

    /** Advance one sample. */
    inline float process() {
        switch (stage_) {
            case Stage::Attack:
                value_ += attackRate_;
                if (value_ >= 1.0f) { value_ = 1.0f; stage_ = Stage::Decay; }
                break;
            case Stage::Decay:
                value_ -= decayRate_;
                if (value_ <= sustain_) { value_ = sustain_; stage_ = Stage::Sustain; }
                break;
            case Stage::Sustain:
                value_ = sustain_;
                break;
            case Stage::Release:
                value_ *= releaseCoef_;
                if (value_ < 1e-5f) { value_ = 0.f; stage_ = Stage::Idle; }
                break;
            case Stage::Idle:
                value_ = 0.f;
                break;
        }
        return value_;
    }

    void reset() { stage_ = Stage::Idle; value_ = 0.f; }

private:
    Stage stage_ = Stage::Idle;
    float value_ = 0.f;
    float sr_ = 48000.f;
    float attackRate_ = 0.01f;
    float decayRate_ = 0.01f;
    float sustain_ = 0.7f;
    float releaseCoef_ = 0.999f;
};

/** Peak detector with instant attack / configurable release (metering + compressor). */
class PeakDetector {
public:
    void setParams(float sampleRate, float releaseMs) {
        releaseCoef_ = std::exp(-1.0f / std::max(1.f, releaseMs * 0.001f * sampleRate));
    }
    inline float process(float x) {
        const float ax = std::fabs(x);
        peak_ = ax > peak_ ? ax : peak_ * releaseCoef_;
        return peak_;
    }
    float value() const { return peak_; }
    void reset() { peak_ = 0.f; }
private:
    float peak_ = 0.f;
    float releaseCoef_ = 0.999f;
};

/** RMS detector over a sliding one-pole window (metering + compressor). */
class RmsDetector {
public:
    void setParams(float sampleRate, float windowMs) {
        coef_ = std::exp(-1.0f / std::max(1.f, windowMs * 0.001f * sampleRate));
    }
    inline float process(float x) {
        sum_ = coef_ * sum_ + (1.0f - coef_) * x * x;
        return std::sqrt(sum_);
    }
    float value() const { return std::sqrt(sum_); }
    void reset() { sum_ = 0.f; }
private:
    float sum_ = 0.f;
    float coef_ = 0.999f;
};

} // namespace s1::audio::dsp
