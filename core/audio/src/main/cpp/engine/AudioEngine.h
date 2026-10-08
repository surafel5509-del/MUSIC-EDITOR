#pragma once
// AudioEngine: Oboe stream lifecycle + full-duplex callback + ownership of
// graph/transport/recorder/sample pool/command queues.
//
// Backend strategy (docs/AUDIO_ENGINE.md §2): Oboe selects AAudio on API 26+
// and transparently falls back to OpenSL ES where AAudio is unavailable or
// buggy (the vendor blocklist lives inside Oboe). We request:
//   * PERFORMANCE_MODE_LOW_LATENCY (fast mixer / MMAP when granted)
//   * SHARING_MODE_EXCLUSIVE when the user opts in and the device allows
//   * Float PCM, framesPerCallback = user buffer size
// Device disconnections trigger onErrorAfterClose -> controlled restart with
// the same graph state (stream parameters are re-read after reopen).

#include <oboe/Oboe.h>
#include "../common/Types.h"
#include "../common/LockFreeQueue.h"
#include "../graph/AudioGraph.h"
#include "../instrument/SamplePool.h"
#include "EngineCommands.h"
#include "Transport.h"
#include "Recorder.h"
#include <atomic>
#include <memory>
#include <mutex>

namespace s1::audio::engine {

struct LatencyReportNative {
    float inputLatencyMs = 0.f;
    float outputLatencyMs = 0.f;
    float roundTripMs = 0.f;
    int32_t bufferFrames = 0;
    int32_t sampleRate = 0;
    int32_t underruns = 0;
    int32_t xruns = 0;
    bool usingMmap = false;
    bool usingAaudio = false;
};

class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    AudioEngine() = default;
    ~AudioEngine() override;

    /** Control thread: full startup. Returns false if no usable output device. */
    bool start(int32_t sampleRate, FrameCount framesPerBlock,
               int32_t maxStrips, int32_t maxInstrumentStrips,
               bool lowLatency, bool exclusive, bool inputEnabled);
    void stop();
    bool isRunning() const { return running_.load(std::memory_order_acquire); }

    /** Restart streams (device switch / settings change), preserving state. */
    bool restart();

    EngineConfig& config() { return graph_.config; }

    // ── Subsystems ──────────────────────────────────────────────────────────
    graph::AudioGraph& graph() { return graph_; }
    Transport& transport() { return transport_; }
    Recorder& recorder() { return recorder_; }
    instrument::SamplePool& samplePool() { return samplePool_; }
    CommandQueue& commands() { return commands_; }
    /** Control thread: free payloads retired by the audio thread. */
    void drainReclaim() { graph_.drainReclaim(); }

    /** Control thread: bulk playback feed into a strip's rings (prefetcher). */
    size_t feedTrackAudio(int32_t stripHandle, const float* left, const float* right, size_t frames);
    size_t feedTrackWritable(int32_t stripHandle) const;

    /** Control thread: MIDI event intake (routed by strip handle in graph). */
    void sendMidi(const instrument::MidiEvent& ev);
    using MidiQueue = LockFreeQueue<instrument::MidiEvent, 4096>;
    MidiQueue& midiQueue() { return midiQueue_; }

    LatencyReportNative latencyReport() const;

    // ── oboe callbacks (audio thread) ───────────────────────────────────────
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    void onErrorBeforeClose(oboe::AudioStream* stream, oboe::Result error) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openStreams();
    void closeStreams();
    void drainCommandQueues();

    std::shared_ptr<oboe::AudioStream> outStream_;
    std::shared_ptr<oboe::AudioStream> inStream_;
    std::atomic<bool> running_{false};
    std::atomic<bool> restartRequested_{false};
    std::atomic<int32_t> underruns_{0};
    std::mutex lifecycleMutex_; // control-thread only: start/stop/restart races

    graph::AudioGraph graph_;
    Transport transport_;
    Recorder recorder_;
    instrument::SamplePool samplePool_;
    CommandQueue commands_;
    MidiQueue midiQueue_;

    bool inputEnabled_ = true;
    uint64_t blockCounter_ = 0;

    // Callback scratch (sized once at start; never reallocated afterwards).
    std::vector<float> inputMono_;                 // raw input read target
    std::vector<float> renderL_, renderR_;          // graph stereo output
    std::vector<float> silenceFrame_;               // zero-fill spare
};

} // namespace s1::audio::engine
