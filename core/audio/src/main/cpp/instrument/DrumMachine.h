#pragma once
// SP-16 drum machine: pad -> sampler voice mapping with choke groups
// (open hat cuts closed hat etc.), pad gain/tune, and pattern-independent
// triggering (the step sequencer runs in Kotlin and pushes MidiEvents at
// sample-accurate offsets — keeping the native side stateless w.r.t. patterns).

#include "Voice.h"
#include "SamplePool.h"
#include "SamplerVoice.h"
#include <memory>

namespace s1::audio::instrument {

struct DrumPadConfig {
    int32_t poolSlot = -1;
    uint8_t key = 0;          // GM mapping key (also accepts MIDI note-ons)
    float gainDb = 0.f;
    float tuneCents = 0.f;
    int32_t chokeGroup = -1;
};

class DrumMachine {
public:
    static constexpr int kMaxPads = 16;

    void init(float sampleRate, SamplePool* pool, int voicesPerPad = 4);
    void setPad(int padIndex, const DrumPadConfig& config);
    void triggerPad(int padIndex, uint8_t velocity, float tuneCentsOverride = 0.f);
    void triggerKey(uint8_t key, uint8_t velocity);   // MIDI note-on path
    void allSoundOff();
    void render(float* left, float* right, FrameCount frames);
    void setSampleRate(float sr);

private:
    struct PadVoices {
        DrumPadConfig config;
        std::vector<std::unique_ptr<SamplerVoice>> voices;
        int nextVoice = 0; // round-robin so fast retriggering doesn't cut itself
    };
    PadVoices pads_[kMaxPads];
    SamplePool* pool_ = nullptr;
    float sr_ = 48000.f;
};

} // namespace s1::audio::instrument
