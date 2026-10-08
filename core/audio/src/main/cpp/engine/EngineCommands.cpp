// Command builders: tiny helpers so the JNI layer (and future native-side
// automation) never hand-rolls struct literals. Keeping builders in one file
// guarantees the payload-ownership rules are applied consistently.

#include "EngineCommands.h"
#include <cstdlib>
#include <cstring>

namespace s1::audio::engine {

EngineCommand makeSimple(CommandType type, int32_t target, int32_t arg1, float fval) {
    EngineCommand c;
    c.type = type;
    c.target = target;
    c.arg1 = arg1;
    c.fval = fval;
    return c;
}

EngineCommand makeFxSlot(int32_t target, int32_t slot, int32_t pluginId,
                         const float* paramPairs, int32_t pairCount) {
    EngineCommand c;
    c.type = CommandType::kSetFxSlot;
    c.target = target;
    c.arg1 = slot;
    c.arg2 = pluginId;
    if (paramPairs && pairCount > 0) {
        // Ownership transfers to the engine; freed via ReclaimQueue.
        float* copy = static_cast<float*>(std::malloc(sizeof(float) * pairCount * 2));
        if (copy) {
            std::memcpy(copy, paramPairs, sizeof(float) * pairCount * 2);
            c.payload = copy;
            c.lval = pairCount;
        }
    }
    return c;
}

EngineCommand makeAutomationUpload(int32_t target, int32_t paramId,
                                   const float* pointsPosValue, int32_t pointCount) {
    EngineCommand c;
    c.type = CommandType::kUploadAutomation;
    c.target = target;
    c.arg1 = paramId;
    c.lval = pointCount;
    if (pointsPosValue && pointCount > 0) {
        float* copy = static_cast<float*>(std::malloc(sizeof(float) * pointCount * 2));
        if (copy) {
            std::memcpy(copy, pointsPosValue, sizeof(float) * pointCount * 2);
            c.payload = copy;
        } else {
            c.type = CommandType::kNop; // allocation failed: drop safely
        }
    }
    return c;
}

EngineCommand makeSampleLoad(int32_t slot, float* interleavedOwned, int64_t frames,
                             int32_t channels, int32_t sampleRate) {
    EngineCommand c;
    c.type = CommandType::kLoadSample;
    c.lval = slot;
    c.fval = static_cast<float>(frames);
    c.arg1 = channels;
    c.arg2 = sampleRate;
    c.payload = interleavedOwned; // caller allocated with malloc; ownership transfers
    return c;
}

EngineCommand makeInstrument(int32_t target, int32_t instrumentType,
                             const float* presetBlob, int32_t blobFloats) {
    EngineCommand c;
    c.type = CommandType::kSetInstrument;
    c.target = target;
    c.arg1 = instrumentType;
    if (presetBlob && blobFloats > 0) {
        float* copy = static_cast<float*>(std::malloc(sizeof(float) * blobFloats));
        if (copy) {
            std::memcpy(copy, presetBlob, sizeof(float) * blobFloats);
            c.payload = copy;
        }
    }
    return c;
}

} // namespace s1::audio::engine
