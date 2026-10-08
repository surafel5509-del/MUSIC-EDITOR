#pragma once
// FxChainRuntime: per-strip insert chain + the engine-wide FX pool.
//
// The pool pre-allocates a bounded number of every plugin type at engine init
// so structural changes (insert/remove/move FX) never allocate on the audio
// thread. Removed units go through a retire window (4 callbacks) before
// returning to the free list, so an in-flight render can never touch a unit
// that a control-thread rebuild is reusing.

#include "../common/Types.h"
#include "../fx/FxUnit.h"
#include "../engine/EngineCommands.h"
#include <memory>
#include <vector>

namespace s1::audio::graph {

/** Native plugin ids — must match the ordinal sent from Kotlin (FxPluginId.nativeId). */
enum NativePluginId : int32_t {
    kPluginNone = 0,
    kPluginCompressor = 1,
    kPluginLimiter = 2,
    kPluginGate = 3,
    kPluginExpander = 4,      // Compressor with ratio<1
    kPluginDeEsser = 5,
    kPluginParametricEq = 6,
    kPluginHighPass = 7,
    kPluginLowPass = 8,
    kPluginAutoFilter = 9,
    kPluginReverb = 10,
    kPluginDelay = 11,
    kPluginPingPongDelay = 12,
    kPluginChorus = 13,
    kPluginFlanger = 14,
    kPluginPhaser = 15,
    kPluginTremolo = 16,
    kPluginAutopan = 17,
    kPluginDistortion = 18,
    kPluginOverdrive = 19,   // Distortion preset topology
    kPluginBitcrusher = 20,
    kPluginAmpSim = 21,      // Distortion + cabinet tone shaping
    kPluginTapeSaturation = 22,
    kPluginPitchShift = 23,
    kPluginGain = 24,
    kPluginAnalyzer = 25,
    kPluginLoudnessMeter = 26,
    kPluginCount
};

class FxPool;

/** One slot: unit + bypass + wet mix + cached latency/tail. */
struct FxSlotRuntime {
    fx::FxUnit* unit = nullptr;   // borrowed from pool; null = empty slot
    int32_t pluginId = kPluginNone;
    bool bypassed = false;
    float wetMix = 1.f;
    FrameCount latency = 0;
};

class FxChainRuntime {
public:
    void init(FxPool* pool, float sampleRate);
    ~FxChainRuntime();

    /** Audio thread: set/clear a slot. Returns true on change. */
    bool setSlot(int slotIndex, int32_t pluginId);
    void setParam(int slotIndex, int32_t paramIndex, float value);
    void setBypass(int slotIndex, bool bypass);
    void setWetMix(int slotIndex, float mix);
    /** Audio thread: move slot content (drag-reorder in the FX rack UI). */
    void moveSlot(int from, int to);
    void clearAll();

    /** Render the chain in place over [frames] of stereo audio. */
    void process(float* left, float* right, FrameCount frames);

    FrameCount totalLatency() const;
    FrameCount maxTail() const;
    int slotCount() const { return kMaxInsertSlots; }
    const FxSlotRuntime& slot(int i) const { return slots_[i]; }

private:
    FxSlotRuntime slots_[kMaxInsertSlots];
    FxPool* pool_ = nullptr;
    float sr_ = 48000.f;
    // Wet-parallel scratch (pre-allocated):
    std::vector<float> dryL_, dryR_;
};

/**
 * Bounded per-type free lists. Sizes tuned for a 96-strip engine:
 * heavy units (reverb/pitch) are fewer; utility units are many.
 */
class FxPool {
public:
    void init(float sampleRate);
    ~FxPool();

    /** Audio thread: borrow a unit of [pluginId]. Null when exhausted. */
    fx::FxUnit* acquire(int32_t pluginId);
    /** Audio thread: schedule return after the retire window. */
    void retireDeferred(fx::FxUnit* unit, int32_t pluginId, uint64_t blockCounter);
    /** Audio thread (top of callback): release units past the retire window. */
    void processDeferred(uint64_t blockCounter);

    int32_t poolPressure() const; // 0..100 telemetry: how close to exhaustion

private:
    struct Entry { fx::FxUnit* unit; int32_t pluginId; };
    struct RetiredEntry { fx::FxUnit* unit; int32_t pluginId; uint64_t retireAtBlock; };

    std::vector<std::unique_ptr<fx::FxUnit>> owned_;   // all allocations
    std::vector<Entry> freeLists_[kPluginCount];
    std::vector<RetiredEntry> retired_;
    float sr_ = 48000.f;
};

/** Factory: create a unit for a plugin id (used by the pool only). */
std::unique_ptr<fx::FxUnit> createFxUnit(int32_t pluginId);

} // namespace s1::audio::graph
