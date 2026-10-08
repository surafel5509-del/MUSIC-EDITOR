#include "Fft.h"
#include "../common/FastMath.h"
#include <algorithm>
#include <cmath>

namespace s1::audio::dsp {

using namespace math;

Fft::Fft(int size) : n_(size) {
    // Precompute twiddle factors e^{-j*2*pi*k/n} for k in [0, n/2).
    twiddleRe_.resize(n_ / 2);
    twiddleIm_.resize(n_ / 2);
    for (int k = 0; k < n_ / 2; ++k) {
        const double angle = -kTwoPi * k / n_;
        twiddleRe_[k] = static_cast<float>(std::cos(angle));
        twiddleIm_[k] = static_cast<float>(std::sin(angle));
    }
    // Bit-reversal permutation.
    bitReverse_.resize(n_);
    const int logN = static_cast<int>(std::log2(n_));
    for (int i = 0; i < n_; ++i) {
        int r = 0;
        for (int b = 0; b < logN; ++b) r = (r << 1) | ((i >> b) & 1);
        bitReverse_[i] = r;
    }
    // Hann window.
    window_.resize(n_);
    for (int i = 0; i < n_; ++i) {
        window_[i] = 0.5f * (1.f - std::cos(kTwoPi * i / (n_ - 1)));
    }
    scratchRe_.resize(n_);
    scratchIm_.resize(n_);
}

Fft::~Fft() = default;

void Fft::forward(float* re, float* im) {
    // Bit-reversal permutation.
    for (int i = 0; i < n_; ++i) {
        const int j = bitReverse_[i];
        if (j > i) {
            std::swap(re[i], re[j]);
            std::swap(im[i], im[j]);
        }
    }
    // Butterfly stages.
    for (int stage = 2; stage <= n_; stage <<= 1) {
        const int half = stage >> 1;
        const int step = n_ / stage;
        for (int base = 0; base < n_; base += stage) {
            for (int k = 0; k < half; ++k) {
                const int tw = k * step;
                const float wre = twiddleRe_[tw];
                const float wim = twiddleIm_[tw];
                const int a = base + k;
                const int b = a + half;
                const float tre = re[b] * wre - im[b] * wim;
                const float tim = re[b] * wim + im[b] * wre;
                re[b] = re[a] - tre;
                im[b] = im[a] - tim;
                re[a] += tre;
                im[a] += tim;
            }
        }
    }
}

void Fft::inverse(float* re, float* im) {
    // Conjugate trick: IFFT(x) = conj(FFT(conj(x))) / n
    for (int i = 0; i < n_; ++i) im[i] = -im[i];
    forward(re, im);
    const float invN = 1.0f / n_;
    for (int i = 0; i < n_; ++i) { re[i] *= invN; im[i] = -im[i] * invN; }
}

void Fft::applyWindow(const float* in, float* out) const {
    for (int i = 0; i < n_; ++i) out[i] = in[i] * window_[i];
}

void Fft::forwardMagnitude(const float* timeData, float* magOut) {
    applyWindow(timeData, scratchRe_.data());
    for (int i = 0; i < n_; ++i) scratchIm_[i] = 0.f;
    forward(scratchRe_.data(), scratchIm_.data());
    // Window coherent gain for Hann = 0.5; scale so a full-scale sine reads ~0dBFS.
    constexpr float kScale = 2.0f / (0.5f * 2048.0f); // normalized below per n_
    const float s = 4.0f / n_;
    (void)kScale;
    for (int i = 0; i < n_ / 2; ++i) {
        magOut[i] = std::sqrt(scratchRe_[i] * scratchRe_[i] + scratchIm_[i] * scratchIm_[i]) * s;
    }
}

// ── SpectrumAnalyzer ─────────────────────────────────────────────────────────

void SpectrumAnalyzer::init(float sampleRate, int fftSize, int bands) {
    sr_ = sampleRate;
    bands_ = bands;
    fft_ = Fft(fftSize);
    inputRing_.assign(fftSize, 0.f);
    mags_.resize(fftSize / 2);
    // Log-spaced band edges: 20Hz .. 20kHz (or Nyquist).
    const float fMax = std::min(20000.f, sampleRate * 0.5f);
    bandBinStart_.resize(bands);
    bandBinEnd_.resize(bands);
    const float logMin = std::log10(20.f);
    const float logMax = std::log10(fMax);
    for (int b = 0; b < bands; ++b) {
        const float f0 = std::pow(10.f, logMin + (logMax - logMin) * b / bands);
        const float f1 = std::pow(10.f, logMin + (logMax - logMin) * (b + 1) / bands);
        int bin0 = static_cast<int>(f0 * fftSize / sampleRate);
        int bin1 = static_cast<int>(f1 * fftSize / sampleRate);
        bandBinStart_[b] = std::clamp(bin0, 0, fftSize / 2 - 1);
        bandBinEnd_[b] = std::clamp(std::max(bin1, bin0 + 1), 1, fftSize / 2);
    }
    for (auto& buf : bandBuffers_) buf.assign(bands, kSilenceDb);
}

void SpectrumAnalyzer::push(const float* mono, FrameCount n) {
    const int fftSize = fft_.size();
    for (FrameCount i = 0; i < n; ++i) {
        inputRing_[ringPos_++] = mono[i];
        if (static_cast<int>(ringPos_) >= fftSize) {
            ringPos_ = 0;
            if (++hopCount_ % 4 != 0) continue; // ~23 updates/sec: analyze every 4th window
            fft_.forwardMagnitude(inputRing_.data(), mags_.data());
            // Aggregate into log bands (max value per band — better peak visibility).
            const int w = writeIdx_.load(std::memory_order_relaxed);
            const int next = (w + 1) % kNumBuffers;
            if (next == readIdx_.load(std::memory_order_acquire)) return; // UI too slow: drop frame
            auto& buf = bandBuffers_[next];
            for (int b = 0; b < bands_; ++b) {
                float peak = 1e-9f;
                for (int k = bandBinStart_[b]; k < bandBinEnd_[b]; ++k) {
                    peak = std::max(peak, mags_[k]);
                }
                buf[b] = 20.f * std::log10(peak);
            }
            writeIdx_.store(next, std::memory_order_release);
        }
    }
}

bool SpectrumAnalyzer::pull(float* bandsDbOut) {
    const int w = writeIdx_.load(std::memory_order_acquire);
    if (w == readIdx_.load(std::memory_order_relaxed)) return false;
    const auto& buf = bandBuffers_[w];
    for (int b = 0; b < bands_; ++b) bandsDbOut[b] = buf[b];
    readIdx_.store(w, std::memory_order_release);
    return true;
}

} // namespace s1::audio::dsp
