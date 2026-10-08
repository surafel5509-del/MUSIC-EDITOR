#pragma once
// Engine command protocol: the ONLY way control threads (JNI/Kotlin) mutate
// engine state. Commands are pushed onto a lock-free MPSC queue and applied
// at the top of each audio callback.
//
// Real-time safety rules:
//   * Producers may allocate (control thread) — e.g. automation payloads are
//     malloc'd copies whose ownership transfers to the engine.
//   * The audio thread NEVER frees; retired payloads are pushed to the
//     ReclaimQueue and freed by the control thread between callbacks.

#include "../common/Types.h"
#include "../common/LockFreeQueue.h"
#include <atomic>

namespace s1::audio::engine {

enum class CommandType : uint16_t {
    // Transport
    kPlay, kStop, kPause, kRecordStart, kRecordStop,
    kSetPositionFrames, kSetLoopRegion, kSetTempo, kSetTimeSignature,
    kSetMetronome, kSetCountIn,
    // Track strips
    kAddStrip, kRemoveStrip, kSetStripActive,
    kSetStripGainDb, kSetStripPan, kSetStripMute, kSetStripSolo, kSetStripWidth,
    kSetStripInputGain, kSetStripOutputBus, kSetStripOrder,
    kSetSendLevel, kSetSendEnabled, kSetSendPan,
    // FX
    kSetFxSlot,          // arg1=slot, arg2=pluginId (0 = remove), payload=params
    kSetFxParam,         // arg1=slot, arg2=paramIndex, fval=value
    kSetFxBypass,        // arg1=slot, fval = 0/1
    kSetFxWetMix,        // arg1=slot, fval = 0..1
    kMoveFxSlot,         // arg1=from, arg2=to
    // Automation (payload = malloc'd float array [pos,value] pairs + count in lval)
    kUploadAutomation,   // arg1=paramId, payload=points, lval=count
    kClearAutomation,    // arg1=paramId
    kSetAutomationMode,  // arg1=paramId, arg2=mode (0=off,1=read,2=touch,3=latch,4=write)
    // Playback feed (audio data arrives via dedicated rings, not commands)
    kSetSourceActive,    // arg1=0 stop consuming, 1 consume from feeder ring
    kSeekSource,         // lval = frames to flush from feeder ring
    // Instruments
    kSetInstrument,      // arg1=instrumentType, payload=preset blob (owned)
    kRemoveInstrument,
    kSetInstrumentMacro, // arg1=macroIndex, fval
    kSetArpeggiator,     // payload = config blob
    kDrumPadSet,         // arg1=padIndex, payload = sample handle etc.
    // Sample pool (bulk float data staged before push; lval = slot)
    kLoadSample,         // payload = interleaved float data (owned), arg1=channels, arg2=sampleRate, lval=slot, fval=frames
    kUnloadSample,       // lval = slot
    // Recording
    kArmStrip,           // arg1 = strip, arg2 = 0/1
    kSetMonitorFx,       // like kSetFxSlot but on the input monitor path
    kSetMonitorMode,     // arg1 = strip, arg2 = mode
    // Buses & master
    kSetBusGain, kSetBusPan, kSetBusMute,
    kSetMasterLimiter,   // fval = ceiling dB, arg2 = 0/1 enabled
    kResetMeters, kResetLoudness,
    // Engine
    kSetBufferSize,      // applied on stream restart (control thread)
    kPanic,              // all notes off, all voices killed
    kNop,
};

/** POD command — trivially copyable so it can live in the lock-free queue. */
struct EngineCommand {
    CommandType type = CommandType::kNop;
    int32_t target = 0;      // strip/bus handle
    int32_t arg1 = 0;
    int32_t arg2 = 0;
    float fval = 0.f;
    float fval2 = 0.f;
    int64_t lval = 0;
    int64_t lval2 = 0;
    void* payload = nullptr; // ownership transfers to engine; see rules above
};

using CommandQueue = LockFreeQueue<EngineCommand, 1024>;

// ── Builders (see EngineCommands.cpp for payload-ownership rules) ────────────
EngineCommand makeSimple(CommandType type, int32_t target, int32_t arg1, float fval);
EngineCommand makeFxSlot(int32_t target, int32_t slot, int32_t pluginId,
                         const float* paramPairs, int32_t pairCount);
EngineCommand makeAutomationUpload(int32_t target, int32_t paramId,
                                   const float* pointsPosValue, int32_t pointCount);
EngineCommand makeSampleLoad(int32_t slot, float* interleavedOwned, int64_t frames,
                             int32_t channels, int32_t sampleRate);
EngineCommand makeInstrument(int32_t target, int32_t instrumentType,
                             const float* presetBlob, int32_t blobFloats);

/**
 * Pointers retired by the audio thread; freed by the control thread.
 * SPSC: audio thread produces, control thread consumes.
 */
using ReclaimQueue = LockFreeQueue<void*, 512>;

} // namespace s1::audio::engine
