#include "DrumMachine.h"
#include <algorithm>
#include <cmath>

namespace s1::audio::instrument {

void DrumMachine::init(float sampleRate, SamplePool* pool, int voicesPerPad) {
    sr_ = sampleRate;
    pool_ = pool;
    for (int p = 0; p < kMaxPads; ++p) {
        pads_[p].voices.clear();
        for (int v = 0; v < voicesPerPad; ++v) {
            auto voice = std::make_unique<SamplerVoice>();
            voice->setSampleRate(sampleRate);
            voice->setPool(pool);
            pads_[p].voices.push_back(std::move(voice));
        }
    }
}

void DrumMachine::setSampleRate(float sr) {
    sr_ = sr;
    for (auto& pad : pads_)
        for (auto& v : pad.voices) v->setSampleRate(sr);
}

void DrumMachine::setPad(int padIndex, const DrumPadConfig& config) {
    if (padIndex < 0 || padIndex >= kMaxPads) return;
    pads_[padIndex].config = config;
    SampleZone zone;
    zone.poolSlot = config.poolSlot;
    zone.rootKey = config.key;
    zone.keyLow = 0; zone.keyHigh = 127;
    zone.gainDb = config.gainDb;
    zone.oneShot = true;
    zone.chokeGroup = config.chokeGroup;
    for (auto& v : pads_[padIndex].voices) v->setZone(zone);
}

void DrumMachine::triggerPad(int padIndex, uint8_t velocity, float tuneCentsOverride) {
    if (padIndex < 0 || padIndex >= kMaxPads) return;
    PadVoices& pad = pads_[padIndex];
    if (pad.config.poolSlot < 0 || pad.voices.empty()) return;

    // Choke: cut all voices in the same group with a 3ms fade.
    if (pad.config.chokeGroup >= 0) {
        for (auto& other : pads_) {
            if (other.config.chokeGroup == pad.config.chokeGroup) {
                for (auto& v : other.voices) v->allSoundOff();
            }
        }
    }

    SamplerVoice* voice = pad.voices[pad.nextVoice].get();
    pad.nextVoice = (pad.nextVoice + 1) % static_cast<int>(pad.voices.size());
    // Root key shifted by tuning so pitch offset rides the sampler rate math.
    SampleZone zone = voice->zone();
    zone.rootKey = static_cast<uint8_t>(std::clamp(
        static_cast<int>(pad.config.key) + static_cast<int>(std::round(tuneCentsOverride / 100.f)), 0, 127));
    voice->setZone(zone);
    voice->noteOn(zone.rootKey, velocity);
}

void DrumMachine::triggerKey(uint8_t key, uint8_t velocity) {
    for (int p = 0; p < kMaxPads; ++p) {
        if (pads_[p].config.poolSlot >= 0 && pads_[p].config.key == key) {
            triggerPad(p, velocity);
            return;
        }
    }
}

void DrumMachine::allSoundOff() {
    for (auto& pad : pads_)
        for (auto& v : pad.voices) v->allSoundOff();
}

void DrumMachine::render(float* left, float* right, FrameCount frames) {
    for (auto& pad : pads_)
        for (auto& v : pad.voices)
            if (v->isActive()) v->render(left, right, frames);
}

} // namespace s1::audio::instrument
