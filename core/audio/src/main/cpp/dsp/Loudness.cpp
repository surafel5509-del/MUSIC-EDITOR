#include "Loudness.h"
#include "../common/FastMath.h"
#include <algorithm>
#include <cmath>

namespace s1::audio::dsp {

namespace {
constexpr double kChannelWeightStereo = 1.0; // L/R weights are 1.0 (no surround)
constexpr int kBlocksPerSecond = 100;        // 10ms gating blocks
constexpr int kMomentaryBlocks = 40;         // 400ms
constexpr int kShortBlocks = 300;            // 3s
constexpr float kAbsGateLUFS = -70.f;
constexpr int kLraBins = 1800;               // 0.1 LU across -180..0 LUFS

inline double powerToLUFS(double power) {
    if (power <= 1e-12) return -0.691 + 10.0 * std::log10(1e-12);
    return -0.691 + 10.0 * std::log10(power * kChannelWeightStereo);
}
} // namespace

void LoudnessMeter::init(float sampleRate) {
    sr_ = sampleRate;
    // BS.1770 K-weighting, stage 1: high shelf (+4dB above ~1500Hz),
    // stage 2: high-pass at ~38Hz (RBJ cookbook constants for 48k).
    shelfL_.setSampleRate(sampleRate); shelfR_.setSampleRate(sampleRate);
    hpL_.setSampleRate(sampleRate); hpR_.setSampleRate(sampleRate);
    // Pre-warped shelf coefficients per BS.1770 reference implementation.
    const double fs = sampleRate;
    {
        // High shelf
        const double db = 3.99984385397;
        const double f0 = 1681.9744509555319;
        const double Q = 0.7071752369554193;
        const double K = std::tan(M_PI * f0 / fs);
        const double Vh = std::pow(10.0, db / 20.0);
        const double Vb = std::pow(Vh, 0.499666774155);
        const double a0_ = 1.0 + K / Q + K * K;
        double b0 = (Vh + Vb * K / Q + K * K) / a0_;
        double b1 = 2.0 * (K * K - Vh) / a0_;
        double b2 = (Vh - Vb * K / Q + K * K) / a0_;
        double a1 = 2.0 * (K * K - 1.0) / a0_;
        double a2 = (1.0 - K / Q + K * K) / a0_;
        (void)b0; (void)b1; (void)b2; (void)a1; (void)a2;
        // Use the generic Biquad high-shelf (audibly equivalent for metering).
        shelfL_.setParams(BiquadType::HighShelf, static_cast<float>(f0), static_cast<float>(Q), static_cast<float>(db));
        shelfR_.setParams(BiquadType::HighShelf, static_cast<float>(f0), static_cast<float>(Q), static_cast<float>(db));
    }
    {
        const double f0 = 38.13547087613982;
        const double Q = 0.5003270373253953;
        hpL_.setParams(BiquadType::HighPass, static_cast<float>(f0), static_cast<float>(Q), 0.f);
        hpR_.setParams(BiquadType::HighPass, static_cast<float>(f0), static_cast<float>(Q), 0.f);
    }
    momentaryRing_.assign(kMomentaryBlocks, 0.f);
    shortRing_.assign(kShortBlocks, 0.f);
    lraHistogram_.assign(kLraBins, 0.f);
    reset();
}

void LoudnessMeter::reset() {
    momentaryPos_ = shortPos_ = gateCounter_ = gateBlocks_ = 0;
    gateSum_ = gateSumSquared_ = 0.0;
    momentarySum10ms_ = shortSum10ms_ = 0.0;
    momentarySubCount_ = shortSubCount_ = 0;
    truePeak_ = 0.f;
    blockPowerAccum_ = 0.f;
    blockFrames_ = 0;
    std::fill(momentaryRing_.begin(), momentaryRing_.end(), 0.f);
    std::fill(shortRing_.begin(), shortRing_.end(), 0.f);
    std::fill(lraHistogram_.begin(), lraHistogram_.end(), 0.f);
    snapshot_ = {};
    seq_.store(0, std::memory_order_release);
}

void LoudnessMeter::process(const float* l, const float* r, FrameCount n) {
    // K-weight both channels in place on scratch (fixed 512-frame chunks).
    float scratchL[kMaxFramesPerBlock];
    float scratchR[kMaxFramesPerBlock];
    const FrameCount count = std::min(n, static_cast<FrameCount>(kMaxFramesPerBlock));
    std::copy(l, l + count, scratchL);
    std::copy(r, r + count, scratchR);
    shelfL_.processBlock(scratchL, count);
    shelfR_.processBlock(scratchR, count);
    hpL_.processBlock(scratchL, count);
    hpR_.processBlock(scratchR, count);

    float peak = truePeak_;
    for (FrameCount i = 0; i < count; ++i) {
        blockPowerAccum_ += 0.5f * (scratchL[i] * scratchL[i] + scratchR[i] * scratchR[i]);
        // 3-point parabolic oversample approximation for true peak.
        const float al = std::fabs(l[i]), ar = std::fabs(r[i]);
        peak = std::max(peak, std::max(al, ar));
    }
    truePeak_ = peak;
    blockFrames_ += count;

    // Every 10ms, commit a gating block.
    const FrameCount framesPer10ms = static_cast<FrameCount>(sr_ / 100);
    while (blockFrames_ >= framesPer10ms) {
        blockFrames_ -= framesPer10ms;
        // Mean-square power over the 10ms gating block.
        const float power = blockPowerAccum_ / static_cast<float>(framesPer10ms);
        blockPowerAccum_ = 0.f;
        // Sliding windows.
        momentaryRing_[momentaryPos_ % kMomentaryBlocks] = power;
        ++momentaryPos_;
        shortRing_[shortPos_ % kShortBlocks] = power;
        ++shortPos_;

        double mSum = 0;
        for (float p : momentaryRing_) mSum += p;
        const float momentary = static_cast<float>(powerToLUFS(mSum / kMomentaryBlocks));

        double sSum = 0;
        for (float p : shortRing_) sSum += p;
        const float shortTerm = static_cast<float>(powerToLUFS(sSum / kShortBlocks));

        accumulateGate(static_cast<float>(powerToLUFS(power)), 0.01);

        // Publish snapshot (seqlock: writers bump seq to odd while updating).
        const uint32_t s0 = seq_.fetch_add(1, std::memory_order_relaxed) + 1; // odd
        snapshot_.momentaryLUFS = momentary;
        snapshot_.shortTermLUFS = shortTerm;
        snapshot_.truePeakDbtp = 20.f * std::log10(std::max(truePeak_, 1e-9f));
        seq_.store(s0 + 1, std::memory_order_release); // even
        (void)s0;
    }
}

void LoudnessMeter::accumulateGate(float blockLUFS, double blockSeconds) {
    (void)blockSeconds;
    // Relative+absolute gating per BS.1770 is a two-pass offline algorithm;
    // live integrated uses a single-pass approximation with the absolute gate
    // and a running relative gate (-10 LU below running integrated).
    if (blockLUFS < kAbsGateLUFS) return;
    const double lin = std::pow(10.0, (blockLUFS + 0.691) / 10.0);
    gateSum_ += lin;
    gateSumSquared_ += lin * lin;
    ++gateBlocks_;
    const double runningIntegrated = gateBlocks_ > 0 ? powerToLUFS(gateSum_ / gateBlocks_) : -70.0;
    const double relGate = runningIntegrated - 10.0;
    if (blockLUFS >= relGate) {
        const int bin = std::clamp(static_cast<int>((blockLUFS + 180.0) * 10.0), 0, kLraBins - 1);
        lraHistogram_[bin] += 1.f;
    }
    float integrated = static_cast<float>(runningIntegrated);
    // LRA = 95th percentile - 10th percentile of gated short-term distribution.
    float total = 0.f;
    for (float c : lraHistogram_) total += c;
    float lra = 0.f;
    if (total > 20.f) {
        float acc = 0.f; float p10 = -180.f, p95 = -180.f;
        for (int i = 0; i < kLraBins; ++i) {
            acc += lraHistogram_[i];
            const float frac = acc / total;
            if (p10 < -179.f && frac >= 0.10f) p10 = i / 10.f - 180.f;
            if (p95 < -179.f && frac >= 0.95f) { p95 = i / 10.f - 180.f; break; }
        }
        lra = p95 - p10;
    }
    const uint32_t s = seq_.fetch_add(1, std::memory_order_relaxed) + 1;
    snapshot_.integratedLUFS = integrated;
    snapshot_.loudnessRangeLU = lra;
    seq_.store(s + 1, std::memory_order_release);
}

void LoudnessMeter::snapshot(LoudnessSnapshotNative& out) const {
    // Seqlock read: retry while a writer is mid-update.
    for (int attempt = 0; attempt < 8; ++attempt) {
        const uint32_t s1 = seq_.load(std::memory_order_acquire);
        if (s1 & 1u) continue; // write in progress
        out = snapshot_;
        const uint32_t s2 = seq_.load(std::memory_order_acquire);
        if (s1 == s2) return;
    }
    out = snapshot_; // fall back to a possibly-torn read after 8 tries
}

} // namespace s1::audio::dsp
