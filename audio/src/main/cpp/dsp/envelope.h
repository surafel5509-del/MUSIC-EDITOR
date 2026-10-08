// SPDX-License-Identifier: MIT
// One-pole analog-modeled ADSR envelope (click-free, RT-safe).
#pragma once

#include <cmath>
#include <algorithm>

namespace studioone::dsp {

class AdsrEnvelope {
public:
    void prepare(double sampleRate) {
        sampleRate_ = sampleRate;
        setAttackMs(attackMs_); setDecayMs(decayMs_); setReleaseMs(releaseMs_);
    }

    void setAttackMs(double ms) noexcept { attackMs_ = ms; attackCoef_ = coefFor(ms); }
    void setDecayMs(double ms) noexcept { decayMs_ = ms; decayCoef_ = coefFor(ms); }
    void setReleaseMs(double ms) noexcept { releaseMs_ = ms; releaseCoef_ = coefFor(ms); }
    void setSustain(double level) noexcept { sustain_ = std::clamp(level, 0.0, 1.0); }

    void noteOn() noexcept { stage_ = Stage::Attack; }
    void noteOff() noexcept { if (stage_ != Stage::Idle) stage_ = Stage::Release; }
    bool isIdle() const noexcept { return stage_ == Stage::Idle; }
    bool isReleasing() const noexcept { return stage_ == Stage::Release; }

    inline double next() noexcept {
        switch (stage_) {
            case Stage::Attack:
                level_ += attackCoef_ * (1.0 - level_);
                if (level_ >= 0.999) { level_ = 1.0; stage_ = Stage::Decay; }
                break;
            case Stage::Decay:
                level_ += decayCoef_ * (sustain_ - level_);
                if (std::abs(level_ - sustain_) < 0.0005) { level_ = sustain_; stage_ = Stage::Sustain; }
                break;
            case Stage::Sustain:
                level_ = sustain_;
                break;
            case Stage::Release:
                level_ *= releaseCoef_;
                if (level_ < 0.0001) { level_ = 0.0; stage_ = Stage::Idle; }
                break;
            case Stage::Idle:
                level_ = 0.0;
                break;
        }
        return level_;
    }

private:
    enum class Stage { Idle, Attack, Decay, Sustain, Release };

    double coefFor(double ms) const noexcept {
        // One-pole coefficient reaching ~99% in `ms` milliseconds.
        const double seconds = std::max(ms, 0.3) / 1000.0;
        return 1.0 - std::exp(-4.6 / (seconds * sampleRate_));
    }

    double sampleRate_{44100.0};
    Stage stage_{Stage::Idle};
    double level_{0.0};
    double sustain_{0.8};
    double attackMs_{5.0}, decayMs_{200.0}, releaseMs_{120.0};
    double attackCoef_{0.01}, decayCoef_{0.001}, releaseCoef_{0.999};
};

}  // namespace studioone::dsp
