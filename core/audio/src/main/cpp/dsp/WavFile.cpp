#include "WavFile.h"
#include <algorithm>
#include <cstring>

namespace s1::audio::dsp {

namespace {

uint32_t readU32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}
uint16_t readU16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}
void writeU32(std::vector<uint8_t>& v, uint32_t x) {
    v.push_back(x & 0xFF); v.push_back((x >> 8) & 0xFF);
    v.push_back((x >> 16) & 0xFF); v.push_back((x >> 24) & 0xFF);
}
void writeU16(std::vector<uint8_t>& v, uint16_t x) {
    v.push_back(x & 0xFF); v.push_back((x >> 8) & 0xFF);
}
void writeStr(std::vector<uint8_t>& v, const char* s) {
    v.insert(v.end(), s, s + 4);
}

int32_t read24(const uint8_t* p) {
    int32_t v = p[0] | (p[1] << 8) | (p[2] << 16);
    if (v & 0x800000) v |= ~0xFFFFFF; // sign extend
    return v;
}

} // namespace

// ── WavReader ────────────────────────────────────────────────────────────────

WavReader::~WavReader() { close(); }

bool WavReader::open(const std::string& path) {
    close();
    fp_ = std::fopen(path.c_str(), "rb");
    if (!fp_) return false;

    uint8_t header[44];
    if (std::fread(header, 1, 44, fp_) != 44) { close(); return false; }
    if (std::memcmp(header, "RIFF", 4) != 0 || std::memcmp(header + 8, "WAVE", 4) != 0) {
        close(); return false;
    }
    // Walk chunks from byte 12 (the probe above consumed the RIFF header).
    std::fseek(fp_, 12, SEEK_SET);
    bool haveFmt = false;
    while (true) {
        uint8_t chunkHdr[8];
        if (std::fread(chunkHdr, 1, 8, fp_) != 8) break;
        const uint32_t size = readU32(chunkHdr + 4);
        if (std::memcmp(chunkHdr, "fmt ", 4) == 0) {
            std::vector<uint8_t> fmt(size);
            if (std::fread(fmt.data(), 1, size, fp_) != size) { close(); return false; }
            const uint16_t formatTag = readU16(fmt.data());
            fmt_.channels = readU16(fmt.data() + 2);
            fmt_.sampleRate = static_cast<int32_t>(readU32(fmt.data() + 4));
            fmt_.bitsPerSample = readU16(fmt.data() + 14);
            fmt_.isFloat = (formatTag == 3); // WAVE_FORMAT_IEEE_FLOAT
            haveFmt = true;
            if (size % 2) std::fseek(fp_, 1, SEEK_CUR); // pad byte
        } else if (std::memcmp(chunkHdr, "data", 4) == 0) {
            if (!haveFmt) { close(); return false; }
            const int bytesPerFrame = fmt_.channels * fmt_.bitsPerSample / 8;
            if (bytesPerFrame <= 0) { close(); return false; }
            dataFrames_ = size / bytesPerFrame;
            return true;
        } else {
            std::fseek(fp_, size + (size % 2), SEEK_CUR); // skip unknown chunk
        }
    }
    close();
    return false;
}

int64_t WavReader::readInterleaved(float* out, int64_t frames) {
    if (!fp_) return 0;
    frames = std::min(frames, dataFrames_ - readPos_);
    if (frames <= 0) return 0;
    const int channels = fmt_.channels;
    const int bytesPerSample = fmt_.bitsPerSample / 8;
    const size_t rawBytes = static_cast<size_t>(frames * channels * bytesPerSample);
    rawBuf_.resize(rawBytes);
    if (std::fread(rawBuf_.data(), 1, rawBytes, fp_) != rawBytes) return 0;
    const int64_t totalSamples = frames * channels;
    if (fmt_.isFloat && fmt_.bitsPerSample == 32) {
        std::memcpy(out, rawBuf_.data(), rawBytes);
    } else {
        for (int64_t i = 0; i < totalSamples; ++i) {
            const uint8_t* p = rawBuf_.data() + i * bytesPerSample;
            switch (fmt_.bitsPerSample) {
                case 16: out[i] = static_cast<int16_t>(readU16(p)) * (1.0f / 32768.0f); break;
                case 24: out[i] = read24(p) * (1.0f / 8388608.0f); break;
                case 32: {
                    int32_t v = static_cast<int32_t>(readU32(p));
                    out[i] = v * (1.0f / 2147483648.0f);
                    break;
                }
                default: out[i] = 0.f;
            }
        }
    }
    readPos_ += frames;
    return frames;
}

bool WavReader::readAll(std::vector<float>& out) {
    out.resize(static_cast<size_t>(dataFrames_ * fmt_.channels));
    const int64_t got = readInterleaved(out.data(), dataFrames_);
    out.resize(static_cast<size_t>(got * fmt_.channels));
    return got == dataFrames_;
}

void WavReader::close() {
    if (fp_) { std::fclose(fp_); fp_ = nullptr; }
    readPos_ = 0;
    dataFrames_ = 0;
}

// ── WavWriter ────────────────────────────────────────────────────────────────

WavWriter::~WavWriter() { close(); }

bool WavWriter::open(const std::string& path, const WavFormat& fmt) {
    close();
    fp_ = std::fopen(path.c_str(), "wb");
    if (!fp_) return false;
    fmt_ = fmt;
    framesWritten_ = 0;

    // Reserve header; sizes patched in close().
    const int bytesPerSample = fmt.bitsPerSample / 8;
    const int blockAlign = fmt.channels * bytesPerSample;
    std::vector<uint8_t> h;
    writeStr(h, "RIFF"); writeU32(h, 0); writeStr(h, "WAVE");
    writeStr(h, "fmt "); writeU32(h, 18); // extended fmt chunk (works for PCM+float)
    writeU16(h, fmt.isFloat ? 3 : 1);
    writeU16(h, static_cast<uint16_t>(fmt.channels));
    writeU32(h, static_cast<uint32_t>(fmt.sampleRate));
    writeU32(h, static_cast<uint32_t>(fmt.sampleRate * blockAlign));
    writeU16(h, static_cast<uint16_t>(blockAlign));
    writeU16(h, static_cast<uint16_t>(fmt.bitsPerSample));
    writeU16(h, 0); // extension size
    writeStr(h, "data"); writeU32(h, 0);
    dataChunkOffset_ = static_cast<int64_t>(h.size()) - 4;
    std::fwrite(h.data(), 1, h.size(), fp_);
    return true;
}

bool WavWriter::writeInterleaved(const float* data, int64_t frames) {
    if (!fp_ || frames <= 0) return false;
    const int bytesPerSample = fmt_.bitsPerSample / 8;
    const int64_t totalSamples = frames * fmt_.channels;
    const size_t rawBytes = static_cast<size_t>(totalSamples * bytesPerSample);
    convertBuf_.resize(rawBytes);
    uint8_t* p = convertBuf_.data();
    for (int64_t i = 0; i < totalSamples; ++i) {
        const float s = std::clamp(data[i], -1.0f, 1.0f);
        switch (fmt_.bitsPerSample) {
            case 16: {
                const auto v = static_cast<int16_t>(s * 32767.0f);
                p[0] = v & 0xFF; p[1] = (v >> 8) & 0xFF;
                p += 2; break;
            }
            case 24: {
                const int32_t v = static_cast<int32_t>(s * 8388607.0f);
                p[0] = v & 0xFF; p[1] = (v >> 8) & 0xFF; p[2] = (v >> 16) & 0xFF;
                p += 3; break;
            }
            case 32:
                if (fmt_.isFloat) {
                    std::memcpy(p, &s, 4);
                } else {
                    const auto v = static_cast<int32_t>(s * 2147483647.0f);
                    p[0] = v & 0xFF; p[1] = (v >> 8) & 0xFF;
                    p[2] = (v >> 16) & 0xFF; p[3] = (v >> 24) & 0xFF;
                }
                p += 4; break;
            default: return false;
        }
    }
    if (std::fwrite(convertBuf_.data(), 1, rawBytes, fp_) != rawBytes) return false;
    framesWritten_ += frames;
    return true;
}

void WavWriter::flush() {
    if (fp_) std::fflush(fp_);
}

bool WavWriter::close() {
    if (!fp_) return true;
    // Patch RIFF and data chunk sizes.
    const int bytesPerSample = fmt_.bitsPerSample / 8;
    const uint32_t dataBytes = static_cast<uint32_t>(framesWritten_ * fmt_.channels * bytesPerSample);
    const uint32_t riffBytes = dataBytes + static_cast<uint32_t>(dataChunkOffset_) - 4;
    std::fseek(fp_, 4, SEEK_SET);
    std::fwrite(&riffBytes, 4, 1, fp_);
    std::fseek(fp_, dataChunkOffset_, SEEK_SET);
    std::fwrite(&dataBytes, 4, 1, fp_);
    const bool ok = std::fclose(fp_) == 0;
    fp_ = nullptr;
    return ok;
}

} // namespace s1::audio::dsp
