#include "Recorder.h"
#include <algorithm>
#include <chrono>
#include <cstring>
#include <pthread.h>

namespace {
size_t nextPow2Static(size_t n) {
    size_t p = 1;
    while (p < n) p <<= 1;
    return p;
}
} // namespace

namespace s1::audio::engine {

Recorder::~Recorder() { shutdown(); }

void Recorder::init(float sampleRate) {
    sr_ = sampleRate;
    if (initialized_.exchange(true)) return;
    takes_.reserve(kMaxSimultaneousTakes);
    const size_t ringFrames = static_cast<size_t>(nextPow2Static(kRingSeconds * sampleRate));
    for (int i = 0; i < kMaxSimultaneousTakes; ++i) {
        auto take = std::make_unique<Take>();
        take->ringStorageL.assign(ringFrames, 0.f);
        take->ringStorageR.assign(ringFrames, 0.f);
        take->ringL = std::make_unique<SpscRingBuffer>();
        take->ringR = std::make_unique<SpscRingBuffer>();
        take->ringL->init(take->ringStorageL.data(), ringFrames);
        take->ringR->init(take->ringStorageR.data(), ringFrames);
        take->drainL.resize(4096);
        take->drainR.resize(4096);
        take->interleave.resize(4096 * 2);
        takes_.push_back(std::move(take));
    }
}

void Recorder::setSampleRate(float sr) {
    // Rate change requires re-init of rings; only legal while stopped.
    if (!isRecording()) {
        shutdown();
        initialized_.store(false);
        init(sr);
    }
}

void Recorder::shutdown() {
    endAllTakes();
    takes_.clear();
    initialized_.store(false);
}

int Recorder::beginTake(int32_t stripHandle, const std::string& wavPath,
                        int32_t channels, int32_t bitsPerSample) {
    if (!initialized_) return -1;
    for (size_t i = 0; i < takes_.size(); ++i) {
        Take* take = takes_[i].get();
        bool expected = false;
        if (take->active.compare_exchange_strong(expected, true)) {
            {
                std::lock_guard<std::mutex> lock(take->pathMutex);
                take->stripHandle = stripHandle;
                take->path = wavPath;
                take->channels = std::clamp(channels, 1, 2);
                take->framesWritten.store(0);
                take->overruns.store(0);
                take->ioError.store(false);
                dsp::WavFormat fmt;
                fmt.sampleRate = static_cast<int32_t>(sr_);
                fmt.channels = take->channels;
                fmt.bitsPerSample = bitsPerSample;
                fmt.isFloat = false;
                if (!take->writer.open(wavPath, fmt)) {
                    take->active.store(false);
                    return -1;
                }
                take->ringL->drain(take->ringL->readable());
                take->ringR->drain(take->ringR->readable());
            }
            take->writerThread = std::make_unique<std::thread>(&Recorder::writerLoop, this, take);
            return static_cast<int>(i);
        }
    }
    return -1; // all take slots busy (entitlement cap enforced earlier in Kotlin)
}

bool Recorder::endTake(int takeIndex, int64_t* framesWrittenOut) {
    if (takeIndex < 0 || takeIndex >= static_cast<int>(takes_.size())) return false;
    Take* take = takes_[takeIndex].get();
    if (!take->active.exchange(false)) return false;
    if (take->writerThread && take->writerThread->joinable()) take->writerThread->join();
    take->writerThread.reset();
    bool ok;
    {
        std::lock_guard<std::mutex> lock(take->pathMutex);
        ok = take->writer.close();
    }
    if (framesWrittenOut) *framesWrittenOut = take->framesWritten.load();
    return ok && !take->ioError.load();
}

void Recorder::endAllTakes() {
    for (int i = 0; i < static_cast<int>(takes_.size()); ++i) endTake(i);
}

bool Recorder::isRecording() const {
    for (const auto& t : takes_) if (t->active.load(std::memory_order_acquire)) return true;
    return false;
}

Recorder::TakeStats Recorder::stats(int takeIndex) const {
    TakeStats s;
    if (takeIndex < 0 || takeIndex >= static_cast<int>(takes_.size())) return s;
    const Take* t = takes_[takeIndex].get();
    s.framesWritten = t->framesWritten.load();
    s.overruns = t->overruns.load();
    s.ioError = t->ioError.load();
    return s;
}

Recorder::Take* Recorder::takeForStrip(int32_t stripHandle) {
    for (auto& t : takes_) {
        if (t->active.load(std::memory_order_acquire) && t->stripHandle == stripHandle) {
            return t.get();
        }
    }
    return nullptr;
}

void Recorder::captureStrip(int32_t stripHandle, const float* left, const float* right, FrameCount frames) {
    Take* take = takeForStrip(stripHandle);
    if (!take) return;
    const size_t wroteL = take->ringL->write(left, static_cast<size_t>(frames));
    const size_t wroteR = take->ringR->write(right, static_cast<size_t>(frames));
    const size_t dropped = static_cast<size_t>(frames) - std::min(wroteL, wroteR);
    if (dropped > 0) take->overruns.fetch_add(static_cast<int64_t>(dropped), std::memory_order_relaxed);
}

void Recorder::writerLoop(Take* take) {
    // Dedicated low-priority writer thread. Never touches audio-thread state
    // beyond the SPSC rings; all file I/O happens here.
    pthread_setname_np(pthread_self(), "s1-rec-writer");
    while (take->active.load(std::memory_order_acquire)) {
        const size_t availL = take->ringL->readable();
        const size_t availR = take->ringR->readable();
        const size_t avail = std::min(std::min(availL, availR), take->drainL.size());
        if (avail == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(3));
            continue;
        }
        take->ringL->read(take->drainL.data(), avail);
        take->ringR->read(take->drainR.data(), avail);

        bool ok;
        {
            std::lock_guard<std::mutex> lock(take->pathMutex);
            if (take->channels == 2) {
                for (size_t i = 0; i < avail; ++i) {
                    take->interleave[i * 2] = take->drainL[i];
                    take->interleave[i * 2 + 1] = take->drainR[i];
                }
                ok = take->writer.writeInterleaved(take->interleave.data(), static_cast<int64_t>(avail));
            } else {
                ok = take->writer.writeInterleaved(take->drainL.data(), static_cast<int64_t>(avail));
            }
        }
        if (!ok) take->ioError.store(true);
        take->framesWritten.fetch_add(static_cast<int64_t>(avail), std::memory_order_relaxed);
        // Periodic flush keeps crash-loss under ~0.5s of audio.
        if ((take->framesWritten.load() & 0x7FFF) < static_cast<int64_t>(avail)) {
            std::lock_guard<std::mutex> lock(take->pathMutex);
            take->writer.flush();
        }
    }
    // Final drain after stop (ring may hold the last partial block).
    for (int pass = 0; pass < 8; ++pass) {
        const size_t avail = std::min(take->ringL->readable(), take->drainL.size());
        if (avail == 0) break;
        take->ringL->read(take->drainL.data(), avail);
        take->ringR->read(take->drainR.data(), avail);
        std::lock_guard<std::mutex> lock(take->pathMutex);
        if (take->channels == 2) {
            for (size_t i = 0; i < avail; ++i) {
                take->interleave[i * 2] = take->drainL[i];
                take->interleave[i * 2 + 1] = take->drainR[i];
            }
            take->writer.writeInterleaved(take->interleave.data(), static_cast<int64_t>(avail));
        } else {
            take->writer.writeInterleaved(take->drainL.data(), static_cast<int64_t>(avail));
        }
        take->framesWritten.fetch_add(static_cast<int64_t>(avail), std::memory_order_relaxed);
    }
}

} // namespace s1::audio::engine
