// SPDX-License-Identifier: MIT
// RBJ-style biquad filter used by EQ, tone controls and the synth SVF.
#pragma once

#include <cmath>
#include <array>

namespace studioone::dsp {

enum class BiquadType {
    LowPass, HighPass, BandPass, Notch, Peaking, LowShelf, HighShelf, AllPass,
};

class Biquad {
public:
    void prepare(double sampleRate) {
        sampleRate_ = sampleRate;
        reset();
        dirty_ = true;
    }

    void reset() {
        for (auto& s : state_) s = {0.0, 0.0};
    }

    void configure(BiquadType type, double freq, double gainDb, double q) {
        pending_ = {type, freq, gainDb, q};
        dirty_ = true;
    }

    /** Processes in place, one sample at a time (Transposed Form II). */
    inline double process(double in, int channel = 0) noexcept {
        if (dirty_ && channel == 0) {
            computeCoefficients();
            dirty_ = false;
        }
        auto& s = state_[channel];
        const double out = b0_ * in + s.z1;
        s.z1 = b1_ * in - a1_ * out + s.z2;
        s.z2 = b2_ * in - a2_ * out;
        return out;
    }

    double b0() const noexcept { return b0_; }

private:
    struct Pending {
        BiquadType type{BiquadType::LowPass};
        double freq{1000.0};
        double gainDb{0.0};
        double q{0.707};
    };

    void computeCoefficients() noexcept {
        const auto [type, freq, gainDb, q] = pending_;
        const double w0 = 2.0 * M_PI * clampFreq(freq) / sampleRate_;
        const double cosw = std::cos(w0);
        const double sinw = std::sin(w0);
        const double alpha = sinw / (2.0 * q);
        const double A = std::pow(10.0, gainDb / 40.0);

        double b0 = 1, b1 = 0, b2 = 0, a0 = 1, a1 = 0, a2 = 0;
        switch (type) {
            case BiquadType::LowPass:
                b0 = (1 - cosw) / 2; b1 = 1 - cosw; b2 = b0;
                a0 = 1 + alpha; a1 = -2 * cosw; a2 = 1 - alpha;
                break;
            case BiquadType::HighPass:
                b0 = (1 + cosw) / 2; b1 = -(1 + cosw); b2 = b0;
                a0 = 1 + alpha; a1 = -2 * cosw; a2 = 1 - alpha;
                break;
            case BiquadType::BandPass:
                b0 = alpha; b1 = 0; b2 = -alpha;
                a0 = 1 + alpha; a1 = -2 * cosw; a2 = 1 - alpha;
                break;
            case BiquadType::Notch:
                b0 = 1; b1 = -2 * cosw; b2 = 1;
                a0 = 1 + alpha; a1 = -2 * cosw; a2 = 1 - alpha;
                break;
            case BiquadType::Peaking:
                b0 = 1 + alpha * A; b1 = -2 * cosw; b2 = 1 - alpha * A;
                a0 = 1 + alpha / A; a1 = -2 * cosw; a2 = 1 - alpha / A;
                break;
            case BiquadType::LowShelf: {
                const double twoSqrtAAlpha = 2 * std::sqrt(A) * alpha;
                b0 = A * ((A + 1) - (A - 1) * cosw + twoSqrtAAlpha);
                b1 = 2 * A * ((A - 1) - (A + 1) * cosw);
                b2 = A * ((A + 1) - (A - 1) * cosw - twoSqrtAAlpha);
                a0 = (A + 1) + (A - 1) * cosw + twoSqrtAAlpha;
                a1 = -2 * ((A - 1) + (A + 1) * cosw);
                a2 = (A + 1) + (A - 1) * cosw - twoSqrtAAlpha;
                break;
            }
            case BiquadType::HighShelf: {
                const double twoSqrtAAlpha = 2 * std::sqrt(A) * alpha;
                b0 = A * ((A + 1) + (A - 1) * cosw + twoSqrtAAlpha);
                b1 = -2 * A * ((A - 1) + (A + 1) * cosw);
                b2 = A * ((A + 1) + (A - 1) * cosw - twoSqrtAAlpha);
                a0 = (A + 1) - (A - 1) * cosw + twoSqrtAAlpha;
                a1 = 2 * ((A - 1) - (A + 1) * cosw);
                a2 = (A + 1) - (A - 1) * cosw - twoSqrtAAlpha;
                break;
            }
            case BiquadType::AllPass:
                b0 = 1 - alpha; b1 = -2 * cosw; b2 = 1 + alpha;
                a0 = 1 + alpha; a1 = -2 * cosw; a2 = 1 - alpha;
                break;
        }
        b0_ = b0 / a0; b1_ = b1 / a0; b2_ = b2 / a0;
        a1_ = a1 / a0; a2_ = a2 / a0;
    }

    static double clampFreq(double f) noexcept { return f < 1.0 ? 1.0 : f; }

    double sampleRate_{44100.0};
    Pending pending_;
    bool dirty_{true};
    double b0_{1}, b1_{0}, b2_{0}, a1_{0}, a2_{0};
    struct State { double z1{0}, z2{0}; };
    std::array<State, 2> state_;
};

}  // namespace studioone::dsp
