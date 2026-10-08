#pragma once
// ITU-R BS.1770-4 loudness metering: K-weighted momentary (400ms), short-term
// (3s), integrated (gated), loudness range, and true-peak (4x oversampled).
// Runs on the master bus inside the audio thread; snapshot published to a
// triple buffer polled by the mixer UI at ~30Hz.

#include "../common/Types.h"
#include "Biquad.h"
#include <atomic>
#include <vector>

namespace s1::audio::dsp {

struct LoudnessSnapshotNative {
    float momentaryLUFS = kSilenceDb;
    float shortTermLUFS = kSilenceDb;
    float integratedLUFS = kSilenceDb;
    float loudnessRangeLU = 0.f;
    float truePeakDbtp = kSilenceDb;
};

class LoudnessMeter {
public:
    void init(float sampleRate);
    void reset();

    /** Process one stereo block (called from the master bus, audio thread). */
    void process(const float* l, const float* r, FrameCount n);

    /** UI poll: copy latest snapshot. Lock-free (seqlock on the struct). */
    void snapshot(LoudnessSnapshotNative& out) const;

private:
    void accumulateGate(float blockPower, double blockSeconds);

    float sr_ = 48000.f;
    // K-weighting filter stages (per BS.1770: high-shelf + high-pass).
    Biquad shelfL_, shelfR_, hpL_, hpR_;
    // 400ms / 3s sliding block sums via ring accumulators.
    std::vector<float> momentaryRing_;   // per-10ms block powers, 40 entries
    std::vector<float> shortRing_;       // 300 entries
    size_t momentaryPos_ = 0, shortPos_ = 0;
    size_t gateCounter_ = 0;
    double gateSum_ = 0.0;
    double gateSumSquared_ = 0.0;
    size_t gateBlocks_ = 0;
    double momentarySum10ms_ = 0.0;
    int momentarySubCount_ = 0;
    double shortSum10ms_ = 0.0;
    int shortSubCount_ = 0;
    // Loudness range: histogram of short-term values (0.1 LU bins, gated).
    std::vector<float> lraHistogram_;
    // True peak: naive 4x oversample peak via 3-tap interpolation is enough
    // for metering (full polyphase FIR is used only in the offline limiter).
    float truePeak_ = 0.f;
    float blockPowerAccum_ = 0.f;
    FrameCount blockFrames_ = 0;

    mutable std::atomic<uint32_t> seq_{0};
    LoudnessSnapshotNative snapshot_{};
};

} // namespace s1::audio::dsp
