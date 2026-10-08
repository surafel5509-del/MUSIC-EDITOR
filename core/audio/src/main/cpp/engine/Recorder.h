#pragma once
// Multitrack recorder.
//
// Audio thread: captureStrip() interleaves the dry+gain input of each armed
// strip into a per-take SPSC ring (lock-free, bounded).
// Writer thread (one per active take): drains rings, writes streaming WAV via
// dsp::WavWriter, flushes every ~500ms, finalizes headers on endTake().
// Overruns (writer too slow / I/O stall) are counted and surfaced as
// telemetry; the ring holds 1 second of audio, far beyond a healthy flush.

#include "../common/Types.h"
#include "../common/RingBuffer.h"
#include "../dsp/WavFile.h"
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace s1::audio::engine {

class Recorder {
public:
    static constexpr int kMaxSimultaneousTakes = 8;
    static constexpr float kRingSeconds = 1.0f;

    Recorder() = default;
    ~Recorder();

    /** Control thread: allocate rings/writers. Call before stream start. */
    void init(float sampleRate);
    void shutdown();

    /** Control thread: begin a take for [stripHandle]. Returns take index or -1. */
    int beginTake(int32_t stripHandle, const std::string& wavPath,
                  int32_t channels, int32_t bitsPerSample);
    /** Control thread: end a take, joins the writer thread, finalizes the WAV. */
    bool endTake(int takeIndex, int64_t* framesWrittenOut = nullptr);
    void endAllTakes();

    /** Audio thread: capture one block for a strip (interleaved into rings). */
    void captureStrip(int32_t stripHandle, const float* left, const float* right, FrameCount frames);

    /** Control thread: is any take active? (transport UI) */
    bool isRecording() const;

    struct TakeStats {
        int64_t framesWritten = 0;
        int64_t overruns = 0;      // ring write drops (data loss!)
        bool ioError = false;
    };
    TakeStats stats(int takeIndex) const;

    void setSampleRate(float sr);

private:
    struct Take {
        std::atomic<bool> active{false};
        int32_t stripHandle = -1;
        std::unique_ptr<SpscRingBuffer> ringL, ringR;
        std::vector<float> ringStorageL, ringStorageR;
        std::vector<float> drainL, drainR, interleave;
        std::unique_ptr<std::thread> writerThread;
        dsp::WavWriter writer;
        std::string path;
        int32_t channels = 2;
        std::atomic<int64_t> framesWritten{0};
        std::atomic<int64_t> overruns{0};
        std::atomic<bool> ioError{false};
        std::mutex pathMutex; // guards writer lifecycle vs begin/end races (control thread only)
    };

    void writerLoop(Take* take);
    Take* takeForStrip(int32_t stripHandle);

    std::vector<std::unique_ptr<Take>> takes_;
    float sr_ = 48000.f;
    std::atomic<bool> initialized_{false};
};

} // namespace s1::audio::engine
