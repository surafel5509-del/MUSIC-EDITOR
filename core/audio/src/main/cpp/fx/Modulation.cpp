#include "Modulation.h"
#include "../common/FastMath.h"
#include <algorithm>
#include <cmath>

namespace s1::audio::fx {

using namespace math;

// ── ModulatedDelay (chorus/flanger) ─────────────────────────────────────────

void ModulatedDelay::setFlangerMode(bool on) { flangerMode_ = on; }

void ModulatedDelay::init(float sampleRate) {
    sr_ = sampleRate;
    // 100ms of delay covers flanger (0.1..10ms) and chorus (5..40ms) ranges.
    const int cap = static_cast<int>(0.1f * sampleRate) + kMaxFramesPerBlock + 8;
    storageL_.assign(cap, 0.f); storageR_.assign(cap, 0.f);
    delayL_.init(storageL_.data(), cap);
    delayR_.init(storageR_.data(), cap);
    lfoL_.init(sampleRate); lfoR_.init(sampleRate);
    rate_.snap(0.8f); depth_.snap(0.5f); feedback_.snap(0.f); mix_.snap(0.5f);
    centerMs_.snap(12.f); stereoPhaseDeg_.snap(90.f);
    for (auto* p : {&rate_, &depth_, &feedback_, &mix_, &centerMs_}) p->setSmoothing(sampleRate, 30.f);
}

void ModulatedDelay::reset() { delayL_.reset(); delayR_.reset(); lfoL_.reset(); lfoR_.reset(); }

void ModulatedDelay::process(float* left, float* right, FrameCount frames) {
    const float rate = rate_.nextBlock();
    lfoL_.setRate(rate);
    lfoR_.setRate(rate);
    // Stereo phase offset: approximate by running R LFO with phase-shifted rate —
    // simpler & stable: derive R from L with quadrature mix of two LFOs.
    const float depth = depth_.nextBlock();
    const float fb = std::clamp(feedback_.nextBlock(), -0.95f, 0.95f);
    const float mix = std::clamp(mix_.nextBlock(), 0.f, 1.f);
    const float center = std::clamp(centerMs_.nextBlock(), 0.1f, 60.f) * 0.001f * sr_;
    const float depthFrames = depth * (flangerMode_ ? 0.005f * sr_ : 0.02f * sr_);
    float fbL_ = 0.f, fbR_ = 0.f;

    for (FrameCount i = 0; i < frames; ++i) {
        const float lfoVal = lfoL_.next();
        const float lfoVal2 = lfoR_.next(); // quadrature approx: different phase drift
        const float modL = center + lfoVal * depthFrames;
        const float modR = center + lfoVal2 * depthFrames;

        const float inL = left[i] + fb * fbL_;
        const float inR = right[i] + fb * fbR_;
        delayL_.write(inL);
        delayR_.write(inR);
        const float yL = delayL_.readFrac(std::max(1.f, modL));
        const float yR = delayR_.readFrac(std::max(1.f, modR));
        fbL_ = yL; fbR_ = yR;

        left[i]  = DenormalGuard::protect((1.f - mix) * left[i] + mix * yL);
        right[i] = DenormalGuard::protect((1.f - mix) * right[i] + mix * yR);
    }
}

void ModulatedDelay::setParam(int32_t index, float value) {
    switch (index) {
        case 0: rate_.set(value); break;
        case 1: depth_.set(value); break;
        case 2: feedback_.set(value); break;
        case 3: mix_.set(value); break;
        case 4: centerMs_.set(value); break;
        default: break;
    }
}

float ModulatedDelay::getParam(int32_t index) const {
    switch (index) {
        case 0: return rate_.target();
        case 1: return depth_.target();
        case 2: return feedback_.target();
        case 3: return mix_.target();
        case 4: return centerMs_.target();
        default: return 0.f;
    }
}

// ── Phaser ───────────────────────────────────────────────────────────────────

void Phaser::init(float sampleRate) {
    sr_ = sampleRate;
    lfo_.init(sampleRate);
    baseFreq_.snap(600.f); depth_.snap(0.7f); rate_.snap(0.5f);
    feedback_.snap(0.3f); mix_.snap(0.5f);
    // Each allpass stage max sweep range: 100Hz..8kHz mapped to delay < 1ms.
    for (int i = 0; i < kStages / 2; ++i) {
        const int cap = static_cast<int>(0.01f * sampleRate) + 16;
        storageL_[i].assign(cap, 0.f); storageR_[i].assign(cap, 0.f);
        allpassL_[i].init(storageL_[i].data(), cap);
        allpassR_[i].init(storageR_[i].data(), cap);
    }
}

void Phaser::reset() {
    for (int i = 0; i < kStages / 2; ++i) { allpassL_[i].reset(); allpassR_[i].reset(); }
    lfo_.reset();
}

void Phaser::process(float* left, float* right, FrameCount frames) {
    lfo_.setRate(rate_.nextBlock());
    const float base = baseFreq_.nextBlock();
    const float depth = depth_.nextBlock();
    const float fb = std::clamp(feedback_.nextBlock(), -0.9f, 0.9f);
    const float mix = mix_.nextBlock();
    float fbL = 0.f, fbR = 0.f;

    for (FrameCount i = 0; i < frames; ++i) {
        // Sweep the allpass center with the LFO (log domain feels musical).
        const float sweep = std::pow(2.f, depth * 2.5f * lfo_.next());
        const float f = std::clamp(base * sweep, 20.f, sr_ * 0.45f);
        const float delayFrames = sr_ / f; // allpass delay ~ 1/f gives phase notch at f

        float xL = left[i] + fb * fbL;
        float xR = right[i] + fb * fbR;
        const int32_t d = static_cast<int32_t>(std::max(1.f, delayFrames));
        for (int s = 0; s < kStages / 2; ++s) {
            xL = allpassL_[s].processAllpass(xL, d, 0.7f);
            xR = allpassR_[s].processAllpass(xR, d, 0.7f);
        }
        fbL = xL; fbR = xR;
        left[i]  = DenormalGuard::protect((1.f - mix) * left[i] + mix * xL);
        right[i] = DenormalGuard::protect((1.f - mix) * right[i] + mix * xR);
    }
}

void Phaser::setParam(int32_t index, float value) {
    switch (index) {
        case 0: rate_.set(value); break;
        case 1: depth_.set(value); break;
        case 2: baseFreq_.set(value); break;
        case 3: feedback_.set(value); break;
        case 4: mix_.set(value); break;
        default: break;
    }
}

float Phaser::getParam(int32_t index) const {
    switch (index) {
        case 0: return rate_.target();
        case 1: return depth_.target();
        case 2: return baseFreq_.target();
        case 3: return feedback_.target();
        case 4: return mix_.target();
        default: return 0.f;
    }
}

// ── Tremolo / AutoPan ────────────────────────────────────────────────────────

void TremoloPan::init(float sampleRate) {
    sr_ = sampleRate;
    lfo_.init(sampleRate);
    rate_.snap(4.f); depth_.snap(0.5f); panAmount_.snap(0.f);
    depth_.setSmoothing(sampleRate, 20.f);
}

void TremoloPan::reset() { lfo_.reset(); }

void TremoloPan::process(float* left, float* right, FrameCount frames) {
    lfo_.setRate(rate_.nextBlock());
    const float depth = depth_.nextBlock();
    const float panAmt = panAmount_.nextBlock();
    for (FrameCount i = 0; i < frames; ++i) {
        const float lfo = lfo_.next();
        // Tremolo: unipolar amplitude modulation 1-depth .. 1
        const float ampGain = 1.f - depth * 0.5f * (1.f - lfo);
        // AutoPan: constant-power pan modulated around center.
        const float pan = panAmt * lfo; // -1..1
        const float angle = (pan + 1.f) * 0.25f * kPi;
        const float gL = std::cos(angle), gR = std::sin(angle);
        const float l = left[i], r = right[i];
        // Pan matrix (constant power) then tremolo amplitude.
        left[i]  = DenormalGuard::protect((l * gL * gL + r * gL * gR) * ampGain);
        right[i] = DenormalGuard::protect((l * gR * gL + r * gR * gR) * ampGain);
    }
}

void TremoloPan::setParam(int32_t index, float value) {
    switch (index) {
        case 0: rate_.set(value); break;
        case 1: depth_.set(value); break;
        case 2: panAmount_.set(std::clamp(value, 0.f, 1.f)); break;
        default: break;
    }
}

float TremoloPan::getParam(int32_t index) const {
    switch (index) {
        case 0: return rate_.target();
        case 1: return depth_.target();
        case 2: return panAmount_.target();
        default: return 0.f;
    }
}

// ── AutoFilter ───────────────────────────────────────────────────────────────

void AutoFilter::init(float sampleRate) {
    sr_ = sampleRate;
    lfo_.init(sampleRate);
    lpL_.setSampleRate(sampleRate); lpR_.setSampleRate(sampleRate);
    rate_.snap(1.f); baseHz_.snap(400.f); rangeOct_.snap(3.f); resonance_.snap(4.f);
}

void AutoFilter::reset() { lpL_.reset(); lpR_.reset(); lfo_.reset(); }

void AutoFilter::process(float* left, float* right, FrameCount frames) {
    lfo_.setRate(rate_.nextBlock());
    const float base = baseHz_.nextBlock();
    const float oct = rangeOct_.nextBlock();
    const float q = resonance_.nextBlock();
    if (q != lastQ_) { lastQ_ = q; }
    // Recompute filter coefficients every 16 samples (smooth enough, cheap).
    constexpr FrameCount kCoefInterval = 16;
    for (FrameCount i = 0; i < frames; ++i) {
        if ((i % kCoefInterval) == 0) {
            const float mod = lfo_.next() * 0.5f + 0.5f; // 0..1
            const float f = base * std::pow(2.f, mod * oct);
            lpL_.setParams(dsp::BiquadType::LowPass, f, q, 0.f);
            lpR_.setParams(dsp::BiquadType::LowPass, f, q, 0.f);
        }
        left[i] = lpL_.process(left[i]);
        right[i] = lpR_.process(right[i]);
    }
}

void AutoFilter::setParam(int32_t index, float value) {
    switch (index) {
        case 0: rate_.set(value); break;
        case 1: baseHz_.set(value); break;
        case 2: rangeOct_.set(value); break;
        case 3: resonance_.set(value); break;
        default: break;
    }
}

float AutoFilter::getParam(int32_t index) const {
    switch (index) {
        case 0: return rate_.target();
        case 1: return baseHz_.target();
        case 2: return rangeOct_.target();
        case 3: return resonance_.target();
        default: return 0.f;
    }
}

// ── Distortion ───────────────────────────────────────────────────────────────

void Distortion::init(float sampleRate) {
    sr_ = sampleRate;
    preHighpassL_.setSampleRate(sampleRate); preHighpassR_.setSampleRate(sampleRate);
    preHighpassL_.setParams(dsp::BiquadType::HighPass, 120.f, 0.707f, 0.f);
    preHighpassR_.setParams(dsp::BiquadType::HighPass, 120.f, 0.707f, 0.f);
    toneL_.setSampleRate(sampleRate); toneR_.setSampleRate(sampleRate);
    toneL_.setParams(dsp::BiquadType::LowPass, 4000.f, 0.707f, 0.f);
    toneR_.setParams(dsp::BiquadType::LowPass, 4000.f, 0.707f, 0.f);
    drive_.snap(12.f); tone_.snap(0.5f); mixDb_.snap(0.f); shapeSel_.snap(0.f);
}

void Distortion::reset() { preHighpassL_.reset(); preHighpassR_.reset(); toneL_.reset(); toneR_.reset(); }

void Distortion::process(float* left, float* right, FrameCount frames) {
    const float drive = dbToGain(drive_.nextBlock());
    const float tone = tone_.nextBlock();
    const float outGain = dbToGain(-std::clamp(drive_.target(), 0.f, 48.f) * 0.5f) * dbToGain(mixDb_.nextBlock());
    const float toneHz = 200.f * std::pow(40.f, tone); // 200Hz..8kHz
    toneL_.setParams(dsp::BiquadType::LowPass, toneHz, 0.9f, 0.f);
    toneR_.setParams(dsp::BiquadType::LowPass, toneHz, 0.9f, 0.f);
    const int shape = static_cast<int>(shapeSel_.target());

    for (FrameCount i = 0; i < frames; ++i) {
        float l = preHighpassL_.process(left[i]) * drive;
        float r = preHighpassR_.process(right[i]) * drive;
        switch (shape) {
            case SoftClip: l = softClip(l); r = softClip(r); break;
            case Asymmetric:
                l = l > 0.f ? fastTanh(l) : fastTanh(2.f * l) * 0.5f;
                r = r > 0.f ? fastTanh(r) : fastTanh(2.f * r) * 0.5f;
                break;
            case Fuzz:
                l = l > 0.f ? std::min(1.f, l * 4.f) : std::max(-1.f, l * 4.f);
                r = r > 0.f ? std::min(1.f, r * 4.f) : std::max(-1.f, r * 4.f);
                break;
            case Tanh:
            default: l = fastTanh(l); r = fastTanh(r); break;
        }
        l = toneL_.process(l) * outGain;
        r = toneR_.process(r) * outGain;
        left[i] = DenormalGuard::protect(l);
        right[i] = DenormalGuard::protect(r);
    }
}

void Distortion::setParam(int32_t index, float value) {
    switch (index) {
        case 0: drive_.set(std::clamp(value, 0.f, 48.f)); break;
        case 1: tone_.set(std::clamp(value, 0.f, 1.f)); break;
        case 2: mixDb_.set(value); break;
        case 3: shapeSel_.set(value); break;
        default: break;
    }
}

float Distortion::getParam(int32_t index) const {
    switch (index) {
        case 0: return drive_.target();
        case 1: return tone_.target();
        case 2: return mixDb_.target();
        case 3: return shapeSel_.target();
        default: return 0.f;
    }
}

// ── Bitcrusher ───────────────────────────────────────────────────────────────

void Bitcrusher::init(float sampleRate) {
    sr_ = sampleRate;
    bits_.snap(12.f); rateDiv_.snap(1.f); mix_.snap(1.f);
}

void Bitcrusher::reset() { phase_ = 0.f; heldL_ = heldR_ = 0.f; }

void Bitcrusher::process(float* left, float* right, FrameCount frames) {
    const float bits = std::clamp(bits_.nextBlock(), 1.f, 24.f);
    const float div = std::max(1.f, rateDiv_.nextBlock());
    const float mix = mix_.nextBlock();
    const float levels = std::pow(2.f, bits);
    const float step = 2.f / levels;

    for (FrameCount i = 0; i < frames; ++i) {
        phase_ += 1.f;
        if (phase_ >= div) {
            phase_ -= div;
            // Quantize + triangular dither (TPDF) to decorrelate the error.
            ditherState_ = ditherState_ * 1664525u + 1013904223u;
            const float d1 = static_cast<float>(ditherState_ >> 16) / 65536.f - 0.5f;
            ditherState_ = ditherState_ * 1664525u + 1013904223u;
            const float d2 = static_cast<float>(ditherState_ >> 16) / 65536.f - 0.5f;
            const float dither = (d1 + d2) * step;
            heldL_ = std::round((left[i] + dither) / step) * step;
            heldR_ = std::round((right[i] + dither) / step) * step;
        }
        left[i]  = (1.f - mix) * left[i] + mix * heldL_;
        right[i] = (1.f - mix) * right[i] + mix * heldR_;
    }
}

void Bitcrusher::setParam(int32_t index, float value) {
    switch (index) {
        case 0: bits_.set(value); break;
        case 1: rateDiv_.set(std::max(1.f, value)); break;
        case 2: mix_.set(value); break;
        default: break;
    }
}

float Bitcrusher::getParam(int32_t index) const {
    switch (index) {
        case 0: return bits_.target();
        case 1: return rateDiv_.target();
        case 2: return mix_.target();
        default: return 0.f;
    }
}

// ── TapeSaturation ───────────────────────────────────────────────────────────

void TapeSaturation::init(float sampleRate) {
    sr_ = sampleRate;
    headL_.setSampleRate(sampleRate); headR_.setSampleRate(sampleRate);
    wowLfo_.init(sampleRate);
    saturation_.snap(0.4f); wowDepth_.snap(0.15f); noiseDb_.snap(-72.f); speedSel_.snap(1.f);
}

void TapeSaturation::reset() { hysteresis_ = 0.f; headL_.reset(); headR_.reset(); wowLfo_.reset(); }

void TapeSaturation::process(float* left, float* right, FrameCount frames) {
    const float sat = saturation_.nextBlock();
    const float wow = wowDepth_.nextBlock();
    wowLfo_.setRate(0.7f);
    // Head bump: gentle low-shelf boost whose frequency depends on "tape speed".
    const float speed = 0.5f + speedSel_.nextBlock(); // 0.5..1.5 => 7.5ips..30ips feel
    headL_.setParams(dsp::BiquadType::LowShelf, 120.f * speed, 0.7f, 3.f * sat);
    headR_.setParams(dsp::BiquadType::LowShelf, 120.f * speed, 0.7f, 3.f * sat);

    for (FrameCount i = 0; i < frames; ++i) {
        const float wowMod = 1.f + wow * 0.002f * wowLfo_.next();
        float l = headL_.process(left[i]) * wowMod;
        float r = headR_.process(right[i]) * wowMod;
        // Hysteresis-ish saturation: state feeds back slightly into the shaper,
        // producing the asymmetric harmonic profile tape is known for.
        const float driveL = 1.f + sat * 6.f;
        const float driveR = 1.f + sat * 6.f;
        float sl = fastTanh(l * driveL + hysteresis_ * sat * 0.3f);
        float srr = fastTanh(r * driveR - hysteresis_ * sat * 0.3f);
        hysteresis_ = 0.98f * hysteresis_ + 0.02f * (sl - srr);
        const float makeup = 1.f / (1.f + sat * 1.5f);
        left[i]  = DenormalGuard::protect(sl * makeup);
        right[i] = DenormalGuard::protect(srr * makeup);
    }
}

void TapeSaturation::setParam(int32_t index, float value) {
    switch (index) {
        case 0: saturation_.set(std::clamp(value, 0.f, 1.f)); break;
        case 1: wowDepth_.set(std::clamp(value, 0.f, 1.f)); break;
        case 2: noiseDb_.set(value); break;
        case 3: speedSel_.set(std::clamp(value, 0.f, 2.f)); break;
        default: break;
    }
}

float TapeSaturation::getParam(int32_t index) const {
    switch (index) {
        case 0: return saturation_.target();
        case 1: return wowDepth_.target();
        case 2: return noiseDb_.target();
        case 3: return speedSel_.target();
        default: return 0.f;
    }
}

} // namespace s1::audio::fx
