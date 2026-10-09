// SPDX-License-Identifier: MIT
// Polymorphic effect interface used by the audio graph.
#pragma once

#include <cstdint>
#include <memory>

namespace studioone::dsp {

/** Common interface the graph uses to drive any effect. */
class EffectBase {
public:
    virtual ~EffectBase() = default;
    virtual void prepare(double sampleRate) = 0;
    virtual void setParameter(uint32_t id, float value) noexcept = 0;
    virtual void process(float* const* channels, int numChannels, int numFrames) noexcept = 0;
    /** Extra latency introduced (e.g. lookahead limiter) for compensation. */
    virtual int32_t latencyFrames() const noexcept { return 0; }
};

/** Mirrors com.studioone.core.domain.model.EffectType ordinal order. */
enum class EffectKind : uint32_t {
    ParametricEq = 0, GraphicEq, Compressor, Limiter, NoiseGate, Expander,
    DeEsser, Reverb, Delay, Chorus, Flanger, Phaser, Tremolo, Autofilter,
    Distortion, Overdrive, Bitcrusher, AmpSim, CabinetSim, Filter,
    PitchShift, TimeStretch, Vocoder,
};

/** Factory: builds the DSP object for an EffectKind. Returns null if unknown. */
std::unique_ptr<EffectBase> createEffect(uint32_t kind);

}  // namespace studioone::dsp
