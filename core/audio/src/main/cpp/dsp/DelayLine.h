#pragma once
// Fixed-capacity delay line with fractional read (Hermite interpolation).
// Storage is supplied by the owner (graph pool) — no allocation on init path.
// Used by reverb combs/allpasses, modulation FX, latency compensation, and
// the granular pitch shifter.

#include "../common/Types.h"
#include "../common/FastMath.h"

namespace s1::audio::dsp {

class DelayLine {
public:
    void init(float* storage, int32_t capacityFrames) {
        buffer_ = storage;
        capacity_ = capacityFrames;
        writePos_ = 0;
        for (int32_t i = 0; i < capacityFrames; ++i) storage[i] = 0.f;
    }

    void reset() {
        writePos_ = 0;
        for (int32_t i = 0; i < capacity_; ++i) buffer_[i] = 0.f;
    }

    inline void write(float x) {
        buffer_[writePos_] = x;
        writePos_ = (writePos_ + 1) % capacity_;
    }

    /** Integer read: delay of [frames] samples. */
    inline float readInt(int32_t frames) const {
        int32_t idx = writePos_ - frames;
        if (idx < 0) idx += capacity_;
        return buffer_[idx];
    }

    /** Fractional read with Hermite interpolation (modulated delays). */
    inline float readFrac(float frames) const {
        float idx = static_cast<float>(writePos_) - frames;
        while (idx < 1.0f) idx += static_cast<float>(capacity_);
        return math::hermiteRead(buffer_, capacity_, idx);
    }

    /** Comb filter with feedback: y = x + fb * y[-d] (Freeverb style). */
    inline float processComb(float x, int32_t delayFrames, float feedback, float& filterStore, float damp) {
        const float out = readInt(delayFrames);
        // Freeverb's one-pole inside the feedback loop tames HF buildup.
        filterStore = out * (1.0f - damp) + filterStore * damp;
        write(x + filterStore * feedback);
        return out;
    }

    /** Allpass: y = -g*x + x[-d] + g*y[-d] */
    inline float processAllpass(float x, int32_t delayFrames, float g) {
        const float delayed = readInt(delayFrames);
        const float out = -g * x + delayed;
        write(x + g * delayed);
        return out;
    }

    int32_t capacity() const { return capacity_; }

private:
    float* buffer_ = nullptr;
    int32_t capacity_ = 0;
    int32_t writePos_ = 0;
};

} // namespace s1::audio::dsp
