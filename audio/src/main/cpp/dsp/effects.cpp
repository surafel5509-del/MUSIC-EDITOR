// SPDX-License-Identifier: MIT
// Effect factory shared by the graph and the native tests.
#include "effect_base.h"
#include "effects.h"
#include "fx_time.h"

#include <memory>

namespace studioone::dsp {

// Concrete wrappers: adapt the value-typed DSP classes to EffectBase.
namespace {

template <typename T>
class Wrapper final : public EffectBase {
public:
    void prepare(double sampleRate) override { dsp_.prepare(sampleRate); }
    void setParameter(uint32_t id, float value) noexcept override { dsp_.setParameter(id, value); }
    void process(float* const* channels, int numChannels, int numFrames) noexcept override {
        dsp_.process(channels, numChannels, numFrames);
    }
    // Zero by default; specialized below for latency-reporting effects.
    int32_t latencyFrames() const noexcept override { return 0; }
    T dsp_;
};

template <>
int32_t Wrapper<Limiter>::latencyFrames() const noexcept { return dsp_.latencyFrames(); }

}  // namespace

std::unique_ptr<EffectBase> createEffect(uint32_t kind) {
    switch (static_cast<EffectKind>(kind)) {
        case EffectKind::ParametricEq:
        case EffectKind::GraphicEq:
        case EffectKind::Filter:
        case EffectKind::Autofilter:
        case EffectKind::DeEsser:
            return std::make_unique<Wrapper<ParametricEq>>();
        case EffectKind::Compressor:
        case EffectKind::Expander:
            return std::make_unique<Wrapper<Compressor>>();
        case EffectKind::Limiter:
            return std::make_unique<Wrapper<Limiter>>();
        case EffectKind::NoiseGate:
            return std::make_unique<Wrapper<NoiseGate>>();
        case EffectKind::Reverb:
        case EffectKind::Vocoder:
            return std::make_unique<Wrapper<Reverb>>();
        case EffectKind::Delay:
        case EffectKind::PitchShift:
        case EffectKind::TimeStretch:
            return std::make_unique<Wrapper<DelayFx>>();
        case EffectKind::Chorus:
        case EffectKind::Flanger:
            return std::make_unique<Wrapper<ChorusFx>>();
        case EffectKind::Phaser:
            return std::make_unique<Wrapper<PhaserFx>>();
        case EffectKind::Tremolo:
            return std::make_unique<Wrapper<TremoloFx>>();
        case EffectKind::Distortion:
        case EffectKind::Overdrive:
        case EffectKind::AmpSim:
        case EffectKind::CabinetSim:
            return std::make_unique<Wrapper<DistortionFx>>();
        case EffectKind::Bitcrusher:
            return std::make_unique<Wrapper<BitcrusherFx>>();
    }
    return nullptr;
}

}  // namespace studioone::dsp
