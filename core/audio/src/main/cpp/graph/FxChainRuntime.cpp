#include "FxChainRuntime.h"
#include "../fx/Compressor.h"
#include "../fx/Reverb.h"
#include "../fx/Modulation.h"
#include "../fx/PitchShift.h"
#include "../fx/Delay.h"
#include "../fx/Gain.h"
#include <algorithm>
#include <cstring>

namespace s1::audio::graph {

// ── Factory ──────────────────────────────────────────────────────────────────

std::unique_ptr<fx::FxUnit> createFxUnit(int32_t pluginId) {
    switch (pluginId) {
        case kPluginCompressor: return std::make_unique<fx::Compressor>();
        case kPluginExpander: {
            auto c = std::make_unique<fx::Compressor>(); // ratio < 1 set via params
            return c;
        }
        case kPluginLimiter: return std::make_unique<fx::Limiter>();
        case kPluginGate: return std::make_unique<fx::Gate>();
        case kPluginDeEsser: return std::make_unique<fx::DeEsser>();
        case kPluginParametricEq: return std::make_unique<fx::ParametricEqUnit>();
        case kPluginHighPass: return std::make_unique<fx::SimpleFilterUnit>(dsp::BiquadType::HighPass);
        case kPluginLowPass: return std::make_unique<fx::SimpleFilterUnit>(dsp::BiquadType::LowPass);
        case kPluginAutoFilter: return std::make_unique<fx::AutoFilter>();
        case kPluginReverb: return std::make_unique<fx::Reverb>();
        case kPluginDelay: return std::make_unique<fx::DelayUnit>(false);
        case kPluginPingPongDelay: return std::make_unique<fx::DelayUnit>(true);
        case kPluginChorus: return std::make_unique<fx::ModulatedDelay>();
        case kPluginFlanger: {
            auto m = std::make_unique<fx::ModulatedDelay>();
            m->setFlangerMode(true);
            return m;
        }
        case kPluginPhaser: return std::make_unique<fx::Phaser>();
        case kPluginTremolo: return std::make_unique<fx::TremoloPan>();
        case kPluginAutopan: {
            auto t = std::make_unique<fx::TremoloPan>();
            t->setParam(1, 0.f);   // depth 0
            t->setParam(2, 1.f);   // pan full
            return t;
        }
        case kPluginDistortion: return std::make_unique<fx::Distortion>();
        case kPluginOverdrive: {
            auto d = std::make_unique<fx::Distortion>();
            d->setParam(3, static_cast<float>(fx::Distortion::SoftClip));
            return d;
        }
        case kPluginAmpSim: {
            auto d = std::make_unique<fx::Distortion>();
            d->setParam(3, static_cast<float>(fx::Distortion::Asymmetric));
            d->setParam(1, 0.35f); // darker tone = cab-ish
            return d;
        }
        case kPluginBitcrusher: return std::make_unique<fx::Bitcrusher>();
        case kPluginTapeSaturation: return std::make_unique<fx::TapeSaturation>();
        case kPluginPitchShift: return std::make_unique<fx::PitchShift>();
        case kPluginGain: return std::make_unique<fx::GainUnit>();
        case kPluginAnalyzer:
        case kPluginLoudnessMeter:
            return nullptr; // metering-only plugins live in the graph's meter taps
        default:
            return nullptr;
    }
}

// ── FxChainRuntime ───────────────────────────────────────────────────────────

void FxChainRuntime::init(FxPool* pool, float sampleRate) {
    pool_ = pool;
    sr_ = sampleRate;
    dryL_.resize(kMaxFramesPerBlock);
    dryR_.resize(kMaxFramesPerBlock);
}

FxChainRuntime::~FxChainRuntime() = default;

bool FxChainRuntime::setSlot(int slotIndex, int32_t pluginId) {
    if (slotIndex < 0 || slotIndex >= kMaxInsertSlots) return false;
    FxSlotRuntime& slot = slots_[slotIndex];
    if (slot.pluginId == pluginId) return false;
    if (slot.unit) {
        pool_->retireDeferred(slot.unit, slot.pluginId, 0);
        slot.unit = nullptr;
    }
    slot.pluginId = pluginId;
    if (pluginId != kPluginNone) {
        slot.unit = pool_->acquire(pluginId);
        if (!slot.unit) { slot.pluginId = kPluginNone; return false; } // pool exhausted
        slot.unit->init(sr_);
        slot.latency = slot.unit->latencyFrames();
    } else {
        slot.latency = 0;
    }
    return true;
}

void FxChainRuntime::setParam(int slotIndex, int32_t paramIndex, float value) {
    if (slotIndex < 0 || slotIndex >= kMaxInsertSlots) return;
    if (slots_[slotIndex].unit) slots_[slotIndex].unit->setParam(paramIndex, value);
}

void FxChainRuntime::setBypass(int slotIndex, bool bypass) {
    if (slotIndex < 0 || slotIndex >= kMaxInsertSlots) return;
    slots_[slotIndex].bypassed = bypass;
}

void FxChainRuntime::setWetMix(int slotIndex, float mix) {
    if (slotIndex < 0 || slotIndex >= kMaxInsertSlots) return;
    slots_[slotIndex].wetMix = std::clamp(mix, 0.f, 1.f);
}

void FxChainRuntime::moveSlot(int from, int to) {
    if (from < 0 || from >= kMaxInsertSlots || to < 0 || to >= kMaxInsertSlots || from == to) return;
    // Rotate between from/to preserving order (drag reorder semantics).
    FxSlotRuntime moving = slots_[from];
    const int step = from < to ? 1 : -1;
    for (int i = from; i != to; i += step) slots_[i] = slots_[i + step];
    slots_[to] = moving;
}

void FxChainRuntime::clearAll() {
    for (int i = 0; i < kMaxInsertSlots; ++i) {
        if (slots_[i].unit) pool_->retireDeferred(slots_[i].unit, slots_[i].pluginId, 0);
        slots_[i] = FxSlotRuntime{};
    }
}

void FxChainRuntime::process(float* left, float* right, FrameCount frames) {
    for (int i = 0; i < kMaxInsertSlots; ++i) {
        const FxSlotRuntime& slot = slots_[i];
        if (!slot.unit || slot.bypassed) continue;
        if (slot.wetMix >= 0.999f) {
            slot.unit->process(left, right, frames);
        } else {
            // Parallel (NY-style) processing: keep a dry copy, blend.
            std::memcpy(dryL_.data(), left, sizeof(float) * frames);
            std::memcpy(dryR_.data(), right, sizeof(float) * frames);
            slot.unit->process(left, right, frames);
            const float w = slot.wetMix, d = 1.f - w;
            for (FrameCount f = 0; f < frames; ++f) {
                left[f]  = w * left[f]  + d * dryL_[f];
                right[f] = w * right[f] + d * dryR_[f];
            }
        }
    }
}

FrameCount FxChainRuntime::totalLatency() const {
    FrameCount sum = 0;
    for (const auto& s : slots_) if (s.unit && !s.bypassed) sum += s.latency;
    return sum;
}

FrameCount FxChainRuntime::maxTail() const {
    FrameCount maxT = 0;
    for (const auto& s : slots_) if (s.unit) maxT = std::max(maxT, s.unit->tailFrames());
    return maxT;
}

// ── FxPool ───────────────────────────────────────────────────────────────────

void FxPool::init(float sampleRate) {
    sr_ = sampleRate;
    // Instance counts per plugin type. Heavy memory units get smaller pools.
    static const int kCounts[kPluginCount] = {
        [kPluginCompressor] = 96, [kPluginLimiter] = 24, [kPluginGate] = 96,
        [kPluginExpander] = 32,   [kPluginDeEsser] = 24, [kPluginParametricEq] = 96,
        [kPluginHighPass] = 48,   [kPluginLowPass] = 48, [kPluginAutoFilter] = 24,
        [kPluginReverb] = 12,     [kPluginDelay] = 24,   [kPluginPingPongDelay] = 16,
        [kPluginChorus] = 16,     [kPluginFlanger] = 8,  [kPluginPhaser] = 8,
        [kPluginTremolo] = 8,     [kPluginAutopan] = 8,  [kPluginDistortion] = 24,
        [kPluginOverdrive] = 24,  [kPluginBitcrusher] = 8,[kPluginAmpSim] = 16,
        [kPluginTapeSaturation] = 16, [kPluginPitchShift] = 8, [kPluginGain] = 96,
    };
    for (int32_t id = 1; id < kPluginCount; ++id) {
        for (int i = 0; i < kCounts[id]; ++i) {
            auto unit = createFxUnit(id);
            if (!unit) break;
            unit->init(sampleRate);
            freeLists_[id].push_back(Entry{unit.get(), id});
            owned_.push_back(std::move(unit));
        }
    }
}

FxPool::~FxPool() = default;

fx::FxUnit* FxPool::acquire(int32_t pluginId) {
    if (pluginId <= 0 || pluginId >= kPluginCount) return nullptr;
    auto& list = freeLists_[pluginId];
    if (list.empty()) return nullptr;
    fx::FxUnit* unit = list.back().unit;
    list.pop_back();
    unit->reset();
    return unit;
}

void FxPool::retireDeferred(fx::FxUnit* unit, int32_t pluginId, uint64_t blockCounter) {
    if (!unit) return;
    // Retire window: 4 callbacks. blockCounter is provided by the graph.
    retired_.push_back(RetiredEntry{unit, pluginId, blockCounter + 4});
}

void FxPool::processDeferred(uint64_t blockCounter) {
    if (retired_.empty()) return;
    auto it = retired_.begin();
    while (it != retired_.end()) {
        if (blockCounter >= it->retireAtBlock) {
            if (it->pluginId > 0 && it->pluginId < kPluginCount) {
                freeLists_[it->pluginId].push_back(Entry{it->unit, it->pluginId});
            }
            it = retired_.erase(it);
        } else {
            ++it;
        }
    }
}

int32_t FxPool::poolPressure() const {
    // Telemetry: percentage of the smallest free list consumed. Cheap enough
    // for a 1Hz poll from the control thread.
    size_t total = 0, freeCount = 0;
    for (int32_t id = 1; id < kPluginCount; ++id) {
        freeCount += freeLists_[id].size();
    }
    total = owned_.size();
    if (total == 0) return 0;
    return static_cast<int32_t>(100 - (freeCount * 100) / total);
}

} // namespace s1::audio::graph
