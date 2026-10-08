#pragma once
// Minimal PCM/float WAV reader & streaming writer.
//
// Reader: used by the sampler/instrument layer for factory content shipped in
// the APK assets (16/24/32-bit PCM + 32-bit float, mono/stereo).
// User imports of compressed formats (mp3/aac/ogg/flac) are decoded to PCM on
// the Kotlin side with MediaCodec and cached — the native engine only ever
// touches PCM (see docs/AUDIO_ENGINE.md §5 "Media pipeline").
//
// Writer: streaming, buffered — the audio thread NEVER touches it; the
// Recorder pushes capture blocks into an SPSC ring and a dedicated writer
// thread drains it here (see engine/Recorder.cpp).

#include "../common/Types.h"
#include <cstdio>
#include <string>
#include <vector>

namespace s1::audio::dsp {

struct WavFormat {
    int32_t sampleRate = 48000;
    int32_t channels = 2;
    int32_t bitsPerSample = 24;   // 16, 24, 32 (int) or 32 (float via formatTag 3)
    bool isFloat = false;
};

class WavReader {
public:
    ~WavReader();
    bool open(const std::string& path);
    const WavFormat& format() const { return fmt_; }
    int64_t dataFrames() const { return dataFrames_; }
    /** Read [frames] into interleaved float output (normalized -1..1). Returns frames read. */
    int64_t readInterleaved(float* out, int64_t frames);
    /** Load whole file interleaved (sampler one-shots <= 10s). */
    bool readAll(std::vector<float>& interleavedOut);
    void close();

private:
    FILE* fp_ = nullptr;
    WavFormat fmt_{};
    int64_t dataFrames_ = 0;
    int64_t readPos_ = 0;
    std::vector<uint8_t> rawBuf_;
};

class WavWriter {
public:
    ~WavWriter();
    bool open(const std::string& path, const WavFormat& fmt);
    /** Write interleaved float frames (converted to target bit depth). */
    bool writeInterleaved(const float* data, int64_t frames);
    /** Flush buffered data to disk (called periodically by the writer thread). */
    void flush();
    /** Finalize: patch sizes in the header and close. Returns false on I/O error. */
    bool close();
    bool isOpen() const { return fp_ != nullptr; }
    int64_t framesWritten() const { return framesWritten_; }

private:
    FILE* fp_ = nullptr;
    WavFormat fmt_{};
    int64_t framesWritten_ = 0;
    int64_t dataChunkOffset_ = 0;
    std::vector<uint8_t> convertBuf_;
};

} // namespace s1::audio::dsp
