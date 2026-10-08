// SPDX-License-Identifier: MIT
// Minimal WAV reader/writer used to load clips into RAM and write recordings.
// Streaming decode of compressed formats is handled by FFmpeg on the Kotlin
// side; the engine only ever sees PCM WAV (see docs/AUDIO_ENGINE.md).
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace studioone::engine {

/** Decoded mono/multi-channel float PCM in [-1, 1], interleaved. */
struct PcmBuffer {
    std::vector<float> interleaved;
    int sampleRate{44100};
    int channels{1};
    int64_t frames{0};
};

class WavFile {
public:
    /** Reads 16/24-bit PCM and IEEE float 32 WAV files. Returns null on error. */
    static std::shared_ptr<PcmBuffer> read(const std::string& path);

    /** Streaming writer for recordings (16/24-bit PCM). */
    class Writer {
    public:
        bool open(const std::string& path, int sampleRate, int channels, int bitDepth);
        /** Interleaved float input; converted per bitDepth. */
        bool write(const float* data, int64_t frames);
        void close();  // patches the RIFF size headers
        bool isOpen() const { return fd_ >= 0; }

    private:
        int fd_{-1};
        int sampleRate_{44100};
        int channels_{1};
        int bitDepth_{16};
        int64_t dataBytes_{0};
    };
};

}  // namespace studioone::engine
