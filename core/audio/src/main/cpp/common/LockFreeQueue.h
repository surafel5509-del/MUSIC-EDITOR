#pragma once
// Bounded MPSC (multi-producer single-consumer) queue for engine commands and
// MIDI events. Producers: any Kotlin/JNI thread. Consumer: the audio thread,
// drained exactly once at the top of each callback.
//
// Implementation: fixed-size slot array with per-slot sequence counters
// (Vyukov-style bounded MPMC queue, used here MPSC). Never allocates, never
// blocks; when full, producers drop the command and bump a counter that the
// UI can surface ("engine command overflow" telemetry).

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace s1::audio {

template <typename T, size_t Capacity>
class LockFreeQueue {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of two");

    struct Slot {
        std::atomic<size_t> sequence{0};
        T data;
    };

public:
    LockFreeQueue() {
        for (size_t i = 0; i < Capacity; ++i) {
            slots_[i].sequence.store(i, std::memory_order_relaxed);
        }
    }

    /** Producer side. Returns false when the queue is full (command dropped). */
    bool push(const T& item) {
        size_t pos = enqueuePos_.load(std::memory_order_relaxed);
        for (;;) {
            Slot& slot = slots_[pos & kMask];
            const size_t seq = slot.sequence.load(std::memory_order_acquire);
            const intptr_t diff = static_cast<intptr_t>(seq) - static_cast<intptr_t>(pos);
            if (diff == 0) {
                if (enqueuePos_.compare_exchange_weak(pos, pos + 1, std::memory_order_relaxed)) {
                    slot.data = item;
                    slot.sequence.store(pos + 1, std::memory_order_release);
                    return true;
                }
            } else if (diff < 0) {
                return false; // full
            } else {
                pos = enqueuePos_.load(std::memory_order_relaxed);
            }
        }
    }

    /** Consumer side (audio thread). Returns false when empty. */
    bool pop(T& out) {
        Slot& slot = slots_[dequeuePos_ & kMask];
        const size_t seq = slot.sequence.load(std::memory_order_acquire);
        const intptr_t diff = static_cast<intptr_t>(seq) - static_cast<intptr_t>(dequeuePos_ + 1);
        if (diff < 0) return false; // empty
        out = slot.data;
        slot.sequence.store(dequeuePos_ + Capacity, std::memory_order_release);
        ++dequeuePos_;
        return true;
    }

    size_t dropped() const { return dropped_.load(std::memory_order_relaxed); }
    void noteDrop() { dropped_.fetch_add(1, std::memory_order_relaxed); }

private:
    static constexpr size_t kMask = Capacity - 1;
    Slot slots_[Capacity];
    alignas(64) std::atomic<size_t> enqueuePos_{0};
    alignas(64) size_t dequeuePos_ = 0; // only touched by the single consumer
    std::atomic<size_t> dropped_{0};
};

} // namespace s1::audio
