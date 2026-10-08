#pragma once
// Volt-1: 2-oscillator subtractive voice with polyBLEP band-limiting,
// 12/24dB SVF filter, dual ADSR (amp + filter), one LFO (mod wheel routing),
// per-voice glide, velocity->filter & velocity->amp curves, and MPE support
// (pressure -> filter amount, timbre -> osc2 mix, slide -> per-voice bend).
//
// Oscillators use polyBLEP residual correction so saw/square are aliasing-
// free across the full keyboard at 44.1k — essential for mobile where we
// cannot afford oversampling.

#include "Voice.h"
#include "../dsp/Envelope.h"
#include <cmath>

namespace s1::audio::instrument {

enum OscWave : int { kWavSine = 0, kWavSaw = 1, kWavSquare = 2, kWavTriangle = 3, kWavPulse25 = 4 };

/** Band-limited oscillator via polyBLEP. */
class PolyBlepOsc {
public:
    void setWave(OscWave w) { wave_ = w; }
    void setSampleRate(float sr) { sr_ = sr; }
    void setFrequency(float hz) {
        hz = std::min(hz, sr_ * 0.45f);
        phaseInc_ = hz / sr_;
    }
    void setPhase(float p) { phase_ = p; }
    float phase() const { return phase_; }

    inline float next() {
        const float t = phase_;
        const float dt = phaseInc_;
        float out = 0.f;
        switch (wave_) {
            case kWavSine:
                out = std::sin(2.f * static_cast<float>(M_PI) * t);
                break;
            case kWavSaw: {
                float naive = 2.f * t - 1.f;
                naive -= polyBlep(t, dt);
                out = naive;
                break;
            }
            case kWavSquare: {
                float naive = t < 0.5f ? 1.f : -1.f;
                naive += polyBlep(t, dt);
                naive -= polyBlep(std::fmod(t + 0.5f, 1.f), dt);
                out = naive * 0.7f; // square sits hotter; normalize a touch
                break;
            }
            case kWavPulse25: {
                float naive = t < 0.25f ? 1.f : -1.f;
                naive += polyBlep(t, dt);
                naive -= polyBlep(std::fmod(t + 0.75f, 1.f), dt);
                out = naive * 0.7f;
                break;
            }
            case kWavTriangle: {
                // Triangle from integrated square + BLEP (leaky integrator).
                float sq = t < 0.5f ? 1.f : -1.f;
                sq += polyBlep(t, dt);
                sq -= polyBlep(std::fmod(t + 0.5f, 1.f), dt);
                triInt_ += sq * dt * 4.f;           // integrate
                triInt_ *= 0.999f;                   // leak to avoid DC drift
                out = triInt_;
                break;
            }
        }
        phase_ += dt;
        if (phase_ >= 1.f) phase_ -= 1.f;
        return out;
    }

    void reset() { phase_ = 0.f; triInt_ = 0.f; }

private:
    static inline float polyBlep(float t, float dt) {
        if (t < dt) {
            t /= dt;
            return t + t - t * t - 1.f;
        }
        if (t > 1.f - dt) {
            t = (t - 1.f) / dt;
            return t * t + t + t + 1.f;
        }
        return 0.f;
    }
    OscWave wave_ = kWavSaw;
    float sr_ = 48000.f;
    float phase_ = 0.f;
    float phaseInc_ = 0.002f;
    float triInt_ = 0.f;
};

/** Stilson-Croom SVF (state variable filter), 2 passes for 24dB mode. */
class SvfFilter {
public:
    void setSampleRate(float sr) { sr_ = sr; }
    void setParams(float cutoffHz, float resonance01) {
        const float f = 2.f * std::sin(static_cast<float>(M_PI) *
                                       std::min(cutoffHz, sr_ * 0.42f) / sr_);
        f_ = f;
        q_ = 2.f - resonance01 * 1.9f; // damping term
    }
    inline float processLP(float in) {
        lp_ += f_ * bp_;
        const float hp = in - lp_ - q_ * bp_;
        bp_ += f_ * hp;
        return lp_;
    }
    void reset() { lp_ = bp_ = 0.f; }
private:
    float sr_ = 48000.f, f_ = 0.1f, q_ = 1.f;
    float lp_ = 0.f, bp_ = 0.f;
};

struct SynthPreset {
    // Oscillators
    OscWave osc1Wave = kWavSaw;
    OscWave osc2Wave = kWavSaw;
    float osc2DetuneCents = 8.f;
    float osc2Mix = 0.5f;          // 0..1 crossfade osc1/osc2
    float osc2Octave = 0.f;        // -2..2
    float subMix = 0.f;
    // Filter
    float filterCutoffHz = 8000.f;
    float filterResonance = 0.2f;
    float filterEnvAmount = 0.4f;  // 0..1 -> octaves of sweep
    bool filter24dB = false;
    // Amp envelope (ms / 0..1)
    float ampAttackMs = 2.f, ampDecayMs = 200.f, ampSustain = 0.8f, ampReleaseMs = 250.f;
    // Filter envelope
    float fltAttackMs = 2.f, fltDecayMs = 400.f, fltSustain = 0.3f, fltReleaseMs = 300.f;
    // LFO
    float lfoRateHz = 5.f;
    float lfoToPitch = 0.f;        // cents
    float lfoToFilter = 0.f;       // 0..1
    // Modulation / play
    float glideMs = 0.f;
    float velocityToFilter = 0.5f;
    float velocityToAmp = 1.f;
    float level = 0.8f;
};

class SubtractiveVoice : public Voice {
public:
    void setSampleRate(float sr) override;
    void noteOn(uint8_t key, uint8_t velocity) override;
    void noteOff(uint8_t key) override;
    void render(float* left, float* right, FrameCount frames) override;
    bool isActive() const override { return !ampEnv_.isIdle(); }
    void allSoundOff() override;
    int32_t currentKey() const override { return key_; }
    void setPressure(float v) override { pressure_ = v; }
    void setPitchBend(float v) override { bendNorm_ = v; }
    void setTimbre(float v) override { timbre_ = v; }

    void applyPreset(const SynthPreset& preset);
    void setMpeEnabled(bool on) { mpe_ = on; }

private:
    float keyFrequency() const;

    PolyBlepOsc osc1_, osc2_, sub_;
    SvfFilter filter_;
    dsp::AdsrEnvelope ampEnv_, fltEnv_;
    SynthPreset preset_{};
    float sr_ = 48000.f;
    uint8_t key_ = 0;
    float velocityNorm_ = 0.8f;
    float pressure_ = 0.f, timbre_ = 0.5f, bendNorm_ = 0.f;
    bool mpe_ = false;
    bool released_ = true;
    float lfoPhase_ = 0.f;
    float lfoPitchMod_ = 0.f; // cents, written per-sample in render()
    float glideTargetHz_ = 0.f, glideCurrentHz_ = 0.f, glideCoef_ = 1.f;
};

} // namespace s1::audio::instrument
