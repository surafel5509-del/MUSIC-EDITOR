#pragma once
// FxUnit: the common interface every native effect implements.
//
// Contract:
//  * process() is called from the audio thread — no allocation, no locks.
//  * setParam() may be called from ANY thread. Parameters are stored as
//    atomics and smoothed inside process() (zipper-noise free).
//  * latencyFrames() feeds the graph's latency compensation (delay-aligned
//    summing). Look-ahead limiters and linear-phase units report > 0.
//  * tailFrames() tells freeze/bounce how long to render after the last input.

#include "../common/Types.h"
#include <algorithm>
#include <atomic>
#include <cmath>

namespace s1::audio::fx {

class FxUnit {
public:
    virtual ~FxUnit() = default;
    virtual void init(float sampleRate) = 0;
    virtual void reset() = 0;

    /** Stereo process in place. Pointers are 16-byte aligned pool buffers. */
    virtual void process(float* left, float* right, FrameCount frames) = 0;

    /** Parameter update from control thread (atomic-safe). */
    virtual void setParam(int32_t index, float value) = 0;
    virtual float getParam(int32_t index) const = 0;

    virtual FrameCount latencyFrames() const { return 0; }
    virtual FrameCount tailFrames() const { return 0; }
    virtual const char* name() const = 0;

protected:
    float sr_ = 48000.f;
};

/** Smoothed parameter: target set from any thread, value ramps on audio thread. */
class SmoothedParam {
public:
    void set(float v) { target_.store(v, std::memory_order_relaxed); }
    float target() const { return target_.load(std::memory_order_relaxed); }
    void setSmoothing(float sampleRate, float timeMs) {
        coef_ = std::exp(-1.0f / std::max(1.0f, timeMs * 0.001f * sampleRate));
    }
    /** Advance one sample toward target. */
    inline float next() {
        value_ = coef_ * value_ + (1.0f - coef_) * target_.load(std::memory_order_relaxed);
        return value_;
    }
    /** Advance per-block (cheap smoothing for slow params). */
    inline float nextBlock() {
        const float t = target_.load(std::memory_order_relaxed);
        value_ += (t - value_) * 0.2f;
        return value_;
    }
    void snap(float v) { value_ = v; target_.store(v, std::memory_order_relaxed); }
    float value() const { return value_; }

private:
    std::atomic<float> target_{0.f};
    float value_ = 0.f;
    float coef_ = 0.99f;
};

} // namespace s1::audio::fx
