// SPDX-License-Identifier: MIT
// Built-in effect processors.
//
// All effects share the same interface: prepare() is called off the audio
// thread; process() must never allocate or lock. Parameter setters called
// from the audio thread use smoothed one-pole targets to avoid zipper noise.
#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <vector>
#include "biquad.h"

namespace studioone::dsp {

/** 4-state smoother: parameters glide over ~8ms at any sample rate. */
class SmoothedParam {
public:
    void prepare(double sampleRate) noexcept { coef_ = 1.0 - std::exp(-1.0 / (0.008 * sampleRate)); }
    void set(float target) noexcept { target_ = target; }
    void snap(float value) noexcept { target_ = value; current_ = value; }
    inline float next() noexcept {
        current_ += coef_ * (target_ - current_);
        return current_;
    }
    float current() const noexcept { return current_; }

private:
    float target_{0.0f};
    float current_{0.0f};
    double coef_{0.01};
};

/** Circular delay line with fractional (linear-interp) reads. RT-safe. */
class DelayLine {
public:
    void prepare(size_t maxDelayFrames) {
        buffer_.assign(maxDelayFrames + 4, 0.0f);
        capacity_ = buffer_.size();
        writePos_ = 0;
    }

    inline void write(float sample) noexcept {
        buffer_[writePos_] = sample;
        writePos_ = (writePos_ + 1) % capacity_;
    }

    /** Reads `delay` samples behind the write position, linearly interpolated. */
    inline float read(double delay) const noexcept {
        const double clamped = std::min(std::max(delay, 1.0), static_cast<double>(capacity_ - 2));
        const double readPos = writePos_ + capacity_ - clamped;
        const size_t i0 = static_cast<size_t>(readPos) % capacity_;
        const size_t i1 = (i0 + 1) % capacity_;
        const float frac = static_cast<float>(readPos - std::floor(readPos));
        return buffer_[i0] + frac * (buffer_[i1] - buffer_[i0]);
    }

private:
    std::vector<float> buffer_;
    size_t capacity_{0};
    size_t writePos_{0};
};

// ============================================================================
// Dynamics
// ============================================================================

/** Feed-forward compressor with soft knee and RMS/peak envelope. */
class Compressor {
public:
    void prepare(double sampleRate) {
        sampleRate_ = sampleRate;
        attack_.prepare(sampleRate); release_.prepare(sampleRate);
        threshold_.prepare(sampleRate); ratio_.prepare(sampleRate);
        makeup_.prepare(sampleRate); mix_.prepare(sampleRate);
        attack_.snap(0.01f); release_.snap(0.12f);
        threshold_.snap(-18.f); ratio_.snap(4.f); makeup_.snap(0.f); mix_.snap(1.f);
        kneeDb_ = 6.0;
    }

    // Param ids (keep in sync with Kotlin EffectCatalog):
    // 0 threshold_db, 1 ratio, 2 attack_ms, 3 release_ms, 4 knee_db, 5 makeup_db, 6 mix
    void setParameter(uint32_t id, float v) noexcept {
        switch (id) {
            case 0: threshold_.set(v); break;
            case 1: ratio_.set(v); break;
            case 2: attackMs_ = v; break;
            case 3: releaseMs_ = v; break;
            case 4: kneeDb_ = v; break;
            case 5: makeup_.set(v); break;
            case 6: mix_.set(v); break;
        }
    }

    void process(float* const* channels, int numChannels, int numFrames) noexcept {
        const double attackCoef = std::exp(-1.0 / (std::max(attackMs_, 0.05) / 1000.0 * sampleRate_));
        const double releaseCoef = std::exp(-1.0 / (std::max(releaseMs_, 1.0) / 1000.0 * sampleRate_));
        for (int i = 0; i < numFrames; ++i) {
            float peak = 0.f;
            for (int c = 0; c < numChannels; ++c) peak = std::max(peak, std::abs(channels[c][i]));
            const double inDb = peak > 1e-6 ? 20.0 * std::log10(peak) : -144.0;

            const double thresh = threshold_.next();
            const double ratio = std::max(ratio_.next(), 1.0f);
            const double knee = kneeDb_;

            double gainDb = 0.0;
            if (2.0 * (inDb - thresh) < -knee) {
                gainDb = 0.0;
            } else if (2.0 * std::abs(inDb - thresh) <= knee) {
                const double x = inDb - thresh + knee / 2.0;
                gainDb = (1.0 / ratio - 1.0) * x * x / (2.0 * knee);
            } else {
                gainDb = thresh + (inDb - thresh) / ratio - inDb;
            }

            const double target = std::pow(10.0, gainDb / 20.0);
            envelope_ = target < envelope_
                ? attackCoef * envelope_ + (1.0 - attackCoef) * target
                : releaseCoef * envelope_ + (1.0 - releaseCoef) * target;

            const double gain = envelope_ * std::pow(10.0, makeup_.next() / 20.0);
            const float wet = static_cast<float>(gain);
            const float mix = mix_.next();
            for (int c = 0; c < numChannels; ++c) {
                channels[c][i] = channels[c][i] * (1.f - mix + mix * wet);
            }
        }
    }

private:
    double sampleRate_{44100.0};
    SmoothedParam attack_, release_, threshold_, ratio_, makeup_, mix_;
    double attackMs_{10.0}, releaseMs_{120.0}, kneeDb_{6.0};
    double envelope_{1.0};
};

/** Downward noise gate/expander with hysteresis. */
class NoiseGate {
public:
    void prepare(double sampleRate) {
        sampleRate_ = sampleRate;
        threshold_.prepare(sampleRate); threshold_.snap(-50.f);
        hysteresis_.prepare(sampleRate); hysteresis_.snap(4.f);
    }
    // 0 threshold_db, 1 attack_ms, 2 release_ms, 3 hysteresis_db
    void setParameter(uint32_t id, float v) noexcept {
        switch (id) {
            case 0: threshold_.set(v); break;
            case 1: attackMs_ = v; break;
            case 2: releaseMs_ = v; break;
            case 3: hysteresis_.set(v); break;
        }
    }

    void process(float* const* channels, int numChannels, int numFrames) noexcept {
        const double attackCoef = std::exp(-1.0 / (std::max(attackMs_, 0.05) / 1000.0 * sampleRate_));
        const double releaseCoef = std::exp(-1.0 / (std::max(releaseMs_, 1.0) / 1000.0 * sampleRate_));
        for (int i = 0; i < numFrames; ++i) {
            float peak = 0.f;
            for (int c = 0; c < numChannels; ++c) peak = std::max(peak, std::abs(channels[c][i]));
            const double db = peak > 1e-6 ? 20.0 * std::log10(peak) : -144.0;
            const double thresh = threshold_.next();
            if (open_) {
                if (db < thresh - hysteresis_.next()) open_ = false;
            } else {
                if (db > thresh) open_ = true;
            }
            const double target = open_ ? 1.0 : 0.0;
            gain_ = target > gain_
                ? attackCoef * gain_ + (1.0 - attackCoef) * target
                : releaseCoef * gain_ + (1.0 - releaseCoef) * target;
            const float g = static_cast<float>(gain_);
            for (int c = 0; c < numChannels; ++c) channels[c][i] *= g;
        }
    }

private:
    double sampleRate_{44100.0};
    SmoothedParam threshold_, hysteresis_;
    double attackMs_{2.0}, releaseMs_{150.0};
    double gain_{0.0};
    bool open_{false};
};

/** Lookahead brickwall limiter for buses and mastering. */
class Limiter {
public:
    void prepare(double sampleRate) {
        sampleRate_ = sampleRate;
        // Size the lookahead delay lines from the configured lookahead.
        const size_t cap = static_cast<size_t>(lookaheadMs_ / 1000.0 * sampleRate_) + 16;
        delayed_[0].prepare(cap);
        delayed_[1].prepare(cap);
        release_.prepare(sampleRate); release_.snap(0.1f);
        ceiling_.prepare(sampleRate); ceiling_.snap(-1.f);
    }
    // 0 ceiling_db, 1 release_ms, 2 lookahead_ms
    void setParameter(uint32_t id, float v) noexcept {
        switch (id) {
            case 0: ceiling_.set(v); break;
            case 1: releaseMs_ = v; break;
            case 2: lookaheadMs_ = v; break;
        }
    }
    int32_t latencyFrames() const noexcept {
        return static_cast<int32_t>(lookaheadMs_ / 1000.0 * sampleRate_) + 1;
    }

    void process(float* const* channels, int numChannels, int numFrames) noexcept {
        const double releaseCoef = std::exp(-1.0 / (std::max(releaseMs_, 1.0) / 1000.0 * sampleRate_));
        const double ceil = std::pow(10.0, ceiling_.next() / 20.0);
        const double lookaheadFrames = lookaheadMs_ / 1000.0 * sampleRate_;
        for (int i = 0; i < numFrames; ++i) {
            float peak = 0.f;
            for (int c = 0; c < numChannels; ++c) peak = std::max(peak, std::abs(channels[c][i]));
            // Delay the signal by the lookahead; detect gain from the future.
            delayed_[0].write(channels[0][i]);
            if (numChannels > 1) delayed_[1].write(channels[1][i]);
            const float futurePeak = std::max(delayed_[0].read(lookaheadFrames),
                                               numChannels > 1 ? delayed_[1].read(lookaheadFrames) : 0.f);
            double targetGain = futurePeak > ceil ? ceil / futurePeak : 1.0;
            gain_ = targetGain < gain_ ? targetGain : releaseCoef * gain_ + (1.0 - releaseCoef) * targetGain;
            const float g = static_cast<float>(gain_);
            channels[0][i] = delayed_[0].read(lookaheadFrames) * g;
            if (numChannels > 1) channels[1][i] = delayed_[1].read(lookaheadFrames) * g;
        }
    }

private:
    double sampleRate_{44100.0};
    SmoothedParam release_, ceiling_;
    double releaseMs_{100.0}, lookaheadMs_{2.0};
    double gain_{1.0};
    DelayLine delayed_[2];
};

}  // namespace studioone::dsp
