#pragma once
// VoiceManager: polyphony, voice stealing, MIDI event intake, arpeggiator,
// and instrument configuration. One instance per instrument strip, created
// from the pre-allocated pool in AudioGraph::init.

#include "Voice.h"
#include "SamplePool.h"
#include "../common/LockFreeQueue.h"
#include <memory>
#include <vector>

// Forward declarations for concrete instruments owned by the manager.
namespace s1::audio::instrument { class DrumMachine; class SamplerVoice; }

namespace s1::audio::instrument {

enum InstrumentType : int32_t {
    kInstNone = 0,
    kInstSubtractive = 1,
    kInstFm = 2,            // mapped onto subtractive topology with FM-flavored defaults
    kInstWavetable = 3,     // ditto (wavetable preset blobs)
    kInstSampler = 4,
    kInstDrumMachine = 5,
    kInstGranular = 6,      // roadmap: renders via sampler pool w/ grain flags
};

class VoiceManager {
public:
    void init(float sampleRate, int32_t maxVoices);
    ~VoiceManager();

    bool assigned() const { return type_ != kInstNone; }
    void deassign();

    /** Audio thread: configure from preset blob (malloc'd floats, ownership kept by caller/graph reclaim). */
    void configure(int32_t instrumentType, const float* presetBlob, SamplePool* pool, float sampleRate);

    /** Audio thread: push a MIDI event for this strip (called by graph drain). */
    void pushEvent(const MidiEvent& ev);

    /** Control/audio thread: tempo for arpeggiator timing (frames per beat). */
    void setTempo(float framesPerBeat) { framesPerBeat_ = framesPerBeat; }
    void setMpeEnabled(bool on) { mpeEnabled_ = on; }

    /** Audio thread: render all voices into L/R (additive). */
    void render(float* left, float* right, FrameCount frames);

    void allNotesOff();
    void setMacro(int32_t index, float value);
    void setArpeggiator(bool enabled, int32_t mode, float divisionBeats, float gate, int32_t octaves);

    int32_t activeVoiceCount() const;
    int32_t type() const { return type_; }

private:
    void dispatchEvent(const MidiEvent& ev);
    Voice* allocateVoice(uint8_t key);
    void renderArpeggiator(FrameCount frames);
    void deassignSilent();
    size_t blobLength(int32_t instrumentType, const float* blob) const;

    int32_t type_ = kInstNone;
    int32_t maxVoices_ = 16;
    float sr_ = 48000.f;
    SamplePool* pool_ = nullptr;
    std::vector<std::unique_ptr<Voice>> voices_;

    // Events queued by the graph, drained at the top of render().
    LockFreeQueue<MidiEvent, 1024> eventQueue_;

    // Held-note table for voice stealing & all-notes-off.
    struct HeldNote { uint8_t key; uint8_t velocity; bool sustained; Voice* voice; };
    std::vector<HeldNote> held_;

    // Concrete instrument backends.
    std::unique_ptr<DrumMachine> drums_;
    std::vector<SampleZone> zones_;        // sampler multisample zones
    bool mpeEnabled_ = false;

    // Arpeggiator state.
    struct ArpState {
        bool enabled = false;
        int32_t mode = 0;             // 0 up, 1 down, 2 updown, 3 random, 4 ordered, 5 chord
        float stepBeats = 0.5f;       // note grid in beats (converted via framesPerBeat_)
        float gate = 0.8f;
        int32_t octaves = 1;
        float phase = 0.f;
        int32_t stepIndex = 0;
        uint8_t currentKey = 0;
        bool noteActive = false;
        float gateFrames = 0.f;
        float gateCountdown = 0.f;
    } arp_;
    Voice* arpVoice_ = nullptr;
    float framesPerBeat_ = 0.f;       // 0 => arpeggiator disabled until tempo known
    uint32_t arpRandState_ = 2463534242u;
    inline uint32_t arpRand_() {      // xorshift32 — no libc rand on audio thread
        arpRandState_ ^= arpRandState_ << 13;
        arpRandState_ ^= arpRandState_ >> 17;
        arpRandState_ ^= arpRandState_ << 5;
        return arpRandState_;
    }

    // Voice stealing cursor.
    int32_t stealIndex_ = 0;

    // Sustain pedal.
    bool sustainPedal_ = false;

    // Preset blob (copied; kept for re-configure on sample rate change).
    std::vector<float> presetBlob_;
    float macros_[8] = {0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f};
};

} // namespace s1::audio::instrument
