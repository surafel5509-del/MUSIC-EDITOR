// SPDX-License-Identifier: MIT
// Time-based, modulation and saturation effects.
#pragma once

#include <cmath>
#include <array>
#include "effects.h"

namespace studioone::dsp {

/** Tempo-agnostic feedback delay with one-pole damping in the loop. */
class DelayFx {
public:
    void prepare(double sampleRate) {
        sampleRate_ = sampleRate;
        line_.prepare(static_cast<size_t>(2.5 * sampleRate));
        time_.prepare(sampleRate); feedback_.prepare(sampleRate); mix_.prepare(sampleRate);
        time_.snap(0.375f); feedback_.snap(0.35f); mix_.snap(0.25f); damp_.prepare(sampleRate);
        damp_.configure(BiquadType::LowPass, 8000.0, 0.0, 0.707);
    }
    // 0 time_ms, 1 feedback, 2 damping_hz, 3 mix
    void setParameter(uint32_t id, float v) noexcept {
        switch (id) {
            case 0: time_.set(v / 1000.f); break;
            case 1: feedback_.set(v); break;
            case 2: damp_.configure(BiquadType::LowPass, v, 0.0, 0.707); break;
            case 3: mix_.set(v); break;
        }
    }

    void process(float* const* channels, int numChannels, int numFrames) noexcept {
        for (int i = 0; i < numFrames; ++i) {
            float in = 0.f;
            for (int c = 0; c < numChannels; ++c) in += channels[c][i] / numChannels;
            const double delaySeconds = time_.next();
            const float delayed = line_.read(delaySeconds * sampleRate_);
            const float fb = delayed * feedback_.next();
            line_.write(in + static_cast<float>(damp_.process(fb)));
            const float wet = delayed * mix_.next();
            for (int c = 0; c < numChannels; ++c) channels[c][i] += wet;
        }
    }

private:
    double sampleRate_{44100.0};
    DelayLine line_;
    SmoothedParam time_, feedback_, mix_;
    Biquad damp_;
};

/** Freeverb-style Schroeder reverb: 4 parallel combs + 2 series allpasses. */
class Reverb {
public:
    static constexpr int kCombs = 4;
    static constexpr int kAllpasses = 2;

    void prepare(double sampleRate) {
        sampleRate_ = sampleRate;
        static const double combTimes[kCombs] = {0.0297, 0.0371, 0.0411, 0.0437};
        static const double apTimes[kAllpasses] = {0.005, 0.0017};
        for (int i = 0; i < kCombs; ++i) {
            combs_[i].prepare(static_cast<size_t>(combTimes[i] * sampleRate) + 8);
            combDelaySec_[i] = combTimes[i];
        }
        for (int i = 0; i < kAllpasses; ++i) {
            allpasses_[i].prepare(static_cast<size_t>(apTimes[i] * sampleRate) + 8);
            apDelaySec_[i] = apTimes[i];
        }
        decay_.prepare(sampleRate); mix_.prepare(sampleRate); predelay_.prepare(sampleRate);
        decay_.snap(0.7f); mix_.snap(0.3f); predelay_.prepare(sampleRate);
        predelayLine_.prepare(static_cast<size_t>(0.2 * sampleRate));
    }
    // 0 decay_s, 1 predelay_ms, 2 damping_hz, 3 size, 4 mix
    void setParameter(uint32_t id, float v) noexcept {
        switch (id) {
            case 0: decay_.set(std::clamp(v / 12.f, 0.f, 0.98f)); break;
            case 1: predelayMs_ = v; break;
            case 2: dampingHz_ = v; break;
            case 4: mix_.set(v); break;
        }
    }

    void process(float* const* channels, int numChannels, int numFrames) noexcept {
        const float fb = decay_.next();
        const float mix = mix_.next();
        for (int i = 0; i < numFrames; ++i) {
            float in = 0.f;
            for (int c = 0; c < numChannels; ++c) in += channels[c][i] / numChannels;
            predelayLine_.write(in);
            const float pd = predelayLine_.read(predelayMs_ / 1000.0 * sampleRate_);

            float wet = 0.f;
            for (int c = 0; c < kCombs; ++c) {
                const float out = combs_[c].read(combDelaySec_[c] * sampleRate_);
                combs_[c].write(pd + out * fb);
                wet += out;
            }
            for (int a = 0; a < kAllpasses; ++a) {
                const float delayed = allpasses_[a].read(apDelaySec_[a] * sampleRate_);
                const float out = -wet + delayed;
                allpasses_[a].write(wet + delayed * 0.5f);
                wet = out;
            }
            wet *= 0.25f;
            for (int c = 0; c < numChannels; ++c) {
                channels[c][i] = channels[c][i] * (1.f - mix) + wet * mix;
            }
        }
    }

private:
    double sampleRate_{44100.0};
    DelayLine combs_[kCombs];
    DelayLine allpasses_[kAllpasses];
    double combDelaySec_[kCombs]{};
    double apDelaySec_[kAllpasses]{};
    DelayLine predelayLine_;
    SmoothedParam decay_, mix_, predelay_;
    float predelayMs_{20.f};
    float dampingHz_{6000.f};
};

/** Chorus/flanger: LFO-modulated delay mixed with dry. */
class ChorusFx {
public:
    void prepare(double sampleRate) {
        sampleRate_ = sampleRate;
        line_.prepare(static_cast<size_t>(0.05 * sampleRate));
        rate_.prepare(sampleRate); depth_.prepare(sampleRate); mix_.prepare(sampleRate);
        rate_.snap(0.8f); depth_.snap(0.0025f); mix_.snap(0.5f);
    }
    // 0 rate_hz, 1 depth_ms, 2 mix, 3 feedback (flanger mode)
    void setParameter(uint32_t id, float v) noexcept {
        switch (id) {
            case 0: rate_.set(v); break;
            case 1: depth_.set(v / 1000.f); break;
            case 2: mix_.set(v); break;
            case 3: feedback_.set(v); break;
        }
    }

    void process(float* const* channels, int numChannels, int numFrames) noexcept {
        const float rate = rate_.next();
        const float depth = depth_.next();
        const float mix = mix_.next();
        const float fb = feedback_.next();
        for (int i = 0; i < numFrames; ++i) {
            phase_ += 2.0 * M_PI * rate / sampleRate_;
            if (phase_ > 2.0 * M_PI) phase_ -= 2.0 * M_PI;
            const double mod = depth * (0.5 + 0.5 * std::sin(phase_)) + 0.0002;
            float in = 0.f;
            for (int c = 0; c < numChannels; ++c) in += channels[c][i] / numChannels;
            const float delayed = line_.read(mod * sampleRate_);
            line_.write(in + delayed * fb);
            const float wet = delayed * mix;
            for (int c = 0; c < numChannels; ++c) {
                channels[c][i] = channels[c][i] * (1.f - mix) + wet;
            }
        }
    }

private:
    double sampleRate_{44100.0};
    DelayLine line_;
    SmoothedParam rate_, depth_, mix_, feedback_;
    double phase_{0.0};
};

/** Four-stage phaser built from LFO-modulated allpass filters. */
class PhaserFx {
public:
    static constexpr int kStages = 4;

    void prepare(double sampleRate) {
        sampleRate_ = sampleRate;
        rate_.prepare(sampleRate); depth_.prepare(sampleRate); feedback_.prepare(sampleRate);
        rate_.snap(0.6f); depth_.snap(0.8f); feedback_.snap(0.4f);
        for (auto& ap : stages_) ap.prepare(sampleRate);
    }
    // 0 rate_hz, 1 depth, 2 stages(ignored, fixed), 3 feedback
    void setParameter(uint32_t id, float v) noexcept {
        switch (id) {
            case 0: rate_.set(v); break;
            case 1: depth_.set(v); break;
            case 3: feedback_.set(v); break;
        }
    }

    void process(float* const* channels, int numChannels, int numFrames) noexcept {
        const float rate = rate_.next();
        const float depth = depth_.next();
        const float fb = feedback_.next();
        for (int i = 0; i < numFrames; ++i) {
            phase_ += 2.0 * M_PI * rate / sampleRate_;
            if (phase_ > 2.0 * M_PI) phase_ -= 2.0 * M_PI;
            const double lfo = 0.5 + 0.5 * std::sin(phase_);
            const double freq = 200.0 + lfo * depth * 6000.0;
            for (auto& ap : stages_) ap.configure(BiquadType::AllPass, freq, 0.0, 0.707);

            for (int c = 0; c < numChannels; ++c) {
                double x = channels[c][i] + lastFb_[c] * fb;
                for (auto& ap : stages_) x = ap.process(x, c);
                lastFb_[c] = static_cast<float>(x);
                channels[c][i] = (channels[c][i] + static_cast<float>(x)) * 0.5f;
            }
        }
    }

private:
    double sampleRate_{44100.0};
    SmoothedParam rate_, depth_, feedback_;
    std::array<Biquad, kStages> stages_;
    std::array<float, 2> lastFb_{0.f, 0.f};
    double phase_{0.0};
};

/** Amplitude tremolo with selectable waveform. */
class TremoloFx {
public:
    void prepare(double sampleRate) {
        sampleRate_ = sampleRate;
        rate_.prepare(sampleRate); depth_.prepare(sampleRate);
        rate_.snap(5.f); depth_.snap(0.8f);
    }
    // 0 rate_hz, 1 depth, 2 waveform (0 sine, 1 triangle, 2 square, 3 saw)
    void setParameter(uint32_t id, float v) noexcept {
        switch (id) {
            case 0: rate_.set(v); break;
            case 1: depth_.set(v); break;
            case 2: waveform_ = static_cast<int>(v) % 4; break;
        }
    }

    void process(float* const* channels, int numChannels, int numFrames) noexcept {
        const float rate = rate_.next();
        const float depth = depth_.next();
        for (int i = 0; i < numFrames; ++i) {
            phase_ += rate / sampleRate_;
            if (phase_ > 1.0) phase_ -= 1.0;
            double lfo;
            switch (waveform_) {
                case 1: lfo = 2.0 * std::abs(2.0 * (phase_ - std::floor(phase_ + 0.5))) - 1.0; break;
                case 2: lfo = phase_ < 0.5 ? 1.0 : -1.0; break;
                case 3: lfo = 2.0 * phase_ - 1.0; break;
                default: lfo = std::sin(2.0 * M_PI * phase_); break;
            }
            const float gain = 1.f - depth * static_cast<float>(0.5 + 0.5 * lfo);
            for (int c = 0; c < numChannels; ++c) channels[c][i] *= gain;
        }
    }

private:
    double sampleRate_{44100.0};
    SmoothedParam rate_, depth_;
    int waveform_{0};
    double phase_{0.0};
};

/** Waveshaping distortion with post tone filter. */
class DistortionFx {
public:
    void prepare(double sampleRate) {
        tone_.prepare(sampleRate);
        tone_.configure(BiquadType::LowPass, 3500.0, 0.0, 0.707);
        driveDb_.prepare(sampleRate); mix_.prepare(sampleRate);
        driveDb_.snap(18.f); mix_.snap(1.f);
    }
    // 0 drive_db, 1 tone_hz, 2 mix
    void setParameter(uint32_t id, float v) noexcept {
        switch (id) {
            case 0: driveDb_.set(v); break;
            case 1: tone_.configure(BiquadType::LowPass, v, 0.0, 0.707); break;
            case 2: mix_.set(v); break;
        }
    }

    void process(float* const* channels, int numChannels, int numFrames) noexcept {
        const double drive = std::pow(10.0, driveDb_.next() / 20.0);
        const double makeup = 1.0 / std::sqrt(drive);
        const float mix = mix_.next();
        for (int i = 0; i < numFrames; ++i) {
            for (int c = 0; c < numChannels; ++c) {
                const float dry = channels[c][i];
                const float shaped = static_cast<float>(std::tanh(dry * drive) * makeup);
                channels[c][i] = dry * (1.f - mix) + static_cast<float>(tone_.process(shaped, c)) * mix;
            }
        }
    }

private:
    SmoothedParam driveDb_, mix_;
    Biquad tone_;
};

/** Bit-depth crusher with sample-rate reduction. */
class BitcrusherFx {
public:
    void prepare(double) { bits_.prepare(44100); downsample_.prepare(44100); bits_.snap(12.f); downsample_.snap(1.f); }
    // 0 bits, 1 downsample factor
    void setParameter(uint32_t id, float v) noexcept {
        switch (id) {
            case 0: bits_.set(v); break;
            case 1: downsample_.set(v); break;
        }
    }

    void process(float* const* channels, int numChannels, int numFrames) noexcept {
        const int bits = static_cast<int>(bits_.next());
        const int step = std::max(static_cast<int>(downsample_.next()), 1);
        const double levels = std::pow(2.0, bits - 1);
        for (int i = 0; i < numFrames; ++i) {
            if (i % step == 0) {
                for (int c = 0; c < numChannels; ++c) {
                    held_[c] = std::round(channels[c][i] * levels) / levels;
                }
            }
            for (int c = 0; c < numChannels; ++c) channels[c][i] = held_[c];
        }
    }

private:
    SmoothedParam bits_, downsample_;
    std::array<float, 2> held_{0.f, 0.f};
};

/** 3-band parametric EQ + HP/LP filters (matches EffectCatalog.PARAMETRIC_EQ). */
class ParametricEq {
public:
    void prepare(double sampleRate) {
        for (auto& band : bands_) band.prepare(sampleRate);
        hp_.prepare(sampleRate); lp_.prepare(sampleRate);
        hp_.configure(BiquadType::HighPass, 20.0, 0.0, 0.707);
        lp_.configure(BiquadType::LowPass, 20000.0, 0.0, 0.707);
        bands_[0].configure(BiquadType::Peaking, 250.0, 0.0, 0.9);
        bands_[1].configure(BiquadType::Peaking, 1200.0, 0.0, 1.0);
        bands_[2].configure(BiquadType::Peaking, 6000.0, 0.0, 1.0);
    }
    // Ids follow EffectCatalog order: hp_freq, band{1,2,3}_{freq,gain,q}, lp_freq
    void setParameter(uint32_t id, float v) noexcept {
        switch (id) {
            case 0: hp_.configure(BiquadType::HighPass, v, 0.0, 0.707); break;
            case 1: bands_[0].configure(BiquadType::Peaking, v, g_[0], q_[0]); break;
            case 2: g_[0] = v; bands_[0].configure(BiquadType::Peaking, f_[0] = f_[0] > 0 ? f_[0] : 250.f, v, q_[0]); break;
            case 3: q_[0] = v; break;
            case 4: bands_[1].configure(BiquadType::Peaking, v, g_[1], q_[1]); break;
            case 5: g_[1] = v; break;
            case 6: q_[1] = v; break;
            case 7: bands_[2].configure(BiquadType::Peaking, v, g_[2], q_[2]); break;
            case 8: g_[2] = v; break;
            case 9: q_[2] = v; break;
            case 10: lp_.configure(BiquadType::LowPass, v, 0.0, 0.707); break;
        }
    }

    void process(float* const* channels, int numChannels, int numFrames) noexcept {
        for (int i = 0; i < numFrames; ++i) {
            for (int c = 0; c < numChannels; ++c) {
                double x = hp_.process(channels[c][i], c);
                for (auto& band : bands_) x = band.process(x, c);
                channels[c][i] = static_cast<float>(lp_.process(x, c));
            }
        }
    }

private:
    std::array<Biquad, 3> bands_;
    Biquad hp_, lp_;
    float f_[3]{250.f, 1200.f, 6000.f};
    float g_[3]{0.f, 0.f, 0.f};
    float q_[3]{0.9f, 1.f, 1.f};
};

}  // namespace studioone::dsp
