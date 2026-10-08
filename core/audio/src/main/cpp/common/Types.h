#pragma once
// Core audio types & compile-time limits. All buffers in the engine are
// pre-allocated from these constants at init — nothing is allocated per block.

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace s1::audio {

using Sample = float;           // internal format is always 32-bit float
using FrameCount = int32_t;
using FramePosition = int64_t;  // absolute timeline position in frames
using TickPosition = int64_t;   // musical position in PPQ ticks (480 PPQ)

inline constexpr int kMaxChannels        = 8;    // per-track channel count (7.1 future-proof)
inline constexpr int kMaxTracks          = 256;  // engine track pool (Pro+ ceiling)
inline constexpr int kMaxBuses           = 16;   // subgroup + aux returns
inline constexpr int kMaxInsertSlots     = 8;    // FX inserts per strip
inline constexpr int kMaxSends           = 4;    // aux sends per strip
inline constexpr int kMaxVoices          = 32;   // per-instrument polyphony
inline constexpr int kMaxFramesPerBlock  = 512;  // matches largest selectable buffer
inline constexpr int kPpq                = 480;  // ticks per quarter note
inline constexpr float kSilenceDb        = -144.0f;
inline constexpr float kUnityLinear      = 1.0f;

/** Two-channel interleaved-free stereo buffer: pointers into pre-allocated pool. */
struct StereoBuffer {
    Sample* left  = nullptr;
    Sample* right = nullptr;
    FrameCount frames = 0;

    void clear(FrameCount n) {
        for (FrameCount i = 0; i < n; ++i) { left[i] = 0.f; right[i] = 0.f; }
        frames = n;
    }
    void addFrom(const StereoBuffer& other, FrameCount n) {
        for (FrameCount i = 0; i < n; ++i) {
            left[i]  += other.left[i];
            right[i] += other.right[i];
        }
    }
    void applyGain(Sample g, FrameCount n) {
        for (FrameCount i = 0; i < n; ++i) { left[i] *= g; right[i] *= g; }
    }
};

/** Engine-wide immutable config captured at init (sample rate may change on device switch). */
struct EngineConfig {
    int32_t sampleRate = 48000;
    FrameCount framesPerBlock = 128;
    int32_t inputChannels = 1;
    int32_t outputChannels = 2;
    bool lowLatencyMode = true;
    bool exclusiveMmap = false;
};

/** Denormal protection: bias trick avoids per-sample branch; SSE/NEON flush modes
 *  are not portable across all Android ABIs. */
struct DenormalGuard {
    static inline Sample protect(Sample x) {
        // Adding ~1e-18 keeps denormals in normal range with inaudible cost.
        return x + 1e-18f - 1e-18f;
    }
};

} // namespace s1::audio
