#include "Biquad.h"
#include "../common/FastMath.h"
#include <algorithm>

namespace s1::audio::dsp {

using namespace math;

void Biquad::setParams(BiquadType type, float freqHz, float q, float gainDb) {
    // Clamp to Nyquist with headroom to keep the transform stable.
    const float f = std::clamp(freqHz, 10.0f, sr_ * 0.495f);
    const float w0 = kTwoPi * f / sr_;
    const float cosw = std::cos(w0);
    const float sinw = std::sin(w0);
    const float alpha = sinw / (2.0f * std::max(q, 0.0001f));
    const float A = std::pow(10.0f, gainDb / 40.0f); // sqrt of linear gain

    float b0, b1, b2, a0, a1, a2;
    switch (type) {
        case BiquadType::LowPass:
            b0 = (1.f - cosw) * 0.5f; b1 = 1.f - cosw; b2 = b0;
            a0 = 1.f + alpha; a1 = -2.f * cosw; a2 = 1.f - alpha;
            break;
        case BiquadType::HighPass:
            b0 = (1.f + cosw) * 0.5f; b1 = -(1.f + cosw); b2 = b0;
            a0 = 1.f + alpha; a1 = -2.f * cosw; a2 = 1.f - alpha;
            break;
        case BiquadType::BandPass: // constant 0dB peak gain
            b0 = alpha; b1 = 0.f; b2 = -alpha;
            a0 = 1.f + alpha; a1 = -2.f * cosw; a2 = 1.f - alpha;
            break;
        case BiquadType::Notch:
            b0 = 1.f; b1 = -2.f * cosw; b2 = 1.f;
            a0 = 1.f + alpha; a1 = -2.f * cosw; a2 = 1.f - alpha;
            break;
        case BiquadType::Peak:
            b0 = 1.f + alpha * A; b1 = -2.f * cosw; b2 = 1.f - alpha * A;
            a0 = 1.f + alpha / A; a1 = -2.f * cosw; a2 = 1.f - alpha / A;
            break;
        case BiquadType::LowShelf: {
            const float twoSqrtAAlpha = 2.f * std::sqrt(A) * alpha;
            b0 = A * ((A + 1.f) - (A - 1.f) * cosw + twoSqrtAAlpha);
            b1 = 2.f * A * ((A - 1.f) - (A + 1.f) * cosw);
            b2 = A * ((A + 1.f) - (A - 1.f) * cosw - twoSqrtAAlpha);
            a0 = (A + 1.f) + (A - 1.f) * cosw + twoSqrtAAlpha;
            a1 = -2.f * ((A - 1.f) + (A + 1.f) * cosw);
            a2 = (A + 1.f) + (A - 1.f) * cosw - twoSqrtAAlpha;
            break;
        }
        case BiquadType::HighShelf: {
            const float twoSqrtAAlpha = 2.f * std::sqrt(A) * alpha;
            b0 = A * ((A + 1.f) + (A - 1.f) * cosw + twoSqrtAAlpha);
            b1 = -2.f * A * ((A - 1.f) + (A + 1.f) * cosw);
            b2 = A * ((A + 1.f) + (A - 1.f) * cosw - twoSqrtAAlpha);
            a0 = (A + 1.f) - (A - 1.f) * cosw + twoSqrtAAlpha;
            a1 = 2.f * ((A - 1.f) - (A + 1.f) * cosw);
            a2 = (A + 1.f) - (A - 1.f) * cosw - twoSqrtAAlpha;
            break;
        }
        case BiquadType::AllPass:
            b0 = 1.f - alpha; b1 = -2.f * cosw; b2 = 1.f + alpha;
            a0 = 1.f + alpha; a1 = -2.f * cosw; a2 = 1.f - alpha;
            break;
    }
    const float invA0 = 1.0f / a0;
    b0_ = b0 * invA0; b1_ = b1 * invA0; b2_ = b2 * invA0;
    a1_ = a1 * invA0; a2_ = a2 * invA0;
}

void ParametricEq::init(float sampleRate) {
    sr_ = sampleRate;
    for (auto& f : filtersL_) f.setSampleRate(sampleRate);
    for (auto& f : filtersR_) f.setSampleRate(sampleRate);
}

void ParametricEq::setBand(int index, const EqBand& band) {
    if (index < 0 || index >= kMaxBands) return;
    bands_[index] = band;
    filtersL_[index].setParams(band.type, band.freqHz, band.q, band.gainDb);
    filtersR_[index].setParams(band.type, band.freqHz, band.q, band.gainDb);
    bandCount_ = std::max(bandCount_, index + 1);
}

void ParametricEq::reset() {
    for (auto& f : filtersL_) f.reset();
    for (auto& f : filtersR_) f.reset();
}

} // namespace s1::audio::dsp
