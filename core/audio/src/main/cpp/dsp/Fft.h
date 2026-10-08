#pragma once
// In-place iterative radix-2 FFT for the spectrum analyzer and the phase
// vocoder (pitch shift / time stretch). Sizes 256..8192. Twiddle tables are
// precomputed at init — transform itself is allocation-free and re-entrant
// per instance (one per analyzer bus).

#include "../common/Types.h"
#include <vector>

namespace s1::audio::dsp {

class Fft {
public:
    explicit Fft(int size = 2048);
    ~Fft();

    int size() const { return n_; }

    /** Forward real FFT. [timeData] length n, [magOut] length n/2 (linear magnitude). */
    void forwardMagnitude(const float* timeData, float* magOut);

    /** Forward complex FFT in-place on interleaved (re,im) arrays of length n. */
    void forward(float* re, float* im);

    /** Inverse complex FFT in-place (1/n normalized). */
    void inverse(float* re, float* im);

    /** Hann window, length n. */
    const float* window() const { return window_.data(); }

    /** Apply Hann window: out[i] = in[i] * w[i]. */
    void applyWindow(const float* in, float* out) const;

private:
    int n_;
    std::vector<float> twiddleRe_;
    std::vector<float> twiddleIm_;
    std::vector<int> bitReverse_;
    std::vector<float> window_;
    std::vector<float> scratchRe_;
    std::vector<float> scratchIm_;
};

/**
 * Spectrum analyzer front-end: accumulates blocks into an FFT hop, computes
 * log-spaced magnitudes for UI. Runs on the audio thread (fixed 2048 FFT with
 * 4x hop = ~23 analysis/sec at 48k) and publishes to a triple-buffer the UI
 * polls — no locking.
 */
class SpectrumAnalyzer {
public:
    void init(float sampleRate, int fftSize = 2048, int bands = 64);
    /** Feed one block of interleaved mono-summed samples. */
    void push(const float* mono, FrameCount n);
    /** Latest analysis copied out (called from UI thread; triple-buffered swap). */
    bool pull(float* bandsDbOut);

private:
    static constexpr int kNumBuffers = 3;
    Fft fft_{2048};
    std::vector<float> inputRing_;
    std::vector<float> mags_;
    std::vector<float> bandBuffers_[kNumBuffers];
    std::vector<int> bandBinStart_;
    std::vector<int> bandBinEnd_;
    std::atomic<int> writeIdx_{0};
    std::atomic<int> readIdx_{0};
    float sr_ = 48000.f;
    int bands_ = 64;
    size_t ringPos_ = 0;
    size_t hopCount_ = 0;
};

} // namespace s1::audio::dsp
