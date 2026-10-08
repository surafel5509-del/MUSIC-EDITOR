#pragma once
// Transport: sample-accurate playhead, loop/punch regions, tempo, count-in,
// and metronome synthesis. State changes from the control thread are atomic;
// position advancement and click scheduling happen exclusively on the audio
// thread. Clicks are pre-rendered at init (sine burst + exponential decay) —
// scheduling is pure index math with zero allocation.

#include "../common/Types.h"
#include <atomic>
#include <vector>

namespace s1::audio::engine {

enum class TransportStateNative : int32_t {
    kStopped = 0, kPlaying = 1, kRecording = 2, kPaused = 3, kCountIn = 4,
};

class Transport {
public:
    void init(float sampleRate);
    void setSampleRate(float sr);

    // ── Control thread ──────────────────────────────────────────────────────
    void play();                       // from current position
    void record();                     // play + arm capture (Recorder observes state)
    void stop();                       // returns to last start position
    void pause();
    void setPosition(FramePosition frames);
    void setLoop(bool enabled, FramePosition start, FramePosition end);
    void setPunch(bool enabled, FramePosition inFrame, FramePosition outFrame);
    void setTempo(float bpm);
    void setTimeSignature(int numerator, int beatUnit);
    void setMetronome(bool enabled, float volume01);
    void setCountInBars(int bars);     // 0 = off
    void setMetronomeInPhones(bool on) { metronomeInPhones_ = on; }

    // ── Audio thread ────────────────────────────────────────────────────────
    /** Advance the playhead by [frames]; handles loop wrap & punch window. */
    void advance(FrameCount frames);
    /** Render metronome clicks for this block (additive). */
    void renderMetronome(float* left, float* right, FrameCount frames);

    // ── Shared state (atomics, any thread) ──────────────────────────────────
    TransportStateNative state() const { return state_.load(std::memory_order_acquire); }
    bool isPlaying() const {
        const auto s = state();
        return s == TransportStateNative::kPlaying || s == TransportStateNative::kRecording ||
               s == TransportStateNative::kCountIn;
    }
    bool isRecording() const { return state() == TransportStateNative::kRecording; }
    bool inPunchWindow() const {
        const FramePosition p = positionFrames();
        return !punchEnabled_ || (p >= punchIn_ && (punchOut_ < 0 || p <= punchOut_));
    }
    bool metronomeEnabled() const { return metronomeEnabled_.load(std::memory_order_relaxed); }
    FramePosition positionFrames() const { return position_.load(std::memory_order_relaxed); }
    float bpm() const { return bpm_.load(std::memory_order_relaxed); }
    float framesPerBeat() const { return framesPerBeat_.load(std::memory_order_relaxed); }
    FramePosition loopStart() const { return loopStart_; }
    FramePosition loopEnd() const { return loopEnd_; }

private:
    void startInternal(bool withCountIn);

    std::atomic<TransportStateNative> state_{TransportStateNative::kStopped};
    std::atomic<FramePosition> position_{0};
    std::atomic<float> bpm_{120.f};
    std::atomic<float> framesPerBeat_{24000.f};
    std::atomic<bool> metronomeEnabled_{false};
    std::atomic<float> metronomeVolume_{0.7f};
    bool metronomeInPhones_ = true;

    FramePosition startPosition_ = 0;
    bool loopEnabled_ = false;
    FramePosition loopStart_ = 0, loopEnd_ = 0;
    bool punchEnabled_ = false;
    FramePosition punchIn_ = 0, punchOut_ = -1;
    int countInBars_ = 0;
    int tsNumerator_ = 4, tsBeatUnit_ = 4;
    FramePosition countInRemaining_ = 0;

    // Pre-rendered clicks: accent (1.5kHz) and normal (1kHz).
    std::vector<float> clickAccent_;
    std::vector<float> clickNormal_;
    float sr_ = 48000.f;

    // Click scheduler state.
    double beatPhaseFrames_ = 0.0;   // frames remaining until the next beat
    int beatInBar_ = 0;

    /** One ringing click instance (accent + normal have independent voices). */
    struct ClickVoice {
        const std::vector<float>* buf = nullptr;
        size_t pos = 0;
        bool active = false;
        void trigger(const std::vector<float>* b) { buf = b; pos = 0; active = true; }
        void stop() { active = false; }
    } clickAccentVoice_, clickNormalVoice_;
};

} // namespace s1::audio::engine
