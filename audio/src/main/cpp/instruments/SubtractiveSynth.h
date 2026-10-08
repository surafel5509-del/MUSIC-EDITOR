// SPDX-License-Identifier: MIT
// Two-oscillator subtractive synth: polyBLEP oscillators -> resonant SVF
// -> ADSR amp. Voice-stealing polyphonic. RT-safe rendering.
#pragma once

#include <array>
#include <cmath>
#include "../dsp/envelope.h"

namespace studioone::instruments {

enum class OscShape : int { Sine = 0, Saw = 1, Square = 2, Triangle = 3, Noise = 4 };

/** PolyBLEP anti-aliased oscillator (band-limited step). */
class PolyBlepOsc {
public:
    void setFrequency(double hz, double sampleRate) noexcept {
        increment_ = hz / sampleRate;
    }

    inline float next(OscShape shape) noexcept {
        if (shape == OscShape::Noise) {
            rngState_ = rngState_ * 1664525u + 1013904223u;
            return static_cast<float>(rngState_ & 0xFFFF) / 32768.f - 1.f;
        }
        double t = phase_;
        phase_ += increment_;
        if (phase_ >= 1.0) phase_ -= 1.0;

        switch (shape) {
            case OscShape::Sine:
                return static_cast<float>(std::sin(2.0 * M_PI * t));
            case OscShape::Saw: {
                double v = 2.0 * t - 1.0;
                v -= polyBlep(t);
                return static_cast<float>(v);
            }
            case OscShape::Square: {
                double v = t < 0.5 ? 1.0 : -1.0;
                v += polyBlep(t);
                v -= polyBlep(std::fmod(t + 0.5, 1.0));
                return static_cast<float>(v);
            }
            case OscShape::Triangle: {
                // Leaky integrator of the square for a triangle.
                double sq = t < 0.5 ? 1.0 : -1.0;
                sq += polyBlep(t);
                sq -= polyBlep(std::fmod(t + 0.5, 1.0));
                triIntegrator_ = triIntegrator_ + (sq - triIntegrator_) * 0.25;
                return static_cast<float>(triIntegrator_ * 4.0);
            }
            default:
                return 0.f;
        }
    }

private:
    inline double polyBlep(double t) const noexcept {
        const double dt = increment_;
        if (dt <= 0.0) return 0.0;
        if (t < dt) {
            const double x = t / dt;
            return x + x - x * x - 1.0;
        }
        if (t > 1.0 - dt) {
            const double x = (t - 1.0) / dt;
            return x * x + x + x + 1.0;
        }
        return 0.0;
    }

    double phase_{0.0};
    double increment_{0.0};
    double triIntegrator_{0.0};
    uint32_t rngState_{0x1234567u};
};

/** Two-pole state-variable filter (lowpass out), stable to resonance 20. */
class SvfFilter {
public:
    void prepare(double sampleRate) noexcept { sampleRate_ = sampleRate; }

    void setTarget(double cutoff, double q) noexcept {
        targetCutoff_ = cutoff; targetQ_ = q;
    }

    inline float process(float in) noexcept {
        // Smooth cutoff to avoid zipper noise on automated sweeps.
        cutoff_ += 0.001 * (targetCutoff_ - cutoff_);
        const double f = 2.0 * std::sin(M_PI * std::min(cutoff_, sampleRate_ * 0.25) / sampleRate_);
        const double k = 1.0 / std::max(targetQ_, 0.1);
        low_ += f * band_;
        high_ = in - low_ - k * band_;
        band_ += f * high_;
        return static_cast<float>(low_);
    }

private:
    double sampleRate_{44100.0};
    double cutoff_{8000.0}, targetCutoff_{8000.0}, targetQ_{0.707};
    double low_{0.0}, band_{0.0}, high_{0.0};
};

class SubtractiveSynth {
public:
    static constexpr int kMaxVoices = 12;

    struct Patch {
        OscShape osc1Shape{OscShape::Saw};
        OscShape osc2Shape{OscShape::Square};
        double osc2DetuneSemitones{0.05};
        double oscMix{0.5};            // 0 = osc1 only, 1 = osc2 only
        double filterCutoff{8000.0};
        double filterQ{0.9};
        double filterEnvAmount{0.5};
        double attackMs{3.0}, decayMs{250.0}, sustain{0.75}, releaseMs{150.0};
        double lfoRateHz{5.0}, lfoToPitch{0.0}; // semitones
        double glideMs{0.0};
        double gain{0.7};
    };

    void prepare(double sampleRate) {
        sampleRate_ = sampleRate;
        for (auto& v : voices_) {
            v.ampEnv.prepare(sampleRate);
            v.filterEnv.prepare(sampleRate);
            v.filter.prepare(sampleRate);
        }
        lfoPhase_ = 0.0;
    }

    void setPatch(const Patch& patch) noexcept { patch_ = patch; }  // called off RT

    void noteOn(int key, int velocity) noexcept {
        int victim = -1;
        for (int i = 0; i < kMaxVoices; ++i) if (!voices_[i].active) { victim = i; break; }
        if (victim < 0) victim = stealIndex_++ % kMaxVoices;

        auto& v = voices_[victim];
        v.active = true;
        v.key = key;
        v.frequency = 440.0 * std::pow(2.0, (key - 69) / 12.0);
        v.velocityGain = velocity / 127.0;
        v.ampEnv.setAttackMs(patch_.attackMs);
        v.ampEnv.setDecayMs(patch_.decayMs);
        v.ampEnv.setSustain(patch_.sustain);
        v.ampEnv.setReleaseMs(patch_.releaseMs);
        v.filterEnv.setAttackMs(patch_.attackMs * 0.7);
        v.filterEnv.setDecayMs(patch_.decayMs);
        v.filterEnv.setSustain(patch_.sustain);
        v.filterEnv.setReleaseMs(patch_.releaseMs);
        v.ampEnv.noteOn();
        v.filterEnv.noteOn();
    }

    void noteOff(int key) noexcept {
        for (auto& v : voices_) if (v.active && v.key == key) {
            v.ampEnv.noteOff(); v.filterEnv.noteOff();
        }
    }

    void allNotesOff() noexcept {
        for (auto& v : voices_) { v.ampEnv.noteOff(); v.filterEnv.noteOff(); }
    }

    /** Renders mono. RT-safe. */
    void render(float* out, int numFrames) noexcept {
        // LFO (shared across voices) — cheap sine.
        const double lfoInc = patch_.lfoRateHz / sampleRate_;
        for (int i = 0; i < numFrames; ++i) {
            lfoPhase_ += lfoInc;
            if (lfoPhase_ >= 1.0) lfoPhase_ -= 1.0;
            const double lfo = std::sin(2.0 * M_PI * lfoPhase_);

            float sample = 0.f;
            for (auto& v : voices_) {
                if (!v.active) continue;
                const double pitchBend = std::pow(2.0, patch_.lfoToPitch * lfo / 12.0);
                v.osc1.setFrequency(v.frequency * pitchBend, sampleRate_);
                v.osc2.setFrequency(v.frequency * pitchBend *
                    std::pow(2.0, patch_.osc2DetuneSemitones / 12.0), sampleRate_);

                const float o1 = v.osc1.next(patch_.osc1Shape);
                const float o2 = v.osc2.next(patch_.osc2Shape);
                const float mix = static_cast<float>(patch_.oscMix);
                float x = o1 * (1.f - mix) + o2 * mix;

                const float envAmt = static_cast<float>(v.filterEnv.next());
                const double cutoff = patch_.filterCutoff *
                    std::pow(2.0, envAmt * patch_.filterEnvAmount * 4.0);
                v.filter.setTarget(cutoff, patch_.filterQ);
                x = v.filter.process(x);

                const double amp = v.ampEnv.next();
                if (v.ampEnv.isIdle()) v.active = false;
                sample += static_cast<float>(x * amp * v.velocityGain);
            }
            out[i] += sample * static_cast<float>(patch_.gain);
        }
    }

private:
    struct Voice {
        bool active{false};
        int key{0};
        double frequency{440.0};
        double velocityGain{1.0};
        PolyBlepOsc osc1, osc2;
        SvfFilter filter;
        studioone::dsp::AdsrEnvelope ampEnv, filterEnv;
    };

    double sampleRate_{44100.0};
    Patch patch_;
    std::array<Voice, kMaxVoices> voices_;
    double lfoPhase_{0.0};
    int stealIndex_{0};
};

}  // namespace studioone::instruments
