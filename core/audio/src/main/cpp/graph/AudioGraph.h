#pragma once
// AudioGraph: the mixing engine.
//
// Topology (fixed two-stage to stay cycle-free and cache-friendly):
//
//   [input capture] -> monitor FX -> (record rings) -> monitor mix
//
//   strip (source: feeder ring | instrument voices)
//     -> latency-comp delay (PDC)
//     -> insert FX chain
//     -> automation-driven gain/pan/width
//     -> pre/post sends to bus strips
//     -> direct to master
//
//   bus strip -> insert FX -> gain/pan -> master
//   master    -> insert FX -> limiter -> loudness/spectrum meters -> output
//
// Every strip, bus, buffer, delay line, and FX slot is allocated from pools
// at init(). The audio callback performs zero allocations and takes zero
// locks; all mutation flows through EngineCommands applied at block start.

#include "../common/Types.h"
#include "../common/RingBuffer.h"
#include "../dsp/DelayLine.h"
#include "../dsp/Envelope.h"
#include "../dsp/Loudness.h"
#include "../dsp/Fft.h"
#include "../engine/EngineCommands.h"
#include "FxChainRuntime.h"
#include <atomic>
#include <memory>
#include <vector>

namespace s1::audio::instrument { class VoiceManager; class SamplePool; }
namespace s1::audio::engine { class Transport; class Recorder; }

namespace s1::audio::graph {

/** One native automation lane: sorted [frame, value] pairs, evaluated per block. */
struct AutomationLaneNative {
    int32_t paramId = -1;
    int32_t mode = 1;               // 0 off, 1 read, 2 touch, 3 latch, 4 write
    float* points = nullptr;        // malloc'd [count*2] — owned; retired via ReclaimQueue
    int32_t count = 0;
    float lastValue = 0.f;          // cached for touch/latch write-back
    bool dirty = false;
};

struct SendRuntime {
    int32_t targetBus = -1;         // index into bus pool, -1 = none
    fx::SmoothedParam levelDb;
    fx::SmoothedParam pan;
    bool preFader = true;
    bool enabled = false;
    // pre-render state:
    float lastLevel = -1e9f;
};

struct StripMeters {
    dsp::PeakDetector peakL, peakR;
    dsp::RmsDetector rmsL, rmsR;
    std::atomic<float> peakLin{0.f}, peakRin{0.f}, rmsLin{0.f}, rmsRin{0.f};
    std::atomic<bool> clipL{false}, clipR{false};
    void init(float sr);
    void process(const float* l, const float* r, FrameCount n);
    void reset();
};

/** A mixer strip (track). Handles are stable ints assigned by Kotlin. */
struct Strip {
    int32_t handle = -1;
    bool active = false;
    bool armed = false;             // record-enable
    int32_t order = 0;

    // Source: playback feeder rings (Kotlin prefetch thread writes).
    SpscRingBuffer feedL, feedR;
    bool sourceActive = false;

    // Instrument source (INSTRUMENT strips).
    instrument::VoiceManager* voices = nullptr;

    FxChainRuntime inserts;
    SendRuntime sends[kMaxSends];
    int32_t outputBus = -1;         // -1 => master

    fx::SmoothedParam gainDb, pan, width, inputGainDb;
    bool mute = false;
    bool solo = false;
    bool frozenBypass = false;      // frozen tracks play their bounce via feed rings

    // Latency compensation.
    dsp::DelayLine pdcL, pdcR;
    FrameCount pdcFrames = 0;

    // Monitor path (armed strips): input -> monitorFx -> phones.
    FxChainRuntime monitorFx;
    int32_t monitorMode = 0;        // 0 off, 1 on, 2 auto

    AutomationLaneNative lanes[16];

    StripMeters meters;

    // Per-strip scratch (pool-owned, pointers assigned at init).
    float* scratchL = nullptr;
    float* scratchR = nullptr;
    float* pdcStorageL = nullptr;
    float* pdcStorageR = nullptr;
    float* feedStorageL = nullptr;
    float* feedStorageR = nullptr;
};

struct BusStrip {
    int32_t handle = -1;
    bool active = false;
    FxChainRuntime inserts;
    fx::SmoothedParam gainDb, pan;
    bool mute = false;
    StripMeters meters;
    float* sumL = nullptr;
    float* sumR = nullptr;
};

/** Snapshot of master meters readable from the UI thread. */
struct MasterMeterSnapshot {
    float peakL = 0.f, peakR = 0.f, rmsL = 0.f, rmsR = 0.f;
    bool clipL = false, clipR = false;
    dsp::LoudnessSnapshotNative loudness;
};

class AudioGraph {
public:
    AudioGraph() = default;
    ~AudioGraph();

    /** Allocate all pools. Must be called from the control thread before start. */
    void init(const EngineConfig& config, int32_t maxStrips, int32_t maxInstrumentStrips);
    void setSampleRate(float sr); // re-inits time-based coefficients after device switch

    // ── Audio-thread API ────────────────────────────────────────────────────
    /** Full-duplex render: inputs captured, mix rendered to outputs. */
    void render(const float* inL, const float* inR,
                float* outL, float* outR, FrameCount frames);

    /** Apply one queued command (audio thread, top of callback). */
    void applyCommand(const EngineCommand& cmd, uint64_t blockCounter);

    // ── Control-thread API ──────────────────────────────────────────────────
    /** Poll latest master meters + loudness (UI thread, ~30Hz). */
    void pullMasterSnapshot(MasterMeterSnapshot& out);
    /** Poll one strip's meters. */
    bool pullStripSnapshot(int32_t handle, float& peakL, float& peakR, float& rmsL, float& rmsR, bool& clipL, bool& clipR);
    /** Pull latest spectrum (bandsDb must hold analyzerBandCount floats). */
    bool pullSpectrum(float* bandsDbOut);
    /** Control thread: free payloads retired by the audio thread. */
    void drainReclaim();

    /** Playback prefetch feed (Kotlin thread -> strip rings). */
    size_t feedStrip(int32_t handle, const float* left, const float* right, size_t frames);
    /** Writable frames available on a strip's feeder rings (flow control). */
    size_t stripWritable(int32_t handle) const;

    /** MIDI routing: hand an event to the target strip's voice manager. */
    void routeMidiEvent(const instrument::MidiEvent& ev);

    /** Access to voice managers (tempo sync etc.). */
    std::vector<std::unique_ptr<instrument::VoiceManager>>& voiceManagers() { return voiceManagers_; }
    int32_t analyzerBandCount() const { return kSpectrumBands; }
    int32_t poolPressure() const { return fxPool_.poolPressure(); }

    // Subsystem access (wired by AudioEngine at init).
    engine::Transport* transport = nullptr;
    engine::Recorder* recorder = nullptr;
    instrument::SamplePool* samplePool = nullptr;

    EngineConfig config{};

    static constexpr int32_t kSpectrumBands = 64;
    static constexpr FrameCount kMaxPdcFrames = 4096;

private:
    void renderStrips(FrameCount frames);
    void renderBuses(FrameCount frames);
    void renderMaster(float* outL, float* outR, FrameCount frames);
    void renderInputPath(const float* inL, const float* inR, FrameCount frames);
    void renderMetronome(FrameCount frames);
    void evaluateAutomation(Strip& strip, FrameCount frames);
    float computeStripGain(const Strip& strip) const;
    bool isAudible(const Strip& strip) const;
    Strip* stripByHandle(int32_t handle);
    BusStrip* busByHandle(int32_t handle);
    int32_t allocStripHandle();
    void freeStripHandle(int32_t handle);
    AutomationLaneNative* laneFor(Strip& strip, int32_t paramId);

    std::vector<Strip> strips_;
    std::vector<BusStrip> buses_;
    std::vector<std::unique_ptr<instrument::VoiceManager>> voiceManagers_;
    int32_t maxStrips_ = 64;

    // Master.
    FxChainRuntime masterInserts_;
    std::unique_ptr<fx::FxUnit> masterLimiter_;      // always-on safety limiter slot
    fx::SmoothedParam masterGainDb_;
    bool masterLimiterEnabled_ = true;
    StripMeters masterMeters_;
    dsp::LoudnessMeter loudness_;
    dsp::SpectrumAnalyzer spectrum_;
    std::atomic<float> masterPeakL_{0.f}, masterPeakR_{0.f};
    float* masterSumL_ = nullptr;
    float* masterSumR_ = nullptr;
    float* masterScratchL_ = nullptr;
    float* masterScratchR_ = nullptr;

    // Input capture scratch.
    float* inScratchL_ = nullptr;
    float* inScratchR_ = nullptr;
    float* monoScratch_ = nullptr;
    // Monitor path sums (armed strips' monitored signal, added to master).
    float* monitorSumL_ = nullptr;
    float* monitorSumR_ = nullptr;
    float* monFxL_ = nullptr;
    float* monFxR_ = nullptr;
    // Metronome render target.
    float* metronomeL_ = nullptr;
    float* metronomeR_ = nullptr;

    // Payloads retired by the audio thread; control thread drains & frees.
    engine::ReclaimQueue reclaim_;

    // Solo state cached per block.
    bool anySolo_ = false;

    FxPool fxPool_;
    uint64_t blockCounter_ = 0;
    float sr_ = 48000.f;
};

} // namespace s1::audio::graph
