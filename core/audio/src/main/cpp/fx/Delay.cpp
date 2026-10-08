#include "Delay.h"
#include "../common/FastMath.h"
#include <algorithm>
#include <cmath>

namespace s1::audio::fx {

using namespace math;

void DelayUnit::init(float sampleRate) {
    sr_ = sampleRate;
    const int cap = static_cast<int>(2.0f * sampleRate); // up to 2s delay
    storageL_.assign(cap, 0.f); storageR_.assign(cap, 0.f);
    delayL_.init(storageL_.data(), cap);
    delayR_.init(storageR_.data(), cap);
    lcL_.setSampleRate(sampleRate); lcR_.setSampleRate(sampleRate);
    hcL_.setSampleRate(sampleRate); hcR_.setSampleRate(sampleRate);
    timeMs_.snap(375.f); feedback_.snap(0.35f); wetDb_.snap(-9.f); dryDb_.snap(0.f);
    lowCut_.snap(120.f); highCut_.snap(8000.f); width_.snap(1.f);
    for (auto* p : {&timeMs_, &feedback_, &wetDb_, &dryDb_}) p->setSmoothing(sampleRate, 30.f);
}

void DelayUnit::reset() { delayL_.reset(); delayR_.reset(); }

FrameCount DelayUnit::tailFrames() const {
    const float fb = std::clamp(feedback_.target(), 0.f, 0.95f);
    if (fb <= 0.001f) return static_cast<FrameCount>(timeMs_.target() * 0.001f * sr_);
    // Time for feedback to decay 60dB: n = -60 / (20*log10(fb)) delay cycles.
    const float cycles = -60.f / (20.f * std::log10(fb));
    return static_cast<FrameCount>(cycles * timeMs_.target() * 0.001f * sr_);
}

void DelayUnit::process(float* left, float* right, FrameCount frames) {
    const float timeMs = std::clamp(timeMs_.nextBlock(), 5.f, 2000.f);
    const float fb = std::clamp(feedback_.nextBlock(), 0.f, 0.95f);
    const float wet = dbToGain(wetDb_.nextBlock());
    const float dry = dbToGain(dryDb_.nextBlock());
    const float width = width_.nextBlock();

    const int delayFrames = std::max(1, static_cast<int>(timeMs * 0.001f * sr_));
    if (timeMs != lastTimeMs_) lastTimeMs_ = timeMs;
    const float lc = lowCut_.nextBlock();
    const float hc = highCut_.nextBlock();
    if (lc != lastLc_ || hc != lastHc_) {
        lastLc_ = lc; lastHc_ = hc;
        lcL_.setParams(dsp::BiquadType::HighPass, lc, 0.707f, 0.f);
        hcL_.setParams(dsp::BiquadType::LowPass, hc, 0.707f, 0.f);
        lcR_.setParams(dsp::BiquadType::HighPass, lc, 0.707f, 0.f);
        hcR_.setParams(dsp::BiquadType::LowPass, hc, 0.707f, 0.f);
    }

    for (FrameCount i = 0; i < frames; ++i) {
        const float inL = left[i], inR = right[i];
        // Read delayed taps first.
        float dL, dR;
        if (pingPong_) {
            // L feeds R's delay and vice versa.
            const float tapL = delayL_.readInt(delayFrames);
            const float tapR = delayR_.readInt(delayFrames);
            dL = tapL; dR = tapR;
            const float fbl = hcR_.process(lcR_.process(tapR)) * fb;
            const float fbr = hcL_.process(lcL_.process(tapL)) * fb;
            delayL_.write(inL + fbl * width);
            delayR_.write(inR + fbr * width);
        } else {
            dL = delayL_.readInt(delayFrames);
            dR = delayR_.readInt(delayFrames);
            delayL_.write(inL + hcL_.process(lcL_.process(dL)) * fb);
            delayR_.write(inR + hcR_.process(lcR_.process(dR)) * fb);
        }
        left[i]  = DenormalGuard::protect(inL * dry + dL * wet);
        right[i] = DenormalGuard::protect(inR * dry + dR * wet);
    }
}

void DelayUnit::setParam(int32_t index, float value) {
    switch (index) {
        case delayparam::kTimeMs: timeMs_.set(value); break;
        case delayparam::kFeedback: feedback_.set(value); break;
        case delayparam::kWet: wetDb_.set(value); break;
        case delayparam::kDry: dryDb_.set(value); break;
        case delayparam::kLowCutHz: lowCut_.set(value); break;
        case delayparam::kHighCutHz: highCut_.set(value); break;
        case delayparam::kWidth: width_.set(value); break;
        default: break;
    }
}

float DelayUnit::getParam(int32_t index) const {
    switch (index) {
        case delayparam::kTimeMs: return timeMs_.target();
        case delayparam::kFeedback: return feedback_.target();
        case delayparam::kWet: return wetDb_.target();
        case delayparam::kDry: return dryDb_.target();
        case delayparam::kLowCutHz: return lowCut_.target();
        case delayparam::kHighCutHz: return highCut_.target();
        case delayparam::kWidth: return width_.target();
        default: return 0.f;
    }
}

} // namespace s1::audio::fx
