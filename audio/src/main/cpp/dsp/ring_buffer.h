// SPDX-License-Identifier: MIT
// Lock-free single-producer/single-consumer ring buffer.
//
// REAL-TIME SAFETY: this structure performs no allocation, no locking and no
// syscalls on the read/write paths. It is the only queue type allowed on the
// audio callback thread (see docs/AUDIO_ENGINE.md).
#pragma once

#include <atomic>
#include <cstddef>
#include <memory>
#include <vector>

namespace studioone::dsp {

template <typename T>
class RingBuffer {
public:
    explicit RingBuffer(size_t capacity)
        : capacity_(capacity), buffer_(std::make_unique<T[]>(capacity)) {}

    RingBuffer(const RingBuffer&) = delete;
    RingBuffer& operator=(const RingBuffer&) = delete;

    /** Producer side. Returns number of items actually written. */
    size_t write(const T* data, size_t count) noexcept {
        const size_t head = head_.load(std::memory_order_relaxed);
        const size_t tail = tail_.load(std::memory_order_acquire);
        const size_t free = freeSpace(head, tail);
        if (count > free) count = free;
        for (size_t i = 0; i < count; ++i) {
            buffer_[(head + i) % capacity_] = data[i];
        }
        head_.store((head + count) % capacity_, std::memory_order_release);
        return count;
    }

    /** Consumer side. Returns number of items actually read. */
    size_t read(T* data, size_t count) noexcept {
        const size_t tail = tail_.load(std::memory_order_relaxed);
        const size_t head = head_.load(std::memory_order_acquire);
        const size_t available = (head + capacity_ - tail) % capacity_;
        if (count > available) count = available;
        for (size_t i = 0; i < count; ++i) {
            data[i] = buffer_[(tail + i) % capacity_];
        }
        tail_.store((tail + count) % capacity_, std::memory_order_release);
        return count;
    }

    size_t available() const noexcept {
        const size_t head = head_.load(std::memory_order_acquire);
        const size_t tail = tail_.load(std::memory_order_acquire);
        return (head + capacity_ - tail) % capacity_;
    }

    void clear() noexcept {
        tail_.store(head_.load(std::memory_order_relaxed), std::memory_order_relaxed);
    }

private:
    size_t freeSpace(size_t head, size_t tail) const noexcept {
        // One slot is always kept empty to distinguish full from empty.
        return (tail + capacity_ - head - 1) % capacity_;
    }

    const size_t capacity_;
    std::unique_ptr<T[]> buffer_;
    alignas(64) std::atomic<size_t> head_{0};
    alignas(64) std::atomic<size_t> tail_{0};
};

}  // namespace studioone::dsp
