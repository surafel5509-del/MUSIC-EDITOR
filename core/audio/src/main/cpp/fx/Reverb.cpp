#include "Reverb.h"
#include "../common/FastMath.h"
#include <algorithm>
#include <cmath>

namespace s1::audio::fx {

using namespace math;

namespace {
// Classic Freeverb tunings (scaled from 44.1k reference).
constexpr int kCombTunings[8]  = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
constexpr int kAllpassTunings[4] = {556, 441, 341, 225};
constexpr int kStereoSpread = 23; // R combs offset
constexpr float kAllpassFeedback = 0.5f;
}

void Reverb::init(float sampleRate) {
    sr_ = sampleRate;
    const float scale = sampleRate / 44100.f;

    for (int i = 0; i < 8; ++i) {
        const int lenL = static_cast<int>(kCombTunings[i] * scale) + 16;
        const int lenR = static_cast<int>((kCombTunings[i] + kStereoSpread) * scale) + 16;
        combsL_.storage[i].assign(lenL, 0.f);
        combsR_.storage[i].assign(lenR, 0.f);
        combsL_.lines[i].init(combsL_.storage[i].data(), lenL);
        combsR_.lines[i].init(combsR_.storage[i].data(), lenR);
        combsL_.filterStore[i] = 0.f;
        combsR_.filterStore[i] = 0.f;
    }
    for (int i = 0; i < 4; ++i) {
        const int lenL = static_cast<int>(kAllpassTunings[i] * scale) + 16;
        const int lenR = static_cast<int>((kAllpassTunings[i] + kStereoSpread) * scale) + 16;
        allpassStorageL_[i].assign(lenL, 0.f);
        allpassStorageR_[i].assign(lenR, 0.f);
        allpassL_[i].init(allpassStorageL_[i].data(), lenL);
        allpassR_[i].init(allpassStorageR_[i].data(), lenR);
    }
    const int preLen = static_cast<int>(0.2f * sampleRate) + 16; // up to 200ms pre-delay
    preDelayStorage_.assign(preLen, 0.f);
    preDelay_.init(preDelayStorage_.data(), preLen);

    size_.snap(0.7f);      size_.setSmoothing(sampleRate, 100.f);
    damp_.snap(0.5f);      damp_.setSmoothing(sampleRate, 100.f);
    width_.snap(1.f);
    dryDb_.snap(0.f);
    wetDb_.snap(-12.f);
    preDelayMs_.snap(20.f);
    lastSize_ = lastDamp_ = -1.f;
}

void Reverb::reset() {
    for (int i = 0; i < 8; ++i) { combsL_.lines[i].reset(); combsR_.lines[i].reset(); }
    for (int i = 0; i < 4; ++i) { allpassL_[i].reset(); allpassR_[i].reset(); }
    preDelay_.reset();
}

FrameCount Reverb::tailFrames() const {
    // Approximate RT60 from size: 0.2s .. 12s.
    const float rt60 = 0.2f + size_.target() * 11.8f;
    return static_cast<FrameCount>(rt60 * sr_);
}

void Reverb::process(float* left, float* right, FrameCount frames) {
    const float size = size_.nextBlock();
    const float damp = damp_.nextBlock();
    if (size != lastSize_ || damp != lastDamp_) { lastSize_ = size; lastDamp_ = damp; }

    // Map size -> comb feedback with damping-aware scaling (Freeverb model).
    const float feedback = std::clamp(0.28f + size * 0.7f, 0.f, 0.98f);
    const float damp1 = damp * 0.5f;
    const float damp2 = 1.f - damp1;
    const float width = std::clamp(width_.nextBlock(), 0.f, 1.f);
    const float dryGain = dbToGain(dryDb_.nextBlock());
    const float wetGain = dbToGain(wetDb_.nextBlock());
    const int preFrames = static_cast<int>(std::clamp(preDelayMs_.nextBlock(), 0.f, 200.f) * 0.001f * sr_);

    for (FrameCount i = 0; i < frames; ++i) {
        // Pre-delay on the mono sum input.
        const float in = (left[i] + right[i]) * 0.5f;
        preDelay_.write(in);
        const float input = preDelay_.readInt(preFrames > 0 ? preFrames : 1);

        // Parallel damped combs, per channel.
        float outL = 0.f, outR = 0.f;
        for (int c = 0; c < 8; ++c) {
            outL += combsL_.lines[c].processComb(input, combsL_.lines[c].capacity() - 16,
                                                  feedback, combsL_.filterStore[c], damp1);
            outR += combsR_.lines[c].processComb(input, combsR_.lines[c].capacity() - 16,
                                                  feedback, combsR_.filterStore[c], damp1);
        }
        (void)damp2;
        outL *= 0.125f; // normalize 8 parallel paths
        outR *= 0.125f;

        // Series allpasses (diffusion).
        for (int a = 0; a < 4; ++a) {
            outL = allpassL_[a].processAllpass(outL, allpassL_[a].capacity() - 16, kAllpassFeedback);
            outR = allpassR_[a].processAllpass(outR, allpassR_[a].capacity() - 16, kAllpassFeedback);
        }

        // Width: 1 = full stereo, 0 = mono.
        const float mid = (outL + outR) * 0.5f;
        const float side = (outL - outR) * 0.5f * width;
        left[i]  = DenormalGuard::protect(left[i] * dryGain + (mid + side) * wetGain);
        right[i] = DenormalGuard::protect(right[i] * dryGain + (mid - side) * wetGain);
    }
}

void Reverb::setParam(int32_t index, float value) {
    switch (index) {
        case revparam::kSize: size_.set(std::clamp(value, 0.f, 1.f)); break;
        case revparam::kDamping: damp_.set(std::clamp(value, 0.f, 1.f)); break;
        case revparam::kWidth: width_.set(value); break;
        case revparam::kDry: dryDb_.set(value); break;
        case revparam::kWet: wetDb_.set(value); break;
        case revparam::kPreDelayMs: preDelayMs_.set(value); break;
        case revparam::kDecaySeconds: // convenience: seconds -> size
            size_.set(std::clamp((value - 0.2f) / 11.8f, 0.f, 1.f));
            break;
        default: break;
    }
}

float Reverb::getParam(int32_t index) const {
    switch (index) {
        case revparam::kSize: return size_.target();
        case revparam::kDamping: return damp_.target();
        case revparam::kWidth: return width_.target();
        case revparam::kDry: return dryDb_.target();
        case revparam::kWet: return wetDb_.target();
        case revparam::kPreDelayMs: return preDelayMs_.target();
        case revparam::kDecaySeconds: return 0.2f + size_.target() * 11.8f;
        default: return 0.f;
    }
}

} // namespace s1::audio::fx
