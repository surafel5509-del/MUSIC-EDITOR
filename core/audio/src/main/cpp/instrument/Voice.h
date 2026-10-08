#pragma once
// Voice interface + MIDI event wire format. Voices are owned by a
// VoiceManager (one per instrument strip) and rendered additively into the
// strip scratch buffer.

#include "../common/Types.h"

namespace s1::audio::instrument {

/** MIDI event routed from JNI. sampleOffset locates the event inside the
 *  current block for sample-accurate timing (critical for tight drums). */
struct MidiEvent {
    int32_t stripHandle = 0;
    int32_t sampleOffset = 0;
    uint8_t type = 0;     // 0 noteOff, 1 noteOn, 2 cc, 3 pitchBend, 4 pressure, 5 timbre(MPE), 6 program, 7 allOff
    uint8_t channel = 0;
    uint8_t key = 60;
    uint8_t velocity = 100;
    int16_t bend = 0;     // -8192..8191
    float fval = 0.f;     // normalized CC/pressure/timbre value
};

class Voice {
public:
    virtual ~Voice() = default;
    virtual void setSampleRate(float sr) = 0;
    virtual void noteOn(uint8_t key, uint8_t velocity) = 0;
    virtual void noteOff(uint8_t key) = 0;
    /** Render [frames] added into the buffers (never overwrites). */
    virtual void render(float* left, float* right, FrameCount frames) = 0;
    virtual bool isActive() const = 0;
    virtual void allSoundOff() = 0;
    virtual int32_t currentKey() const = 0;
    // MPE dimensions (ignored by non-MPE voices):
    virtual void setPressure(float /*v*/) {}
    virtual void setPitchBend(float /*normalized -1..1*/) {}
    virtual void setTimbre(float /*v*/) {}
    // Macro controls 0..7 (normalized):
    virtual void setMacro(int32_t /*index*/, float /*value*/) {}
};

} // namespace s1::audio::instrument
