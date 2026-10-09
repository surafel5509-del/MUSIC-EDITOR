// SPDX-License-Identifier: MIT
#include "WavFile.h"

#include <fcntl.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <sys/stat.h>

namespace studioone::engine {

namespace {

struct ChunkHeader {
    char id[4];
    uint32_t size;
};

uint32_t readU32(const uint8_t* p) {
    return p[0] | (p[1] << 8) | (p[2] << 16) | (p[3] << 24);
}

}  // namespace

std::shared_ptr<PcmBuffer> WavFile::read(const std::string& path) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return nullptr;

    auto buffer = std::make_shared<PcmBuffer>();
    uint8_t header[12];
    if (fread(header, 1, 12, f) != 12 ||
        memcmp(header, "RIFF", 4) != 0 || memcmp(header + 8, "WAVE", 4) != 0) {
        fclose(f);
        return nullptr;
    }

    uint16_t formatTag = 1;
    int bits = 0;
    bool fmtSeen = false;
    std::vector<uint8_t> rawData;

    while (true) {
        ChunkHeader chunk;
        if (fread(&chunk, 1, 8, f) != 8) break;
        if (memcmp(chunk.id, "fmt ", 4) == 0) {
            uint8_t fmt[40];
            const size_t toRead = chunk.size < 40 ? chunk.size : 40;
            if (fread(fmt, 1, toRead, f) != toRead) break;
            if (chunk.size > toRead) fseek(f, static_cast<long>(chunk.size - toRead), SEEK_CUR);
            formatTag = static_cast<uint16_t>(fmt[0] | (fmt[1] << 8));
            buffer->channels = fmt[2] | (fmt[3] << 8);
            buffer->sampleRate = static_cast<int>(readU32(fmt + 4));
            bits = fmt[14] | (fmt[15] << 8);
            fmtSeen = true;
            if (bits != 16 && bits != 24 && bits != 32) break;
        } else if (memcmp(chunk.id, "data", 4) == 0) {
            if (!fmtSeen) break;
            rawData.resize(chunk.size);
            if (fread(rawData.data(), 1, chunk.size, f) != chunk.size) break;
            break;
        } else {
            fseek(f, static_cast<long>(chunk.size + (chunk.size & 1)), SEEK_CUR);
        }
    }
    fclose(f);

    if (rawData.empty() || buffer->channels <= 0 || bits == 0) return nullptr;

    const int bytesPerSample = bits / 8;
    const int64_t totalSamples = static_cast<int64_t>(rawData.size()) / bytesPerSample;
    buffer->frames = totalSamples / buffer->channels;
    buffer->interleaved.resize(static_cast<size_t>(totalSamples));

    const uint8_t* p = rawData.data();
    switch (bits) {
        case 16:
            for (int64_t i = 0; i < totalSamples; ++i, p += 2) {
                const int16_t s = static_cast<int16_t>(p[0] | (p[1] << 8));
                buffer->interleaved[i] = s / 32768.f;
            }
            break;
        case 24:
            for (int64_t i = 0; i < totalSamples; ++i, p += 3) {
                int32_t s = p[0] | (p[1] << 8) | (p[2] << 16);
                if (s & 0x800000) s |= ~0xFFFFFF;
                buffer->interleaved[i] = s / 8388608.f;
            }
            break;
        case 32:
            if (formatTag == 3) {  // IEEE float
                for (int64_t i = 0; i < totalSamples; ++i, p += 4) {
                    float s;
                    memcpy(&s, p, 4);
                    buffer->interleaved[i] = s;
                }
            } else {
                for (int64_t i = 0; i < totalSamples; ++i, p += 4) {
                    const int32_t s = static_cast<int32_t>(readU32(p));
                    buffer->interleaved[i] = s / 2147483648.f;
                }
            }
            break;
        default:
            return nullptr;
    }
    return buffer;
}

// ---------------------------------------------------------------------------
// Writer
// ---------------------------------------------------------------------------

bool WavFile::Writer::open(const std::string& path, int sampleRate, int channels, int bitDepth) {
    fd_ = ::open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd_ < 0) return false;
    sampleRate_ = sampleRate;
    channels_ = channels;
    bitDepth_ = bitDepth;
    dataBytes_ = 0;

    const uint32_t byteRate = sampleRate * channels * (bitDepth / 8);
    const uint16_t blockAlign = channels * (bitDepth / 8);
    uint8_t header[44] = {};
    memcpy(header, "RIFF", 4);
    // RIFF size patched in close()
    memcpy(header + 8, "WAVE", 4);
    memcpy(header + 12, "fmt ", 4);
    header[16] = 16;  // fmt chunk size
    header[20] = (bitDepth == 32) ? 3 : 1;  // 3 = IEEE float
    header[22] = static_cast<uint8_t>(channels);
    header[24] = sampleRate & 0xFF; header[25] = (sampleRate >> 8) & 0xFF;
    header[26] = (sampleRate >> 16) & 0xFF; header[27] = (sampleRate >> 24) & 0xFF;
    header[28] = byteRate & 0xFF; header[29] = (byteRate >> 8) & 0xFF;
    header[30] = (byteRate >> 16) & 0xFF; header[31] = (byteRate >> 24) & 0xFF;
    header[32] = blockAlign & 0xFF; header[33] = blockAlign >> 8;
    header[34] = static_cast<uint8_t>(bitDepth);
    memcpy(header + 36, "data", 4);
    // data size patched in close()
    return ::write(fd_, header, 44) == 44;
}

bool WavFile::Writer::write(const float* data, int64_t frames) {
    if (fd_ < 0) return false;
    const int64_t samples = frames * channels_;
    if (bitDepth_ == 16) {
        std::vector<int16_t> tmp(samples);
        for (int64_t i = 0; i < samples; ++i) {
            const float clamped = data[i] < -1.f ? -1.f : (data[i] > 1.f ? 1.f : data[i]);
            tmp[i] = static_cast<int16_t>(clamped * 32767.f);
        }
        const ssize_t written = ::write(fd_, tmp.data(), static_cast<size_t>(samples) * 2);
        dataBytes_ += written;
        return written == samples * 2;
    }
    // 24-bit PCM
    std::vector<uint8_t> tmp(samples * 3);
    for (int64_t i = 0; i < samples; ++i) {
        const float clamped = data[i] < -1.f ? -1.f : (data[i] > 1.f ? 1.f : data[i]);
        const int32_t s = static_cast<int32_t>(clamped * 8388607.f);
        tmp[i * 3] = s & 0xFF;
        tmp[i * 3 + 1] = (s >> 8) & 0xFF;
        tmp[i * 3 + 2] = (s >> 16) & 0xFF;
    }
    const ssize_t written = ::write(fd_, tmp.data(), tmp.size());
    dataBytes_ += written;
    return written == static_cast<ssize_t>(tmp.size());
}

void WavFile::Writer::close() {
    if (fd_ < 0) return;
    const uint32_t riffSize = static_cast<uint32_t>(36 + dataBytes_);
    const uint32_t dataSize = static_cast<uint32_t>(dataBytes_);
    ::pwrite(fd_, &riffSize, 4, 4);
    ::pwrite(fd_, &dataSize, 4, 40);
    ::close(fd_);
    fd_ = -1;
}

}  // namespace studioone::engine
