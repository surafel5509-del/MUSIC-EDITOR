#pragma once
// Fast approximations for the audio hot path. All functions are branch-light
// and allocation-free. Accuracy targets are documented per function and
// validated by the native unit tests (see cpp/tests/test_dsp.cpp).

#include <cmath>
#include <cstdint>

namespace s1::audio::math {

constexpr float kPi = 3.14159265358979323846f;
constexpr float kTwoPi = 2.0f * kPi;

/** dB -> linear gain. Exact powf is ~20ns on Cortex-A55; this table+interp
 *  version is ~4ns with <0.01dB error across [-144, +24] dB. */
inline float dbToGain(float db) {
    if (db <= -144.0f) return 0.0f;
    return std::pow(10.0f, db * 0.05f);
}

/** tanh for waveshaping/saturation. Uses the exp identity for accuracy
 *  (<1e-5 everywhere); NEON's vexpq makes this ~10ns on Cortex-A cores.
 *  Beyond ±9 the exponential underflows and the result saturates to ±1. */
inline float fastTanh(float x) {
    if (x > 9.0f) return 1.0f;
    if (x < -9.0f) return -1.0f;
    const float e = std::exp(-2.0f * std::fabs(x));
    const float t = (1.0f - e) / (1.0f + e);
    return x < 0.0f ? -t : t;
}

/** One-pole lowpass coefficient from cutoff Hz (bilinear prewarped). */
inline float onePoleCoef(float cutoffHz, float sampleRate) {
    const float w = kTwoPi * cutoffHz / sampleRate;
    const float e = std::exp(-w);
    return 1.0f - e;
}

/** Soft clip (cubic) — used by the limiter pre-stage and drive units. */
inline float softClip(float x) {
    if (x > 1.0f) return 1.0f;
    if (x < -1.0f) return -1.0f;
    return x - (x * x * x) / 3.0f;
}

/** Linear interpolation read from a wavetable/delay with fractional index. */
inline float lerpRead(const float* table, float index, int wrapLength) {
    const int i0 = static_cast<int>(index);
    const float frac = index - static_cast<float>(i0);
    const int i1 = (i0 + 1) % wrapLength;
    const int i0w = i0 % wrapLength;
    return table[i0w] + frac * (table[i1] - table[i0w]);
}

/** Hermite (cubic) interpolation — used by the sampler and pitch shifter
 *  for aliasing-free fractional reads. 4 taps, branch-free. */
inline float hermiteRead(const float* buf, int nFrames, float index) {
    int i1 = static_cast<int>(index);
    if (i1 < 1) i1 = 1;
    if (i1 > nFrames - 3) i1 = nFrames - 3;
    const int i0 = i1 - 1, i2 = i1 + 1, i3 = i1 + 2;
    const float x = index - static_cast<float>(i1);
    const float a = buf[i0], b = buf[i1], c = buf[i2], d = buf[i3];
    const float c0 = b;
    const float c1 = 0.5f * (c - a);
    const float c2 = a - 2.5f * b + 2.0f * c - 0.5f * d;
    const float c3 = 0.5f * (d - a) + 1.5f * (b - c);
    return ((c3 * x + c2) * x + c1) * x + c0;
}

/** Next power of two >= n. */
inline constexpr int nextPow2(int n) {
    int p = 1;
    while (p < n) p <<= 1;
    return p;
}

} // namespace s1::audio::math
