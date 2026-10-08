#pragma once
// Sampler: multisample-zone playback from the RAM SamplePool.
// Hermite-interpolated variable-rate reads, per-zone root/key/velocity ranges,
// ADSR (with optional sustain loop points for sustained instruments), and
// velocity->gain curve. Used by keys/bass/strings packs and by the drum
// machine (one-shot mode with choke groups).

#include "Voice.h"
#include "SamplePool.h"
#include "../dsp/Envelope.h"
#include "../common/FastMath.h"
#include <cmath>

namespace s1::audio::instrument {

struct SampleZone {
    int32_t poolSlot = -1;
    uint8_t rootKey = 60;
    uint8_t keyLow = 0;
    uint8_t keyHigh = 127;
    uint8_t velLow = 1;
    uint8_t velHigh = 127;
    float gainDb = 0.f;
    bool oneShot = false;       // drum mode: ignore note-off until end
    int32_t loopStartFrame = -1; // -1 => no loop
    int32_t loopEndFrame = -1;
    int32_t chokeGroup = -1;
};

class SamplerVoice : public Voice {
public:
    void setSampleRate(float sr) override { sr_ = sr; }
    void setPool(SamplePool* pool) { pool_ = pool; }
    void setZone(const SampleZone& zone) { zone_ = zone; }
    const SampleZone& zone() const { return zone_; }

    void noteOn(uint8_t key, uint8_t velocity) override;
    void noteOff(uint8_t key) override;
    void render(float* left, float* right, FrameCount frames) override;
    bool isActive() const override { return active_; }
    void allSoundOff() override;
    int32_t currentKey() const override { return key_; }
    void setPitchBend(float v) override { bendNorm_ = v; }

    /** ADSR shaping for non-one-shot zones (ms, 0..1). */
    void setEnvelope(float attackMs, float decayMs, float sustain, float releaseMs);

private:
    SamplePool* pool_ = nullptr;
    SampleZone zone_{};
    dsp::AdsrEnvelope env_;
    float sr_ = 48000.f;
    uint8_t key_ = 60;
    float readPos_ = 0.f;
    float rate_ = 1.f;
    float gain_ = 1.f;
    bool active_ = false;
    bool released_ = true;
    bool oneShotActive_ = false;
    float bendNorm_ = 0.f;
    // Crossfade buffer for choke cuts (fast fade instead of hard stop).
    float chokeFade_ = 1.f;
};

} // namespace s1::audio::instrument
