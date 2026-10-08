#include "PitchShift.h"
#include "../common/FastMath.h"
#include <algorithm>
#include <cmath>

namespace s1::audio::fx {

using namespace math;

void PitchShift::init(float sampleRate) {
    sr_ = sampleRate;
    ringCap_ = static_cast<size_t>(0.5f * sampleRate); // 500ms ring
    ringL_.assign(ringCap_, 0.f);
    ringR_.assign(ringCap_, 0.f);
    semitones_.snap(0.f); stretch_.snap(1.f); grainMs_.snap(80.f); formant_.snap(0.f);
    for (auto* p : {&semitones_, &stretch_, &grainMs_}) p->setSmoothing(sampleRate, 40.f);
    grainFrames_ = static_cast<int>(0.08f * sampleRate);
    readPosA_ = 0.f; readPosB_ = static_cast<float>(grainFrames_) * 0.5f;
    grainPhase_ = 0.f;
}

void PitchShift::reset() {
    std::fill(ringL_.begin(), ringL_.end(), 0.f);
    std::fill(ringR_.begin(), ringR_.end(), 0.f);
    writePos_ = 0;
}

void PitchShift::process(float* left, float* right, FrameCount frames) {
    const float semis = semitones_.nextBlock();
    const float stretch = std::clamp(stretch_.nextBlock(), 0.25f, 4.f);
    const float grainMs = std::clamp(grainMs_.nextBlock(), 20.f, 200.f);
    grainFrames_ = static_cast<int>(grainMs * 0.001f * sr_);
    // Read-rate = pitch ratio * stretch rate. Write advances 1 sample/frame;
    // read heads advance [ratio] samples/frame => time scale compensates.
    const float pitchRatio = std::pow(2.f, semis / 12.f);
    const float readRate = pitchRatio * stretch;
    const float grainPhaseInc = 1.f / static_cast<float>(grainFrames_);

    for (FrameCount i = 0; i < frames; ++i) {
        ringL_[writePos_] = left[i];
        ringR_[writePos_] = right[i];

        // Hann window crossfade between the two read heads.
        const float w = 0.5f * (1.f - std::cos(kTwoPi * grainPhase_));
        const float wA = w;          // head A fading out as phase -> 1
        const float wB = 1.f - w;    // head B fading in
        const float idxA = std::fmod(readPosA_, static_cast<float>(ringCap_));
        const float idxB = std::fmod(readPosB_, static_cast<float>(ringCap_));
        const float sAL = ringL_[static_cast<size_t>(idxA)];
        const float sAR = ringR_[static_cast<size_t>(idxA)];
        const float sBL = ringL_[static_cast<size_t>(idxB)];
        const float sBR = ringR_[static_cast<size_t>(idxB)];

        left[i]  = DenormalGuard::protect(sAL * wA + sBL * wB);
        right[i] = DenormalGuard::protect(sAR * wA + sBR * wB);

        readPosA_ += readRate;
        readPosB_ += readRate;
        grainPhase_ += grainPhaseInc;
        if (grainPhase_ >= 1.f) {
            grainPhase_ -= 1.f;
            // Respawn the fading head half a grain ahead of the other.
            readPosA_ = readPosB_ + grainFrames_ * 0.5f;
            std::swap(readPosA_, readPosB_);
        }
        // Keep read heads a safe distance behind the write head (>= 1 grain).
        const float wPos = static_cast<float>(writePos_);
        float distA = wPos - readPosA_;
        if (distA < 0) distA += static_cast<float>(ringCap_);
        if (distA > static_cast<float>(ringCap_) - grainFrames_) {
            readPosA_ = wPos - grainFrames_;
            if (readPosA_ < 0) readPosA_ += static_cast<float>(ringCap_);
        }
        writePos_ = (writePos_ + 1) % ringCap_;
    }
}

void PitchShift::setParam(int32_t index, float value) {
    switch (index) {
        case kParamSemitones: semitones_.set(std::clamp(value, -24.f, 24.f)); break;
        case kParamStretch: stretch_.set(std::clamp(value, 0.25f, 4.f)); break;
        case kParamGrainMs: grainMs_.set(std::clamp(value, 20.f, 200.f)); break;
        case kParamFormantKeep: formant_.set(value); break; // future: spectral domain
        default: break;
    }
}

float PitchShift::getParam(int32_t index) const {
    switch (index) {
        case kParamSemitones: return semitones_.target();
        case kParamStretch: return stretch_.target();
        case kParamGrainMs: return grainMs_.target();
        case kParamFormantKeep: return formant_.target();
        default: return 0.f;
    }
}

} // namespace s1::audio::fx
