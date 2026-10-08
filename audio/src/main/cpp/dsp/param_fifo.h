// SPDX-License-Identifier: MIT
// Lock-free parameter FIFO: UI thread -> audio callback.
//
// Parameter changes are enqueued from the UI thread and drained at the top of
// every audio callback, so the DSP graph never takes a lock and parameters
// never tear mid-buffer.
#pragma once

#include <cstdint>
#include "ring_buffer.h"

namespace studioone::dsp {

/** Addressing: node ids are assigned by the graph; see AudioGraph. */
struct ParamMessage {
    uint32_t nodeId;
    uint32_t paramId;
    float value;
    uint32_t pad{0};  // keep 16 bytes => cache friendly
};

static_assert(sizeof(ParamMessage) == 16);

class ParamFifo {
public:
    ParamFifo() : ring_(4096) {}

    /** UI thread. Drops messages when full (last-write-wins semantics). */
    bool post(uint32_t nodeId, uint32_t paramId, float value) noexcept {
        const ParamMessage msg{nodeId, paramId, value, 0};
        return ring_.write(&msg, 1) == 1;
    }

    /** Audio thread: drain everything pending. */
    template <typename Handler>
    void drain(Handler&& handler) noexcept {
        ParamMessage msg;
        while (ring_.read(&msg, 1) == 1) {
            handler(msg.nodeId, msg.paramId, msg.value);
        }
    }

private:
    RingBuffer<ParamMessage> ring_;
};

}  // namespace studioone::dsp
