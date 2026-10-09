// SPDX-License-Identifier: MIT
// Synthesized drum kit (kick/snare/hats/perc) for zero-latency beats without
// loading samples. Used by the drum machine presets.
#pragma once

#include <cmath>
#include <cstdint>
#include "../dsp/envelope.h"

namespace studioone::instruments {

class DrumSynth {
public:
    // GM-ish mapping of note ranges to drum voices:
    //  36 kick, 38 snare, 42 closed hat, 46 open hat, 39 clap, 43 tom lo,
    //  45 tom mid, 47 tom hi, 49 crash, 51 ride.
    void prepare(double sampleRate) { sampleRate_ = sampleRate; }

    void noteOn(int key, int velocity) noexcept {
        trigger_ = key;
        triggerVelocity_ = velocity / 127.0;
        triggerAge_ = 0;
        noiseState_ = 0xACE1u;
        switch (key) {
            case 36: envLen_ = sampleRate_ * 0.35; break;
            case 38: case 39: envLen_ = sampleRate_ * 0.22; break;
            case 42: envLen_ = sampleRate_ * 0.06; break;
            case 46: envLen_ = sampleRate_ * 0.4; break;
            case 49: envLen_ = sampleRate_ * 1.2; break;
            case 51: envLen_ = sampleRate_ * 0.8; break;
            default: envLen_ = sampleRate_ * 0.25; break;
        }
    }

    void noteOff(int) noexcept {}

    void render(float* out, int numFrames) noexcept {
        if (triggerAge_ >= envLen_) return;
        for (int i = 0; i < numFrames && triggerAge_ < envLen_; ++i, ++triggerAge_) {
            const double t = triggerAge_ / sampleRate_;
            const double env = 1.0 - static_cast<double>(triggerAge_) / envLen_;
            float s = 0.f;
            switch (trigger_) {
                case 36: {  // Kick: exponential pitch sweep + click
                    const double freq = 45.0 + 120.0 * std::exp(-t * 30.0);
                    phase_ += freq / sampleRate_;
                    if (phase_ >= 1.0) phase_ -= 1.0;
                    s = static_cast<float>(std::sin(2.0 * M_PI * phase_) * env * env +
                                           white() * 0.3 * std::exp(-t * 200.0));
                    break;
                }
                case 38: case 39:  // Snare/clap: noise + 190Hz body
                    s = static_cast<float>(white() * 0.6 * env +
                                           std::sin(2.0 * M_PI * 190.0 * t) * 0.4 * env * env);
                    break;
                case 42: case 46:  // Hats: metallic noise, HP-ish via diff
                case 49: case 51: {
                    const float n = white();
                    s = (n - lastNoise_) * 0.5f * static_cast<float>(env);
                    lastNoise_ = n;
                    break;
                }
                default: {  // Toms: pitched sine with falling pitch
                    const double freq = 80.0 + (trigger_ - 43) * 25.0 + 40.0 * std::exp(-t * 15.0);
                    phase_ += freq / sampleRate_;
                    if (phase_ >= 1.0) phase_ -= 1.0;
                    s = static_cast<float>(std::sin(2.0 * M_PI * phase_) * env);
                    break;
                }
            }
            out[i] += s * static_cast<float>(triggerVelocity_) * 0.8f;
        }
    }

private:
    inline float white() noexcept {
        noiseState_ ^= noiseState_ << 13;
        noiseState_ ^= noiseState_ >> 17;
        noiseState_ ^= noiseState_ << 5;
        return static_cast<float>(noiseState_ & 0xFFFF) / 32768.f - 1.f;
    }

    double sampleRate_{44100.0};
    int trigger_{-1};
    double triggerVelocity_{1.0};
    int64_t triggerAge_{0};
    int64_t envLen_{0};
    double phase_{0.0};
    uint32_t noiseState_{0xACE1u};
    float lastNoise_{0.f};
};

}  // namespace studioone::instruments
