// SPDX-License-Identifier: MIT
// Metering taps: peak/RMS per channel + simplified integrated LUFS.
//
// Values are written by the audio callback into plain atomics; the UI polls
// them at ~30fps from the JVM. No locks, no allocation.
#pragma once

#include <atomic>
#include <cmath>
#include <array>
#include "biquad.h"

namespace studioone::dsp {

struct MeterSnapshot {
    float peak[2]{0.f, 0.f};
    float rms[2]{0.f, 0.f};
    float lufsMomentary{-70.f};
    float lufsIntegrated{-70.f};
    bool clip[2]{false, false};
};

class Meter {
public:
    void prepare(double sampleRate) {
        sampleRate_ = sampleRate;
        // K-weighting pre-filter (simplified two-stage shelving).
        shelf_.prepare(sampleRate);
        shelf_.configure(BiquadType::HighShelf, 1500.0, 4.0, 0.707);
        hp_.prepare(sampleRate);
        hp_.configure(BiquadType::HighPass, 38.0, 0.0, 0.5);
    }

    /** Called on the audio thread with the stereo bus content. */
    void process(const float* const* channels, int numChannels, int numFrames) noexcept {
        float peak[2]{0.f, 0.f};
        double sumSq[2]{0.0, 0.0};
        double kSumSq = 0.0;

        for (int i = 0; i < numFrames; ++i) {
            for (int c = 0; c < numChannels && c < 2; ++c) {
                const float s = channels[c][i];
                const float a = std::abs(s);
                if (a > peak[c]) peak[c] = a;
                sumSq[c] += static_cast<double>(s) * s;
                // K-weighting path runs on channel 0+1 average.
            }
            const float mono = numChannels > 1
                ? (channels[0][i] + channels[1][i]) * 0.5f
                : channels[0][i];
            const float weighted = static_cast<float>(hp_.process(shelf_.process(mono)));
            kSumSq += static_cast<double>(weighted) * weighted;
        }

        // Ballistics: fast attack peak hold, exponential RMS decay.
        for (int c = 0; c < 2; ++c) {
            const float held = peakHold_[c].load(std::memory_order_relaxed);
            peakHold_[c].store(peak[c] > held ? peak[c] : held * 0.995f, std::memory_order_relaxed);
            const double rms = std::sqrt(sumSq[c] / std::max(numFrames, 1));
            const float prevRms = rms_[c].load(std::memory_order_relaxed);
            rms_[c].store(static_cast<float>(rms > prevRms ? rms : rms * 0.92 + prevRms * 0.08),
                          std::memory_order_relaxed);
            clip_[c].store(peak[c] >= 0.999f, std::memory_order_relaxed);
        }

        const double meanSq = kSumSq / std::max(numFrames, 1);
        const double momentary = meanSq > 1e-12 ? -0.691 + 10.0 * std::log10(meanSq) : -70.0;
        // Gate at -70 LUFS absolute floor (first gating pass of BS.1770).
        if (momentary > -70.0) {
            const double prev = integratedSum_.load(std::memory_order_relaxed);
            const double count = integratedCount_.load(std::memory_order_relaxed) + 1.0;
            integratedSum_.store(prev + std::pow(10.0, (momentary + 0.691) / 10.0), std::memory_order_relaxed);
            integratedCount_.store(count, std::memory_order_relaxed);
        }
        momentary_.store(static_cast<float>(momentary), std::memory_order_relaxed);
    }

    /** UI thread: cheap lock-free snapshot read. */
    MeterSnapshot snapshot() const noexcept {
        MeterSnapshot s;
        s.peak[0] = peakHold_[0].load(std::memory_order_relaxed);
        s.peak[1] = peakHold_[1].load(std::memory_order_relaxed);
        s.rms[0] = rms_[0].load(std::memory_order_relaxed);
        s.rms[1] = rms_[1].load(std::memory_order_relaxed);
        s.clip[0] = clip_[0].load(std::memory_order_relaxed);
        s.clip[1] = clip_[1].load(std::memory_order_relaxed);
        s.lufsMomentary = momentary_.load(std::memory_order_relaxed);
        const double sum = integratedSum_.load(std::memory_order_relaxed);
        const double count = integratedCount_.load(std::memory_order_relaxed);
        s.lufsIntegrated = count > 0
            ? static_cast<float>(-0.691 + 10.0 * std::log10(sum / count))
            : -70.f;
        return s;
    }

    void reset() noexcept {
        peakHold_[0].store(0.f); peakHold_[1].store(0.f);
        rms_[0].store(0.f); rms_[1].store(0.f);
        integratedSum_.store(0.0); integratedCount_.store(0.0);
    }

private:
    double sampleRate_{44100.0};
    Biquad shelf_, hp_;
    std::atomic<float> peakHold_[2];
    std::atomic<float> rms_[2];
    std::atomic<bool> clip_[2];
    std::atomic<float> momentary_{-70.f};
    std::atomic<double> integratedSum_{0.0};
    std::atomic<double> integratedCount_{0.0};
};

}  // namespace studioone::dsp
