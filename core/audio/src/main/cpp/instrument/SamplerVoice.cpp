#include "SamplerVoice.h"
#include <algorithm>

namespace s1::audio::instrument {

using namespace math;

void SamplerVoice::setEnvelope(float attackMs, float decayMs, float sustain, float releaseMs) {
    env_.setParams(attackMs, decayMs, sustain, releaseMs, sr_);
}

void SamplerVoice::noteOn(uint8_t key, uint8_t velocity) {
    key_ = key;
    const PoolSample* sample = pool_ ? pool_->get(zone_.poolSlot) : nullptr;
    if (!sample || sample->frames <= 0) { active_ = false; return; }

    // Pitch: semitones from root, plus global bend (±2 st default).
    const float semis = static_cast<float>(key) - static_cast<float>(zone_.rootKey) + bendNorm_ * 2.f;
    const float pitchRatio = std::pow(2.f, semis / 12.f);
    rate_ = pitchRatio * static_cast<float>(sample->sampleRate) / sr_;
    // Guard: resampling beyond 4x is inaudible garbage and can overrun reads.
    rate_ = std::clamp(rate_, 0.0625f, 4.f);

    const float vel = std::max(1, static_cast<int>(velocity)) / 127.f;
    // Soft velocity curve (musical default: v^1.4).
    gain_ = dbToGain(zone_.gainDb) * std::pow(vel, 1.4f);

    readPos_ = 0.f;
    active_ = true;
    chokeFade_ = 1.f;
    if (zone_.oneShot) {
        oneShotActive_ = true;
        released_ = false;
        env_.setParams(0.5f, 100000.f, 1.f, 5.f, sr_); // open envelope for one-shots
        env_.noteOn();
    } else {
        released_ = false;
        oneShotActive_ = false;
        env_.noteOn();
    }
}

void SamplerVoice::noteOff(uint8_t key) {
    if (key != key_ || !active_) return;
    if (oneShotActive_) return; // one-shots ignore note-off (drum behavior)
    released_ = true;
    env_.noteOff();
}

void SamplerVoice::allSoundOff() {
    if (!active_) return;
    released_ = true;
    oneShotActive_ = false;
    env_.noteOff();
}

void SamplerVoice::render(float* left, float* right, FrameCount frames) {
    if (!active_ || !pool_) return;
    const PoolSample* sample = pool_->get(zone_.poolSlot);
    if (!sample || !sample->data) { active_ = false; return; }

    const int channels = sample->channels;
    const int64_t totalFrames = sample->frames;
    const bool hasLoop = zone_.loopStartFrame >= 0 && zone_.loopEndFrame > zone_.loopStartFrame;

    for (FrameCount i = 0; i < frames; ++i) {
        // Position handling (loop / end).
        if (readPos_ >= static_cast<float>(totalFrames)) {
            if (hasLoop && !released_) {
                const float loopLen = static_cast<float>(zone_.loopEndFrame - zone_.loopStartFrame);
                readPos_ = static_cast<float>(zone_.loopStartFrame) + std::fmod(readPos_ - zone_.loopEndFrame, loopLen);
            } else {
                active_ = false;
                break;
            }
        } else if (hasLoop && !released_ && readPos_ >= static_cast<float>(zone_.loopEndFrame)) {
            readPos_ -= static_cast<float>(zone_.loopEndFrame - zone_.loopStartFrame);
        }

        const int64_t idx = static_cast<int64_t>(readPos_);
        const float frac = readPos_ - static_cast<float>(idx);
        float sL, sR;
        // Linear interpolation (Hermite at these ratios costs more than it
        // gains for 16/24-bit content; keep linear on the hot path).
        const int64_t idx1 = std::min(idx + 1, totalFrames - 1);
        if (channels >= 2) {
            const float a0 = sample->data[idx * 2], a1 = sample->data[idx1 * 2];
            const float b0 = sample->data[idx * 2 + 1], b1 = sample->data[idx1 * 2 + 1];
            sL = a0 + frac * (a1 - a0);
            sR = b0 + frac * (b1 - b0);
        } else {
            const float a0 = sample->data[idx], a1 = sample->data[idx1];
            sL = sR = a0 + frac * (a1 - a0);
        }

        const float amp = env_.process() * gain_ * chokeFade_;
        left[i] += sL * amp;
        right[i] += sR * amp;

        readPos_ += rate_;

        if (env_.isIdle() && released_) { active_ = false; break; }
    }
}

} // namespace s1::audio::instrument
