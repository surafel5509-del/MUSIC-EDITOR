#include "Transport.h"
#include <algorithm>
#include <cmath>

namespace s1::audio::engine {

namespace {
// Click: 3ms sine burst at f, exponential decay over ~40ms.
std::vector<float> makeClick(float sr, float freqHz, float amplitude) {
    const int len = static_cast<int>(0.05f * sr);
    std::vector<float> buf(len);
    const float decay = std::exp(-1.f / (0.010f * sr));
    float env = 1.f;
    for (int i = 0; i < len; ++i) {
        buf[i] = amplitude * env * std::sin(2.f * static_cast<float>(M_PI) * freqHz * i / sr);
        env *= decay;
    }
    return buf;
}
} // namespace

void Transport::init(float sampleRate) {
    sr_ = sampleRate;
    clickAccent_ = makeClick(sr_, 1500.f, 0.9f);
    clickNormal_ = makeClick(sr_, 1000.f, 0.6f);
    setTempo(120.f);
}

void Transport::setSampleRate(float sr) {
    sr_ = sr;
    clickAccent_ = makeClick(sr, 1500.f, 0.9f);
    clickNormal_ = makeClick(sr, 1000.f, 0.6f);
    setTempo(bpm_.load());
}

void Transport::setTempo(float bpm) {
    bpm_.store(std::clamp(bpm, 10.f, 400.f), std::memory_order_relaxed);
    framesPerBeat_.store(sr_ * 60.f / bpm_.load(), std::memory_order_relaxed);
}

void Transport::setTimeSignature(int numerator, int beatUnit) {
    tsNumerator_ = std::clamp(numerator, 1, 16);
    tsBeatUnit_ = beatUnit;
}

void Transport::setMetronome(bool enabled, float volume01) {
    metronomeEnabled_.store(enabled, std::memory_order_relaxed);
    metronomeVolume_.store(std::clamp(volume01, 0.f, 1.f), std::memory_order_relaxed);
}

void Transport::setCountInBars(int bars) { countInBars_ = std::clamp(bars, 0, 8); }

void Transport::setLoop(bool enabled, FramePosition start, FramePosition end) {
    loopEnabled_ = enabled;
    loopStart_ = start;
    loopEnd_ = std::max(end, start + 1);
}

void Transport::setPunch(bool enabled, FramePosition inFrame, FramePosition outFrame) {
    punchEnabled_ = enabled;
    punchIn_ = inFrame;
    punchOut_ = outFrame;
}

void Transport::setPosition(FramePosition frames) {
    position_.store(std::max(0L, frames), std::memory_order_relaxed);
    // Realign the metronome grid.
    const double fpb = framesPerBeat_.load();
    beatPhaseFrames_ = fpb - std::fmod(static_cast<double>(frames), fpb);
    if (beatPhaseFrames_ >= fpb) beatPhaseFrames_ = 0.0;
    const double beats = static_cast<double>(frames) / fpb;
    const int beatsPerBar = tsNumerator_ * 4 / tsBeatUnit_;
    beatInBar_ = static_cast<int>(std::fmod(beats, static_cast<double>(beatsPerBar)));
}

void Transport::startInternal(bool withCountIn) {
    startPosition_ = position_.load();
    const double fpb = framesPerBeat_.load();
    beatPhaseFrames_ = 0.0;
    const double beats = static_cast<double>(startPosition_) / fpb;
    const int beatsPerBar = std::max(1, tsNumerator_ * 4 / tsBeatUnit_);
    beatInBar_ = static_cast<int>(std::fmod(beats, static_cast<double>(beatsPerBar)));
    if (withCountIn && countInBars_ > 0) {
        countInRemaining_ = static_cast<FramePosition>(countInBars_ * beatsPerBar * fpb);
        state_.store(TransportStateNative::kCountIn, std::memory_order_release);
    }
}

void Transport::play() {
    startInternal(true);
    if (countInRemaining_ <= 0) state_.store(TransportStateNative::kPlaying, std::memory_order_release);
}

void Transport::record() {
    startInternal(true);
    if (countInRemaining_ <= 0) state_.store(TransportStateNative::kRecording, std::memory_order_release);
}

void Transport::stop() {
    state_.store(TransportStateNative::kStopped, std::memory_order_release);
    position_.store(startPosition_, std::memory_order_relaxed);
    countInRemaining_ = 0;
}

void Transport::pause() {
    if (isPlaying()) state_.store(TransportStateNative::kPaused, std::memory_order_release);
}

void Transport::advance(FrameCount frames) {
    const auto st = state_.load(std::memory_order_acquire);
    if (st == TransportStateNative::kStopped || st == TransportStateNative::kPaused) return;

    if (st == TransportStateNative::kCountIn) {
        countInRemaining_ -= frames;
        if (countInRemaining_ <= 0) {
            // Count-in finished: enter the armed mode.
            state_.store(punchEnabled_ ? TransportStateNative::kRecording
                                       : TransportStateNative::kPlaying,
                         std::memory_order_release);
        }
        return; // playhead does not move during count-in
    }

    FramePosition pos = position_.load(std::memory_order_relaxed) + frames;
    if (loopEnabled_ && pos >= loopEnd_) {
        // Wrap to loop start preserving sub-loop phase.
        const FramePosition loopLen = loopEnd_ - loopStart_;
        pos = loopStart_ + ((pos - loopStart_) % loopLen);
        beatPhaseFrames_ = 0.0; // clicks realign on the loop point
        beatInBar_ = 0;
    }
    position_.store(pos, std::memory_order_relaxed);
}

void Transport::renderMetronome(float* left, float* right, FrameCount frames) {
    if (!metronomeEnabled_.load(std::memory_order_relaxed)) return;
    const auto st = state_.load(std::memory_order_acquire);
    if (st == TransportStateNative::kStopped || st == TransportStateNative::kPaused) return;

    const float volume = metronomeVolume_.load(std::memory_order_relaxed);
    const double fpb = framesPerBeat_.load(std::memory_order_relaxed);
    const int beatsPerBar = std::max(1, tsNumerator_ * 4 / tsBeatUnit_);

    // Helper: mix a click voice into the block starting at frame [from].
    auto mixVoice = [&](ClickVoice& cv, FrameCount from) {
        if (!cv.active || !cv.buf) return;
        const auto& buf = *cv.buf;
        for (FrameCount i = from; i < frames && cv.pos < buf.size(); ++i, ++cv.pos) {
            const float s = buf[cv.pos] * volume;
            left[i] += s;
            right[i] += s;
        }
        if (cv.pos >= buf.size()) cv.stop();
    };

    // 1) Continue clicks already ringing from the previous block.
    mixVoice(clickAccentVoice_, 0);
    mixVoice(clickNormalVoice_, 0);

    // 2) Schedule beat crossings that fall inside this block.
    const bool audible = (st == TransportStateNative::kCountIn) || isPlaying();
    if (audible && fpb > 1.0) {
        double phase = beatPhaseFrames_;
        for (FrameCount i = 0; i < frames; ++i) {
            phase -= 1.0;
            if (phase <= 0.0) {
                const bool accent = (beatInBar_ % beatsPerBar) == 0;
                if (accent) clickAccentVoice_.trigger(&clickAccent_);
                else clickNormalVoice_.trigger(&clickNormal_);
                mixVoice(accent ? clickAccentVoice_ : clickNormalVoice_, i);
                beatInBar_ = (beatInBar_ + 1) % beatsPerBar;
                phase += fpb;
            }
        }
        beatPhaseFrames_ = phase + static_cast<double>(frames);
    }
}

} // namespace s1::audio::engine
