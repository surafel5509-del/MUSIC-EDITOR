#pragma once
// RAM sample pool: all decoded PCM used by instruments lives here in float
// form. Samples are loaded via kLoadSample commands (payload ownership
// transfers to the pool); unloads defer freeing through the ReclaimQueue so
// the audio thread never calls free() while a voice may still be reading.

#include "../common/Types.h"
#include "../engine/EngineCommands.h"
#include <atomic>
#include <cstdlib>

namespace s1::audio::instrument {

struct PoolSample {
    float* data = nullptr;      // interleaved float, owned
    int64_t frames = 0;
    int32_t channels = 1;
    int32_t sampleRate = 48000;
    bool inUse = false;
};

class SamplePool {
public:
    static constexpr int32_t kSlots = 512;

    SamplePool() = default;
    ~SamplePool() {
        for (auto& s : slots_) { /* control-thread teardown: direct free ok */ std::free(s.data); s.data = nullptr; }
    }

    /** Audio thread: adopt command payload (already-allocated float array). */
    void loadFromCommand(const engine::EngineCommand& cmd) {
        const int32_t slot = static_cast<int32_t>(cmd.lval);
        if (slot < 0 || slot >= kSlots || !cmd.payload) return;
        PoolSample& s = slots_[slot];
        if (s.data) std::free(s.data); // safe: slot reuse only after kUnloadSample
        s.data = static_cast<float*>(cmd.payload);
        s.frames = static_cast<int64_t>(cmd.fval);
        s.channels = cmd.arg1 > 0 ? cmd.arg1 : 1;
        s.sampleRate = cmd.arg2 > 0 ? cmd.arg2 : 48000;
        s.inUse = true;
    }

    /** Audio thread: release; pointer handed to reclaim queue for deferred free. */
    void unload(int32_t slot, engine::ReclaimQueue& reclaim) {
        if (slot < 0 || slot >= kSlots) return;
        PoolSample& s = slots_[slot];
        if (s.data) {
            reclaim.push(s.data); // control thread frees later
            s.data = nullptr;
        }
        s.inUse = false;
        s.frames = 0;
    }

    const PoolSample* get(int32_t slot) const {
        if (slot < 0 || slot >= kSlots) return nullptr;
        return slots_[slot].inUse ? &slots_[slot] : nullptr;
    }

    int32_t firstFreeSlot() const {
        for (int32_t i = 0; i < kSlots; ++i) if (!slots_[i].inUse) return i;
        return -1;
    }

private:
    PoolSample slots_[kSlots];
};

} // namespace s1::audio::instrument
