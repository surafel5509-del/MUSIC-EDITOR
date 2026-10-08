#include "VoiceManager.h"
#include "SubtractiveSynth.h"
#include "DrumMachine.h"
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>

namespace s1::audio::instrument {

namespace {

// Preset blob layouts (float arrays; see Kotlin InstrumentPresetEncoder —
// the two sides are contractually paired, bump kBlobVersion on any change).
constexpr float kBlobVersion = 1.f;
constexpr int kSubtractiveParamCount = 21;

SynthPreset decodeSubtractive(const float* blob) {
    SynthPreset p;
    int i = 1; // blob[0] = version
    p.osc1Wave = static_cast<OscWave>(static_cast<int>(blob[i++]));
    p.osc2Wave = static_cast<OscWave>(static_cast<int>(blob[i++]));
    p.osc2DetuneCents = blob[i++];
    p.osc2Mix = blob[i++];
    p.osc2Octave = blob[i++];
    p.subMix = blob[i++];
    p.filterCutoffHz = blob[i++];
    p.filterResonance = blob[i++];
    p.filterEnvAmount = blob[i++];
    p.filter24dB = blob[i++] >= 0.5f;
    p.ampAttackMs = blob[i++];
    p.ampDecayMs = blob[i++];
    p.ampSustain = blob[i++];
    p.ampReleaseMs = blob[i++];
    p.fltAttackMs = blob[i++];
    p.fltDecayMs = blob[i++];
    p.fltSustain = blob[i++];
    p.fltReleaseMs = blob[i++];
    p.lfoRateHz = blob[i++];
    p.glideMs = blob[i++];
    p.level = blob[i++];
    (void)kSubtractiveParamCount;
    return p;
}

} // namespace

void VoiceManager::init(float sampleRate, int32_t maxVoices) {
    sr_ = sampleRate;
    maxVoices_ = maxVoices;
    held_.reserve(maxVoices);
}

VoiceManager::~VoiceManager() = default;

void VoiceManager::deassign() {
    allNotesOff();
    voices_.clear();
    drums_.reset();
    zones_.clear();
    presetBlob_.clear();
    type_ = kInstNone;
}

void VoiceManager::configure(int32_t instrumentType, const float* presetBlob, SamplePool* pool, float sampleRate) {
    deassignSilent();
    type_ = instrumentType;
    pool_ = pool;
    sr_ = sampleRate;
    if (presetBlob) {
        // Copy the blob so the caller can reclaim its buffer immediately.
        const size_t len = blobLength(instrumentType, presetBlob);
        presetBlob_.assign(presetBlob, presetBlob + len);
    } else {
        presetBlob_.clear();
    }
    const float* blob = presetBlob_.empty() ? nullptr : presetBlob_.data();

    switch (instrumentType) {
        case kInstSubtractive:
        case kInstFm:
        case kInstWavetable:
        case kInstGranular: {
            SynthPreset preset = blob ? decodeSubtractive(blob) : SynthPreset{};
            for (int v = 0; v < maxVoices_; ++v) {
                auto voice = std::make_unique<SubtractiveVoice>();
                voice->setSampleRate(sr_);
                voice->applyPreset(preset);
                voice->setMpeEnabled(instrumentType == kInstGranular ? true : mpeEnabled_);
                voices_.push_back(std::move(voice));
            }
            break;
        }
        case kInstSampler: {
            // blob: [version][zoneCount][zone: slot,root,kLo,kHi,vLo,vHi,gain,oneShot,loopS,loopE]*
            zones_.clear();
            if (blob) {
                const int zoneCount = static_cast<int>(blob[1]);
                for (int z = 0; z < zoneCount; ++z) {
                    const float* zb = blob + 2 + z * 10;
                    SampleZone zone;
                    zone.poolSlot = static_cast<int32_t>(zb[0]);
                    zone.rootKey = static_cast<uint8_t>(zb[1]);
                    zone.keyLow = static_cast<uint8_t>(zb[2]);
                    zone.keyHigh = static_cast<uint8_t>(zb[3]);
                    zone.velLow = static_cast<uint8_t>(zb[4]);
                    zone.velHigh = static_cast<uint8_t>(zb[5]);
                    zone.gainDb = zb[6];
                    zone.oneShot = zb[7] >= 0.5f;
                    zone.loopStartFrame = static_cast<int32_t>(zb[8]);
                    zone.loopEndFrame = static_cast<int32_t>(zb[9]);
                    zones_.push_back(zone);
                }
            }
            for (int v = 0; v < maxVoices_; ++v) {
                auto voice = std::make_unique<SamplerVoice>();
                voice->setSampleRate(sr_);
                voice->setPool(pool_);
                voice->setEnvelope(2.f, 1e6f, 1.f, 80.f); // sampler packs carry their own decay
                if (!zones_.empty()) voice->setZone(zones_[0]);
                voices_.push_back(std::move(voice));
            }
            break;
        }
        case kInstDrumMachine: {
            drums_ = std::make_unique<DrumMachine>();
            drums_->init(sr_, pool_, 4);
            if (blob) {
                // blob: [version][padCount][pad: slot,key,gain,tune,choke]*
                const int padCount = std::min(static_cast<int>(blob[1]), DrumMachine::kMaxPads);
                for (int p = 0; p < padCount; ++p) {
                    const float* pb = blob + 2 + p * 5;
                    DrumPadConfig cfg;
                    cfg.poolSlot = static_cast<int32_t>(pb[0]);
                    cfg.key = static_cast<uint8_t>(pb[1]);
                    cfg.gainDb = pb[2];
                    cfg.tuneCents = pb[3];
                    cfg.chokeGroup = static_cast<int32_t>(pb[4]);
                    drums_->setPad(p, cfg);
                }
            }
            break;
        }
        default:
            type_ = kInstNone;
            break;
    }
    // Re-apply macros.
    for (int m = 0; m < 8; ++m) setMacro(m, macros_[m]);
}

size_t VoiceManager::blobLength(int32_t instrumentType, const float* blob) const {
    switch (instrumentType) {
        case kInstSampler: return 2 + static_cast<size_t>(blob[1]) * 10;
        case kInstDrumMachine: return 2 + static_cast<size_t>(blob[1]) * 5;
        default: return 1 + kSubtractiveParamCount;
    }
}

void VoiceManager::deassignSilent() {
    voices_.clear();
    drums_.reset();
    zones_.clear();
    held_.clear();
    type_ = kInstNone;
}

void VoiceManager::pushEvent(const MidiEvent& ev) {
    if (!eventQueue_.push(ev)) eventQueue_.noteDrop();
}

void VoiceManager::allNotesOff() {
    for (auto& v : voices_) v->allSoundOff();
    if (drums_) drums_->allSoundOff();
    held_.clear();
    sustainPedal_ = false;
}

void VoiceManager::setMacro(int32_t index, float value) {
    if (index < 0 || index > 7) return;
    macros_[index] = value;
    for (auto& v : voices_) v->setMacro(index, value);
}

void VoiceManager::setArpeggiator(bool enabled, int32_t mode, float divisionBeats, float gate, int32_t octaves) {
    arp_.enabled = enabled;
    arp_.mode = mode;
    arp_.gate = std::clamp(gate, 0.05f, 1.f);
    arp_.octaves = std::clamp(octaves, 1, 4);
    arp_.stepBeats = std::max(0.015625f, divisionBeats);
}

int32_t VoiceManager::activeVoiceCount() const {
    int32_t n = 0;
    for (const auto& v : voices_) if (v->isActive()) ++n;
    return n;
}

Voice* VoiceManager::allocateVoice(uint8_t key) {
    // 1. Reuse a free voice.
    for (auto& v : voices_) if (!v->isActive()) return v.get();
    // 2. Steal a released (quietest envelope) voice.
    for (auto& v : voices_) {
        // A voice past note-off is the least audible steal candidate.
        if (v->currentKey() == key) return v.get(); // same key: retrigger
    }
    // 3. Round-robin steal of the oldest held voice.
    if (!voices_.empty()) {
        stealIndex_ = (stealIndex_ + 1) % static_cast<int32_t>(voices_.size());
        auto* victim = voices_[stealIndex_].get();
        victim->allSoundOff();
        return victim;
    }
    return nullptr;
}

void VoiceManager::dispatchEvent(const MidiEvent& ev) {
    switch (ev.type) {
        case 1: { // note on
            if (drums_) {
                drums_->triggerKey(ev.key, ev.velocity);
            } else if (arp_.enabled) {
                held_.push_back(HeldNote{ev.key, ev.velocity, false, nullptr});
            } else {
                if (ev.velocity == 0) { dispatchEvent(MidiEvent{ev.stripHandle, ev.sampleOffset, 0, ev.channel, ev.key, 0, 0, 0.f}); return; }
                Voice* v = allocateVoice(ev.key);
                if (v) {
                    // Sampler: pick matching zone (no RTTI in the engine: the
                    // voice array type is fixed per instrument type).
                    if (type_ == kInstSampler && !zones_.empty()) {
                        auto* sv = static_cast<SamplerVoice*>(v);
                        for (const auto& z : zones_) {
                            if (ev.key >= z.keyLow && ev.key <= z.keyHigh &&
                                ev.velocity >= z.velLow && ev.velocity <= z.velHigh) {
                                sv->setZone(z);
                                break;
                            }
                        }
                    }
                    v->noteOn(ev.key, ev.velocity);
                    held_.push_back(HeldNote{ev.key, ev.velocity, false, v});
                }
            }
            break;
        }
        case 0: { // note off
            if (arp_.enabled) {
                held_.erase(std::remove_if(held_.begin(), held_.end(),
                    [k = ev.key](const HeldNote& n) { return n.key == k && !n.sustained; }), held_.end());
            } else {
                for (auto it = held_.begin(); it != held_.end();) {
                    if (it->key == ev.key) {
                        if (sustainPedal_) { it->sustained = true; ++it; }
                        else { if (it->voice) it->voice->noteOff(ev.key); it = held_.erase(it); }
                    } else ++it;
                }
                for (auto& v : voices_) v->noteOff(ev.key);
            }
            break;
        }
        case 2: { // CC
            if (ev.key == 64) { // sustain pedal
                sustainPedal_ = ev.fval >= 0.5f;
                if (!sustainPedal_) {
                    for (auto it = held_.begin(); it != held_.end();) {
                        if (it->sustained) { if (it->voice) it->voice->noteOff(it->key); it = held_.erase(it); }
                        else ++it;
                    }
                }
            } else if (ev.key == 1) {
                setMacro(0, ev.fval); // mod wheel -> macro 0
            } else if (ev.key == 74) {
                for (auto& v : voices_) v->setTimbre(ev.fval); // brightness (MPE Y)
            }
            break;
        }
        case 3: // pitch bend (normalized -1..1 in fval)
            for (auto& v : voices_) v->setPitchBend(ev.fval);
            break;
        case 4: // channel pressure / MPE pressure
            for (auto& v : voices_) v->setPressure(ev.fval);
            break;
        case 5: // MPE timbre
            for (auto& v : voices_) v->setTimbre(ev.fval);
            break;
        case 7: // all sound off
            allNotesOff();
            break;
        default: break;
    }
}

void VoiceManager::render(float* left, float* right, FrameCount frames) {
    // 1. Drain queued events (dispatched at block granularity; the queue
    //    carries sampleOffset for a future intra-block scheduler).
    MidiEvent ev;
    while (eventQueue_.pop(ev)) dispatchEvent(ev);

    // 2. Arpeggiator: generate internal note events on the beat grid.
    if (arp_.enabled && !held_.empty()) renderArpeggiator(frames);

    // 3. Render drum pads.
    if (drums_) drums_->render(left, right, frames);

    // 4. Render poly voices.
    for (auto& v : voices_) {
        if (v->isActive()) v->render(left, right, frames);
    }
}

void VoiceManager::renderArpeggiator(FrameCount frames) {
    // Step clock in frames: stepBeats * framesPerBeat is provided by the graph
    // via setTempo (cached here to stay allocation-free).
    const float stepFrames = arp_.stepBeats * framesPerBeat_;
    if (stepFrames <= 0.f) return;

    for (FrameCount i = 0; i < frames; ++i) {
        arp_.phase += 1.f;
        if (arp_.phase >= stepFrames) {
            arp_.phase -= stepFrames;
            // Release previous arp note.
            if (arp_.noteActive && arpVoice_) {
                arpVoice_->noteOff(arp_.currentKey);
                arp_.noteActive = false;
            }
            // Collect held keys sorted. Fixed array — the audio thread must
            // not allocate (max keys: 128 across octaves).
            uint8_t keyBuf[128];
            int keyCount = 0;
            for (const auto& n : held_)
                for (int oct = 0; oct < arp_.octaves; ++oct) {
                    const int k = n.key + oct * 12;
                    if (k <= 127 && keyCount < 128) keyBuf[keyCount++] = static_cast<uint8_t>(k);
                }
            if (keyCount == 0) continue;
            std::sort(keyBuf, keyBuf + keyCount);
            keyCount = static_cast<int>(std::unique(keyBuf, keyBuf + keyCount) - keyBuf);

            uint8_t nextKey = keyBuf[0];
            switch (arp_.mode) {
                case 0: // up
                    nextKey = keyBuf[arp_.stepIndex % static_cast<size_t>(keyCount)];
                    arp_.stepIndex++;
                    break;
                case 1: // down
                    nextKey = keyBuf[static_cast<size_t>(keyCount) - 1 - (arp_.stepIndex % static_cast<size_t>(keyCount))];
                    arp_.stepIndex++;
                    break;
                case 2: { // up-down
                    const int period = keyCount * 2 - 2;
                    const int pos = period > 0 ? arp_.stepIndex % period : 0;
                    const int idx = pos < keyCount ? pos : period - pos;
                    nextKey = keyBuf[std::clamp(idx, 0, keyCount - 1)];
                    arp_.stepIndex++;
                    break;
                }
                case 3: // random
                    nextKey = keyBuf[static_cast<size_t>(arpRand_()) % static_cast<size_t>(keyCount)];
                    break;
                case 4: // ordered (as played)
                    nextKey = held_[arp_.stepIndex % held_.size()].key;
                    arp_.stepIndex++;
                    break;
                case 5: // chord (all at once)
                    for (int ki = 0; ki < keyCount; ++ki) { uint8_t k = keyBuf[ki];
                        Voice* v = allocateVoice(k);
                        if (v) v->noteOn(k, held_.front().velocity);
                    }
                    arp_.stepIndex = 0;
                    continue;
                default: break;
            }
            // Trigger with gate length proportional to arp gate.
            Voice* v = allocateVoice(nextKey);
            if (v) {
                v->noteOn(nextKey, held_.front().velocity);
                arpVoice_ = v;
                arp_.currentKey = nextKey;
                arp_.noteActive = true;
                // Schedule gate-off via envelope: shorten by immediate partial release
                // is not sample-exact; instead we track gate end here.
                arp_.gateFrames = stepFrames * arp_.gate;
                arp_.gateCountdown = arp_.gateFrames;
            }
        } else if (arp_.noteActive && arp_.gateCountdown > 0.f) {
            arp_.gateCountdown -= 1.f;
            if (arp_.gateCountdown <= 0.f && arpVoice_) {
                arpVoice_->noteOff(arp_.currentKey);
                arp_.noteActive = false;
            }
        }
    }
}

} // namespace s1::audio::instrument
