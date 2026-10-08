#include "Compressor.h"
#include "../common/FastMath.h"
#include "../dsp/Biquad.h"
#include <algorithm>
#include <cmath>
#include <vector>

namespace s1::audio::fx {

using namespace math;

// ── Compressor ───────────────────────────────────────────────────────────────

void Compressor::init(float sampleRate) {
    sr_ = sampleRate;
    threshold_.snap(-18.f); threshold_.setSmoothing(sampleRate, 20.f);
    ratio_.snap(3.f);       ratio_.setSmoothing(sampleRate, 20.f);
    attack_.snap(10.f);
    release_.snap(120.f);
    knee_.snap(6.f);
    makeup_.snap(0.f);
    mix_.snap(1.f);
    peakDet_.setParams(sampleRate, 80.f);
    rmsDet_.setParams(sampleRate, 30.f);
    gainSmoothed_ = 1.f;
}

void Compressor::reset() { peakDet_.reset(); rmsDet_.reset(); gainSmoothed_ = 1.f; grDb_.store(0.f); }

void Compressor::process(float* left, float* right, FrameCount frames) {
    const float thresh = threshold_.nextBlock();
    const float ratio = std::max(1.f, ratio_.nextBlock());
    const float knee = std::max(0.f, knee_.nextBlock());
    const float makeup = dbToGain(makeup_.nextBlock());
    const float mix = std::clamp(mix_.nextBlock(), 0.f, 1.f);
    const float atkCoef = std::exp(-1.f / std::max(1.f, attack_.nextBlock() * 0.001f * sr_));
    float relMs = release_.nextBlock();

    for (FrameCount i = 0; i < frames; ++i) {
        // Linked stereo detection (max of L/R) preserves the image.
        const float det = useRms_
            ? std::max(rmsDet_.process(left[i]), rmsDet_.process(right[i]))
            : std::max(peakDet_.process(left[i]), peakDet_.process(right[i]));
        const float detDb = 20.f * std::log10(std::max(det, 1e-9f));

        // Static curve with soft knee.
        float grDb = 0.f;
        const float over = detDb - thresh;
        if (knee > 0.f && over > -knee * 0.5f && over < knee * 0.5f) {
            const float x = over + knee * 0.5f;
            grDb = (1.f / ratio - 1.f) * x * x / (2.f * knee);
        } else if (over >= knee * 0.5f) {
            grDb = over * (1.f / ratio - 1.f);
        }
        grDb = std::max(grDb, -60.f);

        // Program-dependent release: faster on transients, slower on sustain.
        if (programRelease_) {
            const float depth = std::min(1.f, std::fabs(grDb) / 12.f);
            relMs = release_.target() * (1.f - 0.6f * depth);
        }
        const float relCoef = std::exp(-1.f / std::max(1.f, relMs * 0.001f * sr_));

        const float targetGain = dbToGain(grDb);
        // Ballistics: fast attack, smooth release.
        gainSmoothed_ = targetGain < gainSmoothed_
            ? atkCoef * gainSmoothed_ + (1.f - atkCoef) * targetGain
            : relCoef * gainSmoothed_ + (1.f - relCoef) * targetGain;

        const float g = gainSmoothed_ * makeup;
        const float wetL = DenormalGuard::protect(left[i] * g);
        const float wetR = DenormalGuard::protect(right[i] * g);
        left[i]  = mix * wetL + (1.f - mix) * left[i];
        right[i] = mix * wetR + (1.f - mix) * right[i];

        if ((i & 31) == 0) { // publish GR meter at ~1.5kHz, cheap
            grDb_.store(20.f * std::log10(std::max(gainSmoothed_, 1e-6f)), std::memory_order_relaxed);
        }
    }
}

void Compressor::setParam(int32_t index, float value) {
    switch (index) {
        case dynparam::kThreshold: threshold_.set(value); break;
        case dynparam::kRatio: ratio_.set(value); break;
        case dynparam::kAttackMs: attack_.set(value); break;
        case dynparam::kReleaseMs: release_.set(value); break;
        case dynparam::kKneeDb: knee_.set(value); break;
        case dynparam::kMakeupDb: makeup_.set(value); break;
        case dynparam::kMix: mix_.set(value); break;
        case dynparam::kDetector: useRms_ = value >= 0.5f; break;
        case dynparam::kProgramRelease: programRelease_ = value >= 0.5f; break;
        default: break;
    }
}

float Compressor::getParam(int32_t index) const {
    switch (index) {
        case dynparam::kThreshold: return threshold_.target();
        case dynparam::kRatio: return ratio_.target();
        case dynparam::kAttackMs: return attack_.target();
        case dynparam::kReleaseMs: return release_.target();
        case dynparam::kKneeDb: return knee_.target();
        case dynparam::kMakeupDb: return makeup_.target();
        case dynparam::kMix: return mix_.target();
        case dynparam::kDetector: return useRms_ ? 1.f : 0.f;
        case dynparam::kProgramRelease: return programRelease_ ? 1.f : 0.f;
        default: return 0.f;
    }
}

// ── Limiter ──────────────────────────────────────────────────────────────────

void Limiter::init(float sampleRate) {
    sr_ = sampleRate;
    ceiling_.snap(-0.3f); ceiling_.setSmoothing(sampleRate, 10.f);
    release_.snap(60.f);
    det_.setParams(sampleRate, 40.f);
    lookAheadFrames_ = static_cast<FrameCount>(kLookAheadMs * 0.001f * sampleRate);
    const size_t cap = static_cast<size_t>(lookAheadFrames_) + kMaxFramesPerBlock + 8;
    delayL_.assign(cap, 0.f);
    delayR_.assign(cap, 0.f);
    delayPos_ = 0;
    gain_ = 1.f;
}

void Limiter::reset() {
    std::fill(delayL_.begin(), delayL_.end(), 0.f);
    std::fill(delayR_.begin(), delayR_.end(), 0.f);
    det_.reset();
    gain_ = 1.f;
}

void Limiter::process(float* left, float* right, FrameCount frames) {
    const float ceiling = dbToGain(ceiling_.nextBlock());
    const float relCoef = std::exp(-1.f / std::max(1.f, release_.nextBlock() * 0.001f * sr_));
    const size_t cap = delayL_.size();

    for (FrameCount i = 0; i < frames; ++i) {
        // Detect on the *undelayed* signal (look-ahead): compute required gain.
        const float peak = std::max(std::fabs(left[i]), std::fabs(right[i]));
        det_.process(peak);
        const float predicted = std::max(det_.value(), peak);
        float targetGain = 1.f;
        if (predicted > ceiling) {
            targetGain = ceiling / predicted;
        }
        // Instant attack, exponential release.
        gain_ = targetGain < gain_ ? targetGain : relCoef * gain_ + (1.f - relCoef) * targetGain;

        // Write current sample into the look-ahead delay, output the delayed one.
        const size_t readPos = delayPos_;
        delayL_[delayPos_] = left[i];
        delayR_[delayPos_] = right[i];
        delayPos_ = (delayPos_ + 1) % cap;
        left[i]  = softClip(DenormalGuard::protect(delayL_[readPos] * gain_));
        right[i] = softClip(DenormalGuard::protect(delayR_[readPos] * gain_));
    }
}

void Limiter::setParam(int32_t index, float value) {
    switch (index) {
        case 0: ceiling_.set(value); break;
        case 1: release_.set(value); break;
        default: break;
    }
}

float Limiter::getParam(int32_t index) const {
    switch (index) {
        case 0: return ceiling_.target();
        case 1: return release_.target();
        default: return 0.f;
    }
}

// ── Gate ─────────────────────────────────────────────────────────────────────

void Gate::init(float sampleRate) {
    sr_ = sampleRate;
    threshold_.snap(-45.f); threshold_.setSmoothing(sampleRate, 5.f);
    attack_.snap(1.f);
    holdMs_.snap(40.f);
    release_.snap(150.f);
    rangeDb_.snap(-60.f);
    det_.setParams(sampleRate, 20.f);
    gain_ = 0.f;
    open_ = false;
}

void Gate::reset() { det_.reset(); gain_ = 0.f; holdCounter_ = 0.f; open_ = false; }

void Gate::process(float* left, float* right, FrameCount frames) {
    const float thresh = dbToGain(threshold_.nextBlock());
    const float closedGain = dbToGain(rangeDb_.nextBlock());
    const float atkCoef = std::exp(-1.f / std::max(1.f, attack_.nextBlock() * 0.001f * sr_));
    const float relCoef = std::exp(-1.f / std::max(1.f, release_.nextBlock() * 0.001f * sr_));
    const float holdFrames = holdMs_.nextBlock() * 0.001f * sr_;

    for (FrameCount i = 0; i < frames; ++i) {
        const float env = std::max(det_.process(left[i]), det_.process(right[i]));
        if (env > thresh) {
            open_ = true;
            holdCounter_ = holdFrames;
        } else if (open_ && holdCounter_ > 0.f) {
            holdCounter_ -= 1.f;
        } else if (open_ && env < thresh * 0.8f) { // hysteresis avoids flutter
            open_ = false;
        }
        const float target = open_ ? 1.f : closedGain;
        gain_ = target > gain_
            ? atkCoef * gain_ + (1.f - atkCoef) * target
            : relCoef * gain_ + (1.f - relCoef) * target;
        left[i]  = DenormalGuard::protect(left[i] * gain_);
        right[i] = DenormalGuard::protect(right[i] * gain_);
    }
}

void Gate::setParam(int32_t index, float value) {
    switch (index) {
        case 0: threshold_.set(value); break;
        case 1: attack_.set(value); break;
        case 2: holdMs_.set(value); break;
        case 3: release_.set(value); break;
        case 4: rangeDb_.set(value); break;
        default: break;
    }
}

float Gate::getParam(int32_t index) const {
    switch (index) {
        case 0: return threshold_.target();
        case 1: return attack_.target();
        case 2: return holdMs_.target();
        case 3: return release_.target();
        case 4: return rangeDb_.target();
        default: return 0.f;
    }
}

// ── DeEsser ──────────────────────────────────────────────────────────────────

void DeEsser::init(float sampleRate) {
    sr_ = sampleRate;
    freq_.snap(6500.f);
    threshold_.snap(-30.f);
    release_.snap(80.f);
    bandL_.setSampleRate(sampleRate); bandR_.setSampleRate(sampleRate);
    bandL2_.setSampleRate(sampleRate); bandR2_.setSampleRate(sampleRate);
    det_.setParams(sampleRate, 10.f);
    gain_ = 1.f;
}

void DeEsser::reset() { bandL_.reset(); bandR_.reset(); det_.reset(); gain_ = 1.f; }

void DeEsser::process(float* left, float* right, FrameCount frames) {
    const float f = std::clamp(freq_.nextBlock(), 1000.f, 12000.f);
    bandL_.setParams(dsp::BiquadType::BandPass, f, 1.5f, 0.f);
    bandR_.setParams(dsp::BiquadType::BandPass, f, 1.5f, 0.f);
    const float thresh = dbToGain(threshold_.nextBlock());
    const float relCoef = std::exp(-1.f / std::max(1.f, release_.nextBlock() * 0.001f * sr_));

    for (FrameCount i = 0; i < frames; ++i) {
        // Split band: detect sibilance energy, duck only the band.
        const float sibL = bandL_.process(left[i]);
        const float sibR = bandR_.process(right[i]);
        const float env = det_.process(std::max(std::fabs(sibL), std::fabs(sibR)));
        float target = 1.f;
        if (env > thresh) target = thresh / env; // ratio -> inf above threshold
        gain_ = target < gain_ ? 0.3f * gain_ + 0.7f * target : relCoef * gain_ + (1.f - relCoef) * target;
        // Recombine: full signal minus ducked portion of the band.
        left[i]  = DenormalGuard::protect(left[i] - sibL * (1.f - gain_));
        right[i] = DenormalGuard::protect(right[i] - sibR * (1.f - gain_));
    }
}

void DeEsser::setParam(int32_t index, float value) {
    switch (index) {
        case 0: freq_.set(value); break;
        case 1: threshold_.set(value); break;
        case 2: release_.set(value); break;
        default: break;
    }
}

float DeEsser::getParam(int32_t index) const {
    switch (index) {
        case 0: return freq_.target();
        case 1: return threshold_.target();
        case 2: return release_.target();
        default: return 0.f;
    }
}

} // namespace s1::audio::fx
