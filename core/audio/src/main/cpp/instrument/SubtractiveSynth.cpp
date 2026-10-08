#include "SubtractiveSynth.h"
#include "../common/FastMath.h"
#include <algorithm>
#include <cmath>

namespace s1::audio::instrument {

using namespace math;

void SubtractiveVoice::setSampleRate(float sr) {
    sr_ = sr;
    osc1_.setSampleRate(sr);
    osc2_.setSampleRate(sr);
    sub_.setSampleRate(sr);
    filter_.setSampleRate(sr);
}

void SubtractiveVoice::applyPreset(const SynthPreset& preset) {
    preset_ = preset;
    osc1_.setWave(preset.osc1Wave);
    osc2_.setWave(preset.osc2Wave);
    sub_.setWave(kWavSine);
    ampEnv_.setParams(preset.ampAttackMs, preset.ampDecayMs, preset.ampSustain, preset.ampReleaseMs, sr_);
    fltEnv_.setParams(preset.fltAttackMs, preset.fltDecayMs, preset.fltSustain, preset.fltReleaseMs, sr_);
    glideCoef_ = preset.glideMs > 0.5f
        ? std::exp(-1.f / (preset.glideMs * 0.001f * sr_))
        : 0.f;
}

float SubtractiveVoice::keyFrequency() const {
    // Base + MPE/bend: bendNorm -1..1 over ±48 semitones when MPE, else ±2.
    const float bendSemis = mpe_ ? bendNorm_ * 48.f : bendNorm_ * 2.f;
    const float cents = (key_ - 69) * 100.f + bendSemis * 100.f + lfoPitchMod_;
    return 440.f * std::pow(2.f, cents / 1200.f);
}

void SubtractiveVoice::noteOn(uint8_t key, uint8_t velocity) {
    key_ = key;
    velocityNorm_ = std::max(1, static_cast<int>(velocity)) / 127.f;
    released_ = false;
    ampEnv_.noteOn();
    fltEnv_.noteOn();
    const float hz = keyFrequency();
    glideTargetHz_ = hz;
    if (glideCoef_ <= 0.f || glideCurrentHz_ <= 0.f) glideCurrentHz_ = hz;
}

void SubtractiveVoice::noteOff(uint8_t key) {
    if (key == key_) {
        ampEnv_.noteOff();
        fltEnv_.noteOff();
        released_ = true;
    }
}

void SubtractiveVoice::allSoundOff() {
    ampEnv_.noteOff();
    fltEnv_.noteOff();
    released_ = true;
}

void SubtractiveVoice::render(float* left, float* right, FrameCount frames) {
    if (ampEnv_.isIdle() && released_) return;

    // Per-block LFO advance (rate modulated by macros later if desired).
    const float lfoInc = preset_.lfoRateHz / sr_;
    const float vel = velocityNorm_;
    const float level = preset_.level *
        (preset_.velocityToAmp > 0.f ? (1.f - preset_.velocityToAmp) + preset_.velocityToAmp * vel : 1.f);
    const float osc2Mix = mpe_ ? std::clamp(timbre_, 0.f, 1.f) : preset_.osc2Mix;
    const float detuneRatio = std::pow(2.f, (preset_.osc2DetuneCents + (mpe_ ? pressure_ * 30.f : 0.f)) / 1200.f);
    const float osc2FreqScale = std::pow(2.f, preset_.osc2Octave);

    for (FrameCount i = 0; i < frames; ++i) {
        // LFO (sine) -> pitch vibrato + filter wobble.
        lfoPhase_ += lfoInc;
        if (lfoPhase_ >= 1.f) lfoPhase_ -= 1.f;
        const float lfo = std::sin(kTwoPi * lfoPhase_);
        lfoPitchMod_ = lfo * preset_.lfoToPitch;

        // Glide toward target frequency.
        if (glideCoef_ > 0.f) {
            glideCurrentHz_ = glideCoef_ * glideCurrentHz_ + (1.f - glideCoef_) * glideTargetHz_;
        } else {
            glideCurrentHz_ = glideTargetHz_;
        }
        const float baseHz = glideCurrentHz_;

        osc1_.setFrequency(baseHz);
        osc2_.setFrequency(baseHz * osc2FreqScale * detuneRatio);
        sub_.setFrequency(baseHz * 0.5f);

        const float o1 = osc1_.next();
        const float o2 = osc2_.next();
        float sig = o1 * (1.f - osc2Mix * 0.5f) + o2 * (osc2Mix * 0.5f);
        sig += sub_.next() * preset_.subMix * 0.5f;

        // Filter envelope + key tracking + velocity + MPE pressure + LFO.
        const float fltEnv = fltEnv_.process();
        const float envOctaves = preset_.filterEnvAmount * 4.f * fltEnv;
        const float velOctaves = preset_.velocityToFilter * (vel - 0.5f) * 2.f;
        const float pressOctaves = mpe_ ? pressure_ * 1.5f : 0.f;
        const float lfoOctaves = preset_.lfoToFilter * lfo * 0.5f;
        const float cutoff = std::clamp(
            preset_.filterCutoffHz *
                std::pow(2.f, envOctaves + velOctaves + pressOctaves + lfoOctaves + (key_ - 60) * 0.02f),
            20.f, sr_ * 0.42f);
        filter_.setParams(cutoff, preset_.filterResonance);

        float out = filter_.processLP(sig);
        if (preset_.filter24dB) out = filter_.processLP(out);

        const float amp = ampEnv_.process() * level;
        out *= amp;

        left[i] += out;
        right[i] += out;

        if (ampEnv_.isIdle() && released_) break; // voice finished mid-block
    }
}

} // namespace s1::audio::instrument
