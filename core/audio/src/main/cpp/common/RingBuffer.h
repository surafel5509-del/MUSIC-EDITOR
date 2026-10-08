#pragma once
// Lock-free single-producer / single-consumer ring buffer.
//
// Used on the audio hot path for:
//   * playback sample feed: Kotlin prefetch thread -> audio thread
//   * recording capture: audio thread -> WAV writer thread
//   * meter snapshots: audio thread -> UI polling thread
//
// Memory ordering: producer publishes with release, consumer acquires.
// Capacity is rounded up to a power of two so index wrapping is a bitmask.
// NO dynamic allocation after construction; the caller supplies storage.

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace s1::audio {

class SpscRingBuffer {
public:
    SpscRingBuffer() = default;

    /** [storage] must outlive the buffer and be at least capacity floats. */
    void init(float* storage, size_t capacityPow2) {
        buffer_ = storage;
        capacity_ = capacityPow2;
        mask_ = capacityPow2 - 1;
        writePos_.store(0, std::memory_order_relaxed);
        readPos_.store(0, std::memory_order_relaxed);
    }

    /** Producer: write up to [count] frames. Returns frames actually written. */
    size_t write(const float* src, size_t count) {
        const size_t w = writePos_.load(std::memory_order_relaxed);
        const size_t r = readPos_.load(std::memory_order_acquire);
        const size_t available = capacity_ - (w - r);
        if (count > available) count = available;
        for (size_t i = 0; i < count; ++i) {
            buffer_[(w + i) & mask_] = src[i];
        }
        writePos_.store(w + count, std::memory_order_release);
        return count;
    }

    /** Consumer: read up to [count] frames. Returns frames actually read. */
    size_t read(float* dst, size_t count) {
        const size_t r = readPos_.load(std::memory_order_relaxed);
        const size_t w = writePos_.load(std::memory_order_acquire);
        const size_t available = w - r;
        if (count > available) count = available;
        for (size_t i = 0; i < count; ++i) {
            dst[i] = buffer_[(r + i) & mask_];
        }
        readPos_.store(r + count, std::memory_order_release);
        return count;
    }

    /** Consumer: discard [count] frames (used when record monitoring drops late data). */
    void drain(size_t count) {
        const size_t r = readPos_.load(std::memory_order_relaxed);
        const size_t w = writePos_.load(std::memory_order_acquire);
        const size_t available = w - r;
        if (count > available) count = available;
        readPos_.store(r + count, std::memory_order_release);
    }

    size_t readable() const {
        return writePos_.load(std::memory_order_acquire) - readPos_.load(std::memory_order_acquire);
    }
    size_t writable() const { return capacity_ - readable(); }
    size_t capacity() const { return capacity_; }

private:
    float* buffer_ = nullptr;
    size_t capacity_ = 0;
    size_t mask_ = 0;
    // Separate cache lines to avoid false sharing between producer/consumer cores.
    alignas(64) std::atomic<size_t> writePos_{0};
    alignas(64) std::atomic<size_t> readPos_{0};
};

} // namespace s1::audio
