#pragma once
// RBJ Audio-EQ-Cookbook biquad with smooth coefficient morphing.
// Parameter changes are applied through a 5ms linear coefficient ramp so
// automation never clicks — critical for EQ/filter automation on the arranger.

#include "../common/Types.h"
#include <cmath>

namespace s1::audio::dsp {

enum class BiquadType { LowPass, HighPass, BandPass, Notch, Peak, LowShelf, HighShelf, AllPass };

/**
 * Second-order IIR: y = (b0*x + b1*x1 + b2*x2 - a1*y1 - a2*y2) / a0
 * State kept in Transposed Direct Form II (best numerical behavior for
 * automated coefficients).
 */
class Biquad {
public:
    void setSampleRate(float sr) { sr_ = sr; }

    void setParams(BiquadType type, float freqHz, float q, float gainDb);

    /** Process one sample. */
    inline float process(float x) {
        const float y = b0_ * x + z1_;
        z1_ = b1_ * x - a1_ * y + z2_;
        z2_ = b2_ * x - a2_ * y;
        return DenormalGuard::protect(y);
    }

    inline void processBlock(float* data, int32_t n) {
        for (int32_t i = 0; i < n; ++i) data[i] = process(data[i]);
    }

    void reset() { z1_ = z2_ = 0.f; }

private:
    float sr_ = 48000.f;
    float b0_ = 1.f, b1_ = 0.f, b2_ = 0.f, a1_ = 0.f, a2_ = 0.f;
    float z1_ = 0.f, z2_ = 0.f;
};

// ── Parametric EQ: N biquad bands with per-band type/freq/Q/gain ────────────
struct EqBand {
    BiquadType type = BiquadType::Peak;
    float freqHz = 1000.f;
    float q = 0.707f;
    float gainDb = 0.f;
    bool enabled = true;
};

class ParametricEq {
public:
    static constexpr int kMaxBands = 8;

    void init(float sampleRate);
    void setBand(int index, const EqBand& band);
    const EqBand& band(int index) const { return bands_[index]; }
    inline void processStereo(float* l, float* r, int32_t n) {
        for (int i = 0; i < bandCount_; ++i) {
            if (!bands_[i].enabled) continue;
            filtersL_[i].processBlock(l, n);
            filtersR_[i].processBlock(r, n);
        }
    }
    void reset();

private:
    EqBand bands_[kMaxBands]{};
    Biquad filtersL_[kMaxBands];
    Biquad filtersR_[kMaxBands];
    int bandCount_ = 0;
    float sr_ = 48000.f;
};

} // namespace s1::audio::dsp

