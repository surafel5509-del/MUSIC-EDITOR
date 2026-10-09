// SPDX-License-Identifier: MIT
// Top-level engine: owns the Oboe stream, the graph and the record writer.
//
// Lifecycle: create -> start(config) -> [use] -> stop -> destroy.
// The stream callback is the ONLY realtime thread in the system.
#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include <oboe/Oboe.h>

#include "../graph/AudioGraph.h"
#include "WavFile.h"

namespace studioone::engine {

struct EngineConfig {
    int32_t sampleRate{44100};
    int32_t framesPerCallback{128};
    bool inputEnabled{false};
    bool useLowLatency{true};
    bool forceOpenSles{false};
};

class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    AudioEngine();
    ~AudioEngine() override;

    AudioEngine(const AudioEngine&) = delete;
    AudioEngine& operator=(const AudioEngine&) = delete;

    bool start(const EngineConfig& config);
    void stop();
    bool isRunning() const noexcept { return stream_ != nullptr; }

    /** Report from Oboe: which API and buffer size actually got negotiated. */
    struct StreamInfo {
        int32_t sampleRate{0};
        int32_t framesPerBurst{0};
        int32_t bufferSizeInFrames{0};
        bool usingAAudio{true};
        double outputLatencyMs{0.0};
    };
    StreamInfo streamInfo() const noexcept;

    graph::AudioGraph& graph() noexcept { return graph_; }

    // ---- Recording (background thread drains the record ring) -------------
    bool startRecording(const std::string& path, int bitDepth);
    void stopRecording();
    bool isRecording() const noexcept { return recordWriterRunning_.load(); }

    // ---- Oboe callback -----------------------------------------------------
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;

private:
    void recordWriterLoop();

    std::shared_ptr<oboe::AudioStream> stream_;
    graph::AudioGraph graph_;
    EngineConfig config_;

    std::mutex streamMutex_;  // guards open/close only; never held in callback

    // Recording writer (drains graph_.recordRing()).
    std::atomic<bool> recordWriterRunning_{false};
    std::thread recordThread_;
    WavFile::Writer recordWriter_;
    int recordChannels_{2};

    // Preallocated deinterleave staging for the callback (RT-safe: resized
    // only in start(), never inside the audio callback).
    std::vector<float> staging_;
};

}  // namespace studioone::engine
